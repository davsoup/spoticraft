package dev.spotifyhud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Spotify Authorization Code flow with PKCE (no client secret needed).
 * A throwaway listener on 127.0.0.1 catches the redirect. Uses a plain ServerSocket
 * so it doesn't depend on the jdk.httpserver module being present in the game's runtime.
 */
public final class SpotifyAuth {
    private static final Logger LOG = LoggerFactory.getLogger("spotifyhud");
    private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SCOPES = "user-read-playback-state user-modify-playback-state"
            + " playlist-read-private playlist-read-collaborative user-library-read";

    private final SpotifyConfig config;
    private final Path tokenFile;
    private final HttpClient http;
    private final AtomicBoolean loginInProgress = new AtomicBoolean(false);
    private final SecureRandom random = new SecureRandom();

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long expiresAtMs;

    public SpotifyAuth(SpotifyConfig config, Path tokenFile, HttpClient http) {
        this.config = config;
        this.tokenFile = tokenFile;
        this.http = http;
        loadRefreshToken();
    }

    public boolean hasLogin() {
        String t = refreshToken;
        return t != null && !t.isBlank();
    }

    public boolean isLoginInProgress() {
        return loginInProgress.get();
    }

    public String redirectUri() {
        return "http://127.0.0.1:" + config.redirectPort + "/callback";
    }

    /** Returns a valid access token, refreshing it when it is about to expire. Blocking. */
    public synchronized String accessToken(boolean forceRefresh)
            throws IOException, InterruptedException, SpotifyException {
        if (!hasLogin()) throw new SpotifyException(401, "Not logged in to Spotify", 0);
        if (forceRefresh || accessToken == null || System.currentTimeMillis() > expiresAtMs - 30_000) {
            JsonObject res = tokenRequest(orderedMap(
                    "grant_type", "refresh_token",
                    "refresh_token", refreshToken,
                    "client_id", config.clientId));
            applyTokens(res);
        }
        return accessToken;
    }

    public synchronized void logout() {
        accessToken = null;
        refreshToken = null;
        expiresAtMs = 0;
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException ignored) {
        }
    }

    /** Opens the browser and finishes login in the background. {@code say} is called from a worker thread. */
    public void startLogin(Consumer<String> say) {
        if (config.clientId == null || config.clientId.isBlank()) {
            say.accept("Set \"clientId\" in config/spotifyhud.json, then run /spotify reload");
            return;
        }
        if (!loginInProgress.compareAndSet(false, true)) {
            say.accept("Login already in progress - check your browser");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                runLogin(say);
            } catch (Exception e) {
                LOG.warn("Spotify login failed", e);
                say.accept("Login failed: " + e.getMessage());
            } finally {
                loginInProgress.set(false);
            }
        }, "SpotifyHud-Login");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- login flow

    private void runLogin(Consumer<String> say) throws Exception {
        String verifier = randomUrlSafe(64);
        String challenge = base64Url(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = randomUrlSafe(16);

        Map<String, String> query = orderedMap(
                "response_type", "code",
                "client_id", config.clientId,
                "scope", SCOPES,
                "redirect_uri", redirectUri(),
                "code_challenge_method", "S256",
                "code_challenge", challenge,
                "state", state);
        String url = AUTH_URL + "?" + form(query).replace("+", "%20");

        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                    config.redirectPort));

            say.accept("Opening Spotify login in your browser...");
            LOG.info("Spotify login URL (open manually if no browser appeared): {}", url);
            try {
                openBrowser(url);
            } catch (IOException e) {
                say.accept("Couldn't open a browser - copy the login URL from the game log");
            }

            String code = waitForCode(server, state);
            JsonObject res = tokenRequest(orderedMap(
                    "grant_type", "authorization_code",
                    "code", code,
                    "redirect_uri", redirectUri(),
                    "client_id", config.clientId,
                    "code_verifier", verifier));
            applyTokens(res);
            say.accept("Logged in to Spotify");
        }
    }

    private String waitForCode(ServerSocket server, String expectedState) throws IOException {
        long deadline = System.currentTimeMillis() + 180_000L;
        while (System.currentTimeMillis() < deadline) {
            server.setSoTimeout((int) Math.max(1000L, deadline - System.currentTimeMillis()));
            try (Socket s = server.accept()) {
                s.setSoTimeout(5000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String requestLine = reader.readLine(); // e.g. "GET /callback?code=...&state=... HTTP/1.1"
                if (requestLine == null) continue;
                String[] parts = requestLine.split(" ");
                if (parts.length < 2 || !parts[1].startsWith("/callback")) {
                    respond(s, "404 Not Found", "Not found");
                    continue;
                }
                Map<String, String> q = parseQuery(parts[1]);
                if (!expectedState.equals(q.get("state"))) {
                    respond(s, "400 Bad Request", "State mismatch. Try logging in again from Minecraft.");
                    continue;
                }
                if (q.containsKey("error")) {
                    respond(s, "200 OK", "Login cancelled. You can close this tab.");
                    throw new IOException("Spotify said: " + q.get("error"));
                }
                String code = q.get("code");
                if (code == null || code.isBlank()) {
                    respond(s, "400 Bad Request", "Missing code");
                    continue;
                }
                respond(s, "200 OK", "Spotify HUD is connected. You can close this tab and go back to Minecraft.");
                return code;
            } catch (SocketTimeoutException ignored) {
                // keep waiting until the deadline
            }
        }
        throw new IOException("Timed out waiting for the browser login");
    }

    private static void respond(Socket s, String status, String message) throws IOException {
        byte[] body = ("<!doctype html><meta charset=utf-8><title>Spotify HUD</title>"
                + "<body style=\"font-family:sans-serif;background:#111;color:#eee;padding:3em\">"
                + "<h2>" + message + "</h2></body>").getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static void openBrowser(String url) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } else if (os.contains("mac")) {
            new ProcessBuilder("open", url).start();
        } else {
            new ProcessBuilder("xdg-open", url).start();
        }
    }

    // ---------------------------------------------------------------- tokens

    private JsonObject tokenRequest(Map<String, String> formData)
            throws IOException, InterruptedException, SpotifyException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(formData)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        String body = res.body() == null ? "" : res.body();
        if (res.statusCode() != 200) {
            if (body.contains("invalid_grant")) {
                logout(); // refresh token revoked/expired (Spotify expires them after ~6 months)
                throw new SpotifyException(400, "Spotify login expired - press the login key again", 0);
            }
            if (body.contains("invalid_client")) {
                throw new SpotifyException(400, "Spotify rejected the clientId in your config", 0);
            }
            throw new SpotifyException(res.statusCode(), "Spotify token error (" + res.statusCode() + ")", 0);
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private void applyTokens(JsonObject o) {
        accessToken = o.get("access_token").getAsString();
        long expiresIn = o.has("expires_in") ? o.get("expires_in").getAsLong() : 3600L;
        expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L;
        JsonElement rt = o.get("refresh_token");
        if (rt != null && !rt.isJsonNull()) {
            refreshToken = rt.getAsString();
            saveRefreshToken();
        }
    }

    private void loadRefreshToken() {
        try {
            if (Files.exists(tokenFile)) {
                JsonObject o = JsonParser.parseString(Files.readString(tokenFile)).getAsJsonObject();
                JsonElement rt = o.get("refresh_token");
                if (rt != null && !rt.isJsonNull()) refreshToken = rt.getAsString();
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Couldn't read Spotify token file", e);
        }
    }

    private void saveRefreshToken() {
        try {
            Files.createDirectories(tokenFile.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("refresh_token", refreshToken);
            Files.writeString(tokenFile, o.toString());
            try {
                tokenFile.toFile().setReadable(false, false);
                tokenFile.toFile().setReadable(true, true);
                tokenFile.toFile().setWritable(false, false);
                tokenFile.toFile().setWritable(true, true);
            } catch (SecurityException ignored) {
            }
        } catch (IOException e) {
            LOG.warn("Couldn't save Spotify token file", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static String form(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String pathAndQuery) {
        Map<String, String> m = new LinkedHashMap<>();
        int q = pathAndQuery.indexOf('?');
        if (q < 0) return m;
        for (String pair : pathAndQuery.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return base64Url(b);
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
