package dev.spotifyhud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/spotifyhud.json. Edit it, then run /spotify reload (or restart). */
public final class SpotifyConfig {
    /** Client ID of your app from https://developer.spotify.com/dashboard */
    public String clientId = "";
    /** Must match the redirect URI registered on your Spotify app: http://127.0.0.1:<port>/callback */
    public int redirectPort = 8888;
    public int volumeStep = 5;
    public int seekStepSeconds = 10;
    public int pollIntervalMs = 2000;
    public boolean showHud = true;
    public int hudX = 6;
    public int hudY = 6;
    public int hudWidth = 170;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static SpotifyConfig load(Path file) {
        SpotifyConfig cfg = new SpotifyConfig();
        try {
            if (Files.exists(file)) {
                SpotifyConfig read = GSON.fromJson(Files.readString(file), SpotifyConfig.class);
                if (read != null) cfg = read;
            }
            if (cfg.clientId == null) cfg.clientId = "";
            cfg.volumeStep = Math.max(1, cfg.volumeStep);
            cfg.seekStepSeconds = Math.max(1, cfg.seekStepSeconds);
            cfg.pollIntervalMs = Math.max(1000, cfg.pollIntervalMs); // be nice to Spotify's rate limits
            cfg.hudWidth = Math.max(100, cfg.hudWidth);
            cfg.save(file); // writes defaults on first run, and normalises the file
        } catch (IOException | RuntimeException e) {
            // Broken JSON etc: keep defaults, don't crash the game.
        }
        return cfg;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this));
    }
}
