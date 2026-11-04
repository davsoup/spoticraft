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
        String album = "";
        String artUrl = "";
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
            JsonObject alb = obj(item, "album");
            if (alb != null) {
                album = str(alb, "name");
                artUrl = pickImage(arr(alb, "images"));
            } else {
                artUrl = pickImage(arr(item, "images")); // podcast episode
                if (artUrl.isEmpty()) {
                    JsonObject show = obj(item, "show");
                    if (show != null) artUrl = pickImage(arr(show, "images"));
                }
            }
        } else if ("ad".equals(str(o, "currently_playing_type"))) {
            title = "Advertisement";
        }

        String rep = str(o, "repeat_state");
        int repeat = "context".equals(rep) ? 1 : "track".equals(rep) ? 2 : 0;
        return new PlaybackState(true, playing, title, artist, album, artUrl, progress, duration,
                volume, volumeSupported, bool(o, "shuffle_state"), repeat, System.currentTimeMillis());
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

    public void setShuffle(boolean on) throws IOException, InterruptedException, SpotifyException {
        command("PUT", "/me/player/shuffle?state=" + on);
    }

    /** mode: 0 off, 1 context, 2 track. */
    public void setRepeat(int mode) throws IOException, InterruptedException, SpotifyException {
        command("PUT", "/me/player/repeat?state=" + (mode == 1 ? "context" : mode == 2 ? "track" : "off"));
    }

    public void setVolume(int percent) throws IOException, InterruptedException, SpotifyException {
        command("PUT", "/me/player/volume?volume_percent=" + Math.max(0, Math.min(100, percent)));
    }

    // ---------------------------------------------------------------- browsing / queue

    /** A track, episode or playlist the browser can list. */
    public record Item(String name, String sub, String uri, boolean track) {
    }

    public java.util.List<Item> getQueue() throws IOException, InterruptedException, SpotifyException {
        JsonObject o = getJson("/me/player/queue");
        java.util.List<Item> out = new java.util.ArrayList<>();
        JsonArray q = o == null ? null : arr(o, "queue");
        if (q != null) for (JsonElement e : q) addTrack(out, e);
        return out;
    }

    public java.util.List<Item> getPlaylists() throws IOException, InterruptedException, SpotifyException {
        JsonObject o = getJson("/me/playlists?limit=50");
        java.util.List<Item> out = new java.util.ArrayList<>();
        JsonArray items = o == null ? null : arr(o, "items");
        if (items != null) {
            for (JsonElement e : items) {
                if (!e.isJsonObject()) continue;
                JsonObject p = e.getAsJsonObject();
                String uri = str(p, "uri");
                if (uri.isEmpty()) continue;
                JsonObject owner = obj(p, "owner");
                out.add(new Item(str(p, "name"), owner == null ? "" : str(owner, "display_name"), uri, false));
            }
        }
        return out;
    }

    public java.util.List<Item> getLiked() throws IOException, InterruptedException, SpotifyException {
        JsonObject o = getJson("/me/tracks?limit=50");
        java.util.List<Item> out = new java.util.ArrayList<>();
        JsonArray items = o == null ? null : arr(o, "items");
        if (items != null) {
            for (JsonElement e : items) {
                if (e.isJsonObject()) addTrack(out, e.getAsJsonObject().get("track"));
            }
        }
        return out;
    }

    public java.util.List<Item> searchTracks(String query) throws IOException, InterruptedException, SpotifyException {
        String q = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        JsonObject o = getJson("/search?type=track&limit=10&q=" + q);
        java.util.List<Item> out = new java.util.ArrayList<>();
        JsonObject tracks = o == null ? null : obj(o, "tracks");
        JsonArray items = tracks == null ? null : arr(tracks, "items");
        if (items != null) for (JsonElement e : items) addTrack(out, e);
        return out;
    }

    /** Starts a playlist/album/artist context. */
    public void playContext(String uri) throws IOException, InterruptedException, SpotifyException {
        JsonObject b = new JsonObject();
        b.addProperty("context_uri", uri);
        commandBody("PUT", "/me/player/play", b.toString());
    }

    public void playTrack(String uri) throws IOException, InterruptedException, SpotifyException {
        JsonObject b = new JsonObject();
        JsonArray uris = new JsonArray();
        uris.add(uri);
        b.add("uris", uris);
        commandBody("PUT", "/me/player/play", b.toString());
    }

    public void queueTrack(String uri) throws IOException, InterruptedException, SpotifyException {
        String u = java.net.URLEncoder.encode(uri, java.nio.charset.StandardCharsets.UTF_8);
        command("POST", "/me/player/queue?uri=" + u);
    }

    private JsonObject getJson(String path) throws IOException, InterruptedException, SpotifyException {
        HttpResponse<String> res = call("GET", path);
        if (res.statusCode() == 204) return null;
        if (res.statusCode() != 200) throw error(res);
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private static void addTrack(java.util.List<Item> out, JsonElement e) {
        if (e == null || !e.isJsonObject()) return;
        JsonObject t = e.getAsJsonObject();
        String uri = str(t, "uri");
        if (uri.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        JsonArray artists = arr(t, "artists");
        if (artists != null) {
            for (JsonElement a : artists) {
                if (!a.isJsonObject()) continue;
                String n = str(a.getAsJsonObject(), "name");
                if (n.isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(n);
            }
        } else {
            JsonObject show = obj(t, "show");
            if (show != null) sb.append(str(show, "name"));
        }
        out.add(new Item(str(t, "name"), sb.toString(), uri, true));
    }

    private void commandBody(String method, String path, String json) throws IOException, InterruptedException, SpotifyException {
        HttpResponse<String> res = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = auth.accessToken(attempt > 0);
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json))
                    .build();
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 401) break;
        }
        int sc = res.statusCode();
        if (sc >= 200 && sc < 300) return;
        throw error(res);
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
        if (sc == 401) return new SpotifyException(sc, "Spotify login rejected - use Log in on the P screen", 0);
        if (sc == 403 && message.toLowerCase().contains("scope")) {
            return new SpotifyException(sc, "Missing permission - use Re-login in Queue & Playlists", 0);
        }
        if (sc == 403) {
            String m = "PREMIUM_REQUIRED".equals(reason) ? "Spotify Premium is required for this control"
                    : message.isEmpty() ? "Spotify refused that action" : message;
            return new SpotifyException(sc, m, 0);
        }
        return new SpotifyException(sc, message.isEmpty() ? "Spotify error " + sc : message, 0);
    }

    private static JsonArray arr(JsonObject o, String k) {
        return present(o, k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : null;
    }

    /** Smallest image that is at least 200px wide, else the largest available. */
    private static String pickImage(JsonArray images) {
        if (images == null) return "";
        String best = "";
        int bestW = Integer.MAX_VALUE;
        String largest = "";
        int largestW = -1;
        for (JsonElement e : images) {
            if (!e.isJsonObject()) continue;
            JsonObject i = e.getAsJsonObject();
            String u = str(i, "url");
            if (u.isEmpty()) continue;
            int w = present(i, "width") ? i.get("width").getAsInt() : 0;
            if (w >= 200 && w < bestW) {
                bestW = w;
                best = u;
            }
            if (w > largestW) {
                largestW = w;
                largest = u;
            }
        }
        return best.isEmpty() ? largest : best;
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
