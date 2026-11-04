package dev.spotifyhud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Time-synced lyrics for one track, fetched from the free lrclib.net API. */
final class Lyrics {
    record Line(long ms, String text) {
    }

    static final int IDLE = 0, LOADING = 1, READY = 2, NONE = 3;

    private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]");

    volatile int status = IDLE;
    private volatile List<Line> lines = List.of();

    void fetch(HttpClient http, Executor ex, PlaybackState s) {
        if (status != IDLE) return;
        status = LOADING;
        final String title = s.title();
        final String artist = s.artist().split(",")[0].trim();
        final String album = s.album();
        final long durSec = Math.round(s.durationMs() / 1000.0);
        ex.execute(() -> {
            try {
                String synced = get(http, "https://lrclib.net/api/get?track_name=" + enc(title)
                        + "&artist_name=" + enc(artist)
                        + (album.isEmpty() ? "" : "&album_name=" + enc(album))
                        + (durSec > 0 ? "&duration=" + durSec : ""), false);
                if (synced == null) {
                    synced = get(http, "https://lrclib.net/api/search?track_name=" + enc(title)
                            + "&artist_name=" + enc(artist), true);
                }
                List<Line> parsed = synced == null ? List.of() : parse(synced);
                lines = parsed;
                status = parsed.isEmpty() ? NONE : READY;
            } catch (Throwable t) {
                status = NONE;
            }
        });
    }

    /** Returns {current, next} lines for the given playback position (either may be empty). */
    String[] around(long ms) {
        List<Line> l = lines;
        int idx = -1;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).ms() <= ms) idx = i;
            else break;
        }
        String cur = idx >= 0 ? l.get(idx).text() : "";
        String next = idx + 1 < l.size() ? l.get(idx + 1).text() : "";
        return new String[]{cur, next};
    }

    private static String get(HttpClient http, String url, boolean search) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "SpotifyHud Minecraft mod")
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return null;
        JsonElement root = JsonParser.parseString(res.body());
        if (!search) return syncedOf(root);
        if (!root.isJsonArray()) return null;
        JsonArray a = root.getAsJsonArray();
        for (JsonElement e : a) {
            String s = syncedOf(e);
            if (s != null) return s;
        }
        return null;
    }

    private static String syncedOf(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        if (!o.has("syncedLyrics") || o.get("syncedLyrics").isJsonNull()) return null;
        String s = o.get("syncedLyrics").getAsString();
        return s.isBlank() ? null : s;
    }

    static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher m = STAMP.matcher(raw);
            List<Long> stamps = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long frac = 0;
                String f = m.group(3);
                if (f != null) {
                    frac = Long.parseLong(f);
                    if (f.length() == 1) frac *= 100;
                    else if (f.length() == 2) frac *= 10;
                }
                stamps.add(min * 60000 + sec * 1000 + frac);
                end = m.end();
            }
            if (stamps.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long t : stamps) out.add(new Line(t, text));
        }
        out.sort((a, b) -> Long.compare(a.ms(), b.ms()));
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
