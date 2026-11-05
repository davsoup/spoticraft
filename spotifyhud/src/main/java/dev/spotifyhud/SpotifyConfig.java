package dev.spotifyhud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/spotifyhud.json. Edit it, then run /spotify reload (or restart). */
public final class SpotifyConfig implements Cloneable {
    /** Client ID of your app from https://developer.spotify.com/dashboard */
    public String clientId = "";
    /** Must match the redirect URI registered on your Spotify app: http://127.0.0.1:<port>/callback */
    public int redirectPort = 8888;
    public int volumeStep = 5;
    public int seekStepSeconds = 10;
    public int pollIntervalMs = 2000;
    public boolean showHud = true;
    public boolean showLyrics = true;
    /** 0 = block card, 1 = spinning CD, 2 = vinyl record. */
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
    /** Shrink title/artist text so it fits instead of scrolling. */
    public boolean autoFit = false;
    /** CD style: show title/artist/progress under the disc. */
    public boolean cdText = true;
    /** Rounded (true) or square (false) card and box corners. */
    public boolean rounded = true;
    /** CD text box: 0 = mograph, 1 = solid, 2 = none. */
    public int cdBgMode = 1;
    /** Small SHF / RPT badges when shuffle or repeat is on. */
    public boolean modeIndicators = true;
    /** Nudge the album cover inside the block card (-20 .. 20). */
    public int artOffX = 0;
    public int artOffY = 0;
    /** Lyrics size in percent (70 - 200). */
    public int lyricsScale = 100;
    /** Lyrics background: 0 = dark, 1 = colour, 2 = mograph, 3 = none. */
    public int lyricsBgMode = 0;
    public int lyricsBgColor = -1;
    /** Overall size of the block card (0.5 - 3.0). Drag a corner on the control screen. */
    public float hudScale = 1.0f;
    /** Diameter of the CD in GUI pixels (40 - 220). Drag a corner on the control screen. */
    public int cdSize = 110;
    public int alignLyrics = 0;
    /** Extra distance between the lyrics and the card (-30 .. 120). */
    public int lyricsOffset = 0;
    /** Glow flash when a new lyric line starts. */
    public boolean lyricsFlash = true;
    public int glowSpread = 14;
    public int glowSoftness = 60;
    public int glowStrength = 60;
    /** RGB colours, or -1 to follow the album cover. */
    public int glowColor = -1;
    public int textColor = 0xFFFFFF;
    public int barColor = 0xFFFFFF;
    public int hudX = 8;
    public int hudY = 40;

    // ---- per-style darkness (CD / vinyl text box)
    public int cdDarkness = 48;
    public int vinylDarkness = 48;

    // ---- vinyl style
    public boolean vinylText = true;
    public int alignVinyl = 1;
    public int vinylBgMode = 1;
    public int vinylSize = 110;
    /** Record body: false = black, true = tinted with the album colour. */
    public boolean vinylTint = false;
    public boolean vinylArm = true;
    /** Tonearm colour (RGB) or -1 for the album accent. */
    public int vinylArmColor = 0xC8C8C8;

    // ---- karaoke lyric fill
    public boolean lyricsKaraoke = true;
    public int lyricsFillColor = -1;

    // ---- queue peek
    public boolean queuePeek = true;
    /** Also show it on the in-game HUD (otherwise only on the control screen). */
    public boolean queuePeekHud = false;
    public int queuePeekCount = 3;
    /** 0 = right of the card, 1 = left, 2 = below. */
    public int queuePeekSide = 0;
    public boolean queuePeekCovers = true;
    /** 0 = mograph, 1 = solid, 2 = none. */
    public int queuePeekBg = 0;
    public int queuePeekDarkness = 35;
    public int queuePeekColor = 0xFFFFFF;
    /** Text size inside the box (70 - 200 %). */
    public int queuePeekText = 100;
    /** Text alignment: 0 left, 1 center, 2 right. */
    public int queuePeekAlign = 0;
    /** Overall size of the box (0.5 - 3.0). Drag a corner on the control screen. */
    public float queuePeekScale = 1.0f;
    /** After dragging it, the box stays at queuePeekX / queuePeekY instead of snapping beside the card. */
    public boolean queuePeekCustom = false;
    public int queuePeekX = 0;
    public int queuePeekY = 0;

    // ---- ambient screen-edge glow
    public boolean ambient = true;
    public int ambientSize = 90;
    public int ambientIntensity = 30;
    public int ambientColor = -1;
    public boolean ambientMograph = true;
    /** 0 = all edges, 1 = left/right, 2 = top/bottom. */
    public int ambientEdges = 0;
    public int ambientSpeed = 4;

    // ---- beat link (audio-reactive modulation)
    public boolean beatEnabled = false;
    /** 0 = only the Spotify desktop app, 1 = all PC audio. */
    public int beatSource = 0;
    public int beatSensitivity = 50;
    public java.util.List<Link> links = new java.util.ArrayList<>();

    /** Shallow copy; the HUD draws from a modulated copy so saved settings are never touched. */
    public SpotifyConfig copy() {
        try {
            return (SpotifyConfig) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    // ---- helpers: settings that depend on the current disc style (1 = CD, 2 = vinyl)
    public int dSize() {
        return style == 2 ? vinylSize : cdSize;
    }

    public void setDSize(int v) {
        if (style == 2) vinylSize = v;
        else cdSize = v;
    }

    public boolean dText() {
        return style == 2 ? vinylText : cdText;
    }

    public int dBox() {
        return style == 2 ? vinylBgMode : cdBgMode;
    }

    public int dDark() {
        return style == 2 ? vinylDarkness : cdDarkness;
    }

    public int textAlign() {
        return style == 2 ? alignVinyl : style == 1 ? alignCd : alignBlock;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static SpotifyConfig load(Path file) {
        SpotifyConfig cfg = new SpotifyConfig();
        try {
            if (Files.exists(file)) {
                SpotifyConfig read = GSON.fromJson(Files.readString(file), SpotifyConfig.class);
                if (read != null) cfg = read;
            }
            if (cfg.clientId == null) cfg.clientId = "";
            if (cfg.links == null) cfg.links = new java.util.ArrayList<>();
            cfg.links.removeIf(l -> l == null || l.param == null);
            cfg.beatSource = cl(cfg.beatSource, 0, 1);
            cfg.beatSensitivity = cl(cfg.beatSensitivity, 0, 100);
            cfg.volumeStep = Math.max(1, cfg.volumeStep);
            cfg.seekStepSeconds = Math.max(1, cfg.seekStepSeconds);
            cfg.pollIntervalMs = Math.max(1000, cfg.pollIntervalMs); // be nice to Spotify's rate limits
            cfg.style = Math.max(0, Math.min(2, cfg.style));
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
            cfg.cdBgMode = Math.max(0, Math.min(2, cfg.cdBgMode));
            cfg.artOffX = Math.max(-20, Math.min(20, cfg.artOffX));
            cfg.artOffY = Math.max(-20, Math.min(20, cfg.artOffY));
            cfg.lyricsScale = Math.max(70, Math.min(200, cfg.lyricsScale));
            cfg.lyricsBgMode = Math.max(0, Math.min(3, cfg.lyricsBgMode));
            cfg.cdDarkness = cl(cfg.cdDarkness, 0, 80);
            cfg.vinylDarkness = cl(cfg.vinylDarkness, 0, 80);
            cfg.alignVinyl = cl(cfg.alignVinyl, 0, 2);
            cfg.vinylBgMode = cl(cfg.vinylBgMode, 0, 2);
            cfg.vinylSize = cl(cfg.vinylSize, 40, 220);
            cfg.queuePeekCount = cl(cfg.queuePeekCount, 1, 5);
            cfg.queuePeekSide = cl(cfg.queuePeekSide, 0, 2);
            cfg.queuePeekBg = cl(cfg.queuePeekBg, 0, 2);
            cfg.queuePeekDarkness = cl(cfg.queuePeekDarkness, 0, 80);
            cfg.queuePeekText = cl(cfg.queuePeekText, 70, 200);
            cfg.queuePeekAlign = cl(cfg.queuePeekAlign, 0, 2);
            cfg.queuePeekScale = Math.max(0.5f, Math.min(3.0f, cfg.queuePeekScale));
            cfg.ambientSize = cl(cfg.ambientSize, 20, 200);
            cfg.ambientIntensity = cl(cfg.ambientIntensity, 0, 100);
            cfg.ambientEdges = cl(cfg.ambientEdges, 0, 2);
            cfg.ambientSpeed = cl(cfg.ambientSpeed, 1, 10);
            cfg.save(file); // writes defaults on first run, and normalises the file
        } catch (IOException | RuntimeException e) {
            // Broken JSON etc: keep defaults, don't crash the game.
        }
        return cfg;
    }

    private static int cl(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this));
    }
}
