package dev.spotifyhud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Thin blocking wrapper over the Spotify Web API player endpoints. Call from a worker thread only. */
public final class SpotifyApi {
    private static final String BASE = "https://api.spotify.com/v1";

    private final SpotifyAuth auth;
    private final HttpClient http;

    public SpotifyApi(SpotifyAuth auth, HttpClient http) {
        this.auth = auth;
        this.http = http;
    }

    // ---------------------------------------------------------------- reads

    public PlaybackState getPlayback() throws IOException, InterruptedException, SpotifyException {
        HttpResponse<String> res = call("GET", "/me/player?additional_types=episode");
        int sc = res.statusCode();
        if (sc == 204) return PlaybackState.INACTIVE; // nothing active on any device
        if (sc != 200) throw error(res);
        String body = res.body();
        if (body == null || body.isBlank()) return PlaybackState.INACTIVE;

        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        boolean playing = bool(o, "is_playing");
        long progress = lng(o, "progress_ms");

        int volume = 0;
        boolean volumeSupported = false;
        JsonObject device = obj(o, "device");
        if (device != null && present(device, "volume_percent")) {
            volume = device.get("volume_percent").getAsInt();
            volumeSupported = true;
        }

        String title = "";
        String artist = "";
        long duration = 0;
        JsonObject item = obj(o, "item");
        if (item != null) {
            title = str(item, "name");
            duration = lng(item, "duration_ms");
            JsonArray artists = item.has("artists") && item.get("artists").isJsonArray()
                    ? item.getAsJsonArray("artists") : null;
            if (artists != null && artists.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement a : artists) {
                    if (!a.isJsonObject()) continue;
                    String n = str(a.getAsJsonObject(), "name");
                    if (n.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(n);
                }
                artist = sb.toString();
            } else {
                JsonObject show = obj(item, "show"); // podcast episode
                if (show != null) artist = str(show, "name");
            }
        } else if ("ad".equals(str(o, "currently_playing_type"))) {
            title = "Advertisement";
        }

        return new PlaybackState(true, playing, title, artist, progress, duration,
                volume, volumeSupported, System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- commands

    public void play() throws IOException, InterruptedException, SpotifyException {
        command("PUT", "/me/player/play");
    }

    public void pause() throws IOException, InterruptedException, SpotifyException {
        command("PUT", "/me/player/pause");
    }

    public void next() throws IOException, InterruptedException, SpotifyException {
        command("POST", "/me/player/next");
    }

    public void previous() throws IOException, InterruptedException, SpotifyException {
        command("POST", "/me/player/previous");
    }

    public void seek(long positionMs) throws IOException, InterruptedException, SpotifyException {
        command("PUT", "/me/player/seek?position_ms=" + Math.max(0L, positionMs));
    }

    public void setVolume(int percent) throws IOException, InterruptedException, SpotifyException {
        command("PUT", "/me/player/volume?volume_percent=" + Math.max(0, Math.min(100, percent)));
    }

    // ---------------------------------------------------------------- plumbing

    private void command(String method, String path) throws IOException, InterruptedException, SpotifyException {
        HttpResponse<String> res = call(method, path);
        int sc = res.statusCode();
        if (sc >= 200 && sc < 300) return;
        throw error(res);
    }

    private HttpResponse<String> call(String method, String path)
            throws IOException, InterruptedException, SpotifyException {
        HttpResponse<String> res = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = auth.accessToken(attempt > 0);
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + token)
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 401) break; // on 401, retry once with a freshly refreshed token
        }
        return res;
    }

    private static SpotifyException error(HttpResponse<String> res) {
        int sc = res.statusCode();
        String reason = "";
        String message = "";
        try {
            JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
            JsonObject err = obj(root, "error");
            if (err != null) {
                reason = str(err, "reason");
                message = str(err, "message");
            }
        } catch (RuntimeException ignored) {
        }

        long retry = 0;
        if (sc == 429) {
            retry = res.headers().firstValueAsLong("Retry-After").orElse(5L);
            return new SpotifyException(sc, "Spotify rate limit - retrying in " + retry + "s", retry);
        }
        if (sc == 404 || "NO_ACTIVE_DEVICE".equals(reason)) {
            return new SpotifyException(sc, "No active Spotify device - press play in Spotify first", 0);
        }
        if (sc == 401) return new SpotifyException(sc, "Spotify login rejected - press the login key", 0);
        if (sc == 403) {
            String m = "PREMIUM_REQUIRED".equals(reason) ? "Spotify Premium is required for this control"
                    : message.isEmpty() ? "Spotify refused that action" : message;
            return new SpotifyException(sc, m, 0);
        }
        return new SpotifyException(sc, message.isEmpty() ? "Spotify error " + sc : message, 0);
    }

    // ---- null-safe JSON helpers
    private static boolean present(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull();
    }

    private static JsonObject obj(JsonObject o, String k) {
        return present(o, k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static String str(JsonObject o, String k) {
        return present(o, k) ? o.get(k).getAsString() : "";
    }

    private static long lng(JsonObject o, String k) {
        return present(o, k) ? o.get(k).getAsLong() : 0L;
    }

    private static boolean bool(JsonObject o, String k) {
        return present(o, k) && o.get(k).getAsBoolean();
    }
}
