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
    public boolean showLyrics = true;
    /** 0 = block card, 1 = spinning CD. */
    public int style = 0;
    /** Text alignment per style: 0 left, 1 center, 2 right. */
    public int alignBlock = 0;
    public int alignCd = 1;
    /** Title text size multiplier (1.0 - 2.0). */
    public float titleScale = 1.5f;
    /** Background: 0 = colour spill from the cover, 1 = solid colour, 2 = none. */
    public int bgMode = 0;
    /** Percent of black laid over the background (0 - 80). */
    public int bgDarkness = 35;
    /** Slow background breathing and CD spin. */
    public boolean motion = true;
    /** Solid background colour (RGB), or -1 to use the cover's colour. */
    public int bgColor = -1;
    public boolean glow = true;
    /** Overall size of the block card (0.5 - 3.0). Drag a corner on the control screen. */
    public float hudScale = 1.0f;
    /** Diameter of the CD in GUI pixels (40 - 220). Drag a corner on the control screen. */
    public int cdSize = 110;
    public int alignLyrics = 0;
    /** Extra distance between the lyrics and the card (-30 .. 120). */
    public int lyricsOffset = 0;
    public int glowSpread = 14;
    public int glowSoftness = 60;
    public int glowStrength = 60;
    /** RGB colours, or -1 to follow the album cover. */
    public int glowColor = -1;
    public int textColor = 0xFFFFFF;
    public int barColor = 0xFFFFFF;
    public int hudX = 8;
    public int hudY = 40;

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
            cfg.style = Math.max(0, Math.min(1, cfg.style));
            cfg.alignBlock = Math.max(0, Math.min(2, cfg.alignBlock));
            cfg.alignCd = Math.max(0, Math.min(2, cfg.alignCd));
            cfg.titleScale = Math.max(1.0f, Math.min(2.0f, cfg.titleScale));
            cfg.bgMode = Math.max(0, Math.min(2, cfg.bgMode));
            cfg.bgDarkness = Math.max(0, Math.min(80, cfg.bgDarkness));
            cfg.glowStrength = Math.max(0, Math.min(100, cfg.glowStrength));
            cfg.hudScale = Math.max(0.5f, Math.min(3.0f, cfg.hudScale));
            cfg.cdSize = Math.max(40, Math.min(220, cfg.cdSize));
            cfg.alignLyrics = Math.max(0, Math.min(2, cfg.alignLyrics));
            cfg.lyricsOffset = Math.max(-30, Math.min(120, cfg.lyricsOffset));
            cfg.glowSpread = Math.max(4, Math.min(40, cfg.glowSpread));
            cfg.glowSoftness = Math.max(0, Math.min(100, cfg.glowSoftness));
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
