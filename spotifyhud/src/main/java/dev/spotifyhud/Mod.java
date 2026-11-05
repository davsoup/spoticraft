package dev.spotifyhud;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The modulation engine: every customization setting is registered here as a target, and any music signal
 * (bass, mid, treble, level, beat, alternate) can be wired to it with an amount, smoothing, gate and curve.
 * The HUD draws from a modulated copy of the config, so saved settings are never changed.
 */
final class Mod {
    enum K {INT, FLOAT, BOOL, ENUM, COLOR}

    /** @param range how far a 100 % link moves the value; lo/hi clamp the result. */
    record Def(String id, String label, K kind, double lo, double hi, double range, int n) {
    }

    static final String[] SOURCES = {"None", "Bass", "Mid", "Treble", "Level", "Beat", "Alternate"};
    static final String[] CURVES = {"Linear", "Punchy", "Soft"};
    static final String[] COLOR_MODES = {"Hue shift", "Brightness", "Cycle colors", "Cycle album", "Flash white", "Flash album"};
    private static final int[] CYCLE = {0xFF4D4D, 0xFF9F43, 0xFFE066, 0x5EE08A, 0x4DE1E8, 0x5B8CFF, 0xA66BFF, 0xFF6BD6, 0xFFFFFF};

    static final List<Def> DEFS = new ArrayList<>();
    private static final Map<String, Def> BY_ID = new HashMap<>();
    private static final Map<String, Field> FIELDS = new HashMap<>();

    private static void num(String id, String label, double lo, double hi) {
        add(new Def(id, label, K.INT, lo, hi, hi - lo, 0));
    }

    private static void flt(String id, String label, double lo, double hi) {
        add(new Def(id, label, K.FLOAT, lo, hi, hi - lo, 0));
    }

    private static void pos(String id, String label) {
        add(new Def(id, label, K.INT, -400, 4000, 100, 0));
    }

    private static void bool(String id, String label) {
        add(new Def(id, label, K.BOOL, 0, 1, 1, 0));
    }

    private static void en(String id, String label, int n) {
        add(new Def(id, label, K.ENUM, 0, n - 1, n, n));
    }

    private static void col(String id, String label) {
        add(new Def(id, label, K.COLOR, 0, 0, 1, 0));
    }

    private static void add(Def d) {
        DEFS.add(d);
        BY_ID.put(d.id(), d);
    }

    static {
        // general
        en("style", "Style", 3);
        bool("showHud", "HUD");
        bool("showLyrics", "Lyrics");
        bool("motion", "Motion");
        bool("rounded", "Round corners");
        flt("titleScale", "Title size", 1.0, 2.0);
        bool("autoFit", "Auto-fit text");
        bool("modeIndicators", "Shuffle/repeat badge");
        pos("hudX", "HUD position X");
        pos("hudY", "HUD position Y");
        // block
        en("alignBlock", "Block text align", 3);
        en("bgMode", "Block background", 3);
        num("bgDarkness", "Block darkness", 0, 80);
        num("artOffX", "Cover X", -20, 20);
        num("artOffY", "Cover Y", -20, 20);
        flt("hudScale", "Block size", 0.5, 3.0);
        // cd
        bool("cdText", "CD text");
        en("alignCd", "CD text align", 3);
        en("cdBgMode", "CD text box", 3);
        num("cdDarkness", "CD darkness", 0, 80);
        num("cdSize", "CD size", 40, 220);
        // vinyl
        bool("vinylText", "Vinyl text");
        en("alignVinyl", "Vinyl text align", 3);
        en("vinylBgMode", "Vinyl text box", 3);
        num("vinylDarkness", "Vinyl darkness", 0, 80);
        num("vinylSize", "Record size", 40, 220);
        bool("vinylTint", "Record tint");
        bool("vinylArm", "Tonearm");
        col("vinylArmColor", "Tonearm colour");
        // lyrics
        en("alignLyrics", "Lyrics align", 3);
        num("lyricsScale", "Lyrics size", 70, 200);
        num("lyricsOffset", "Lyrics distance", -30, 120);
        en("lyricsBgMode", "Lyrics background", 4);
        col("lyricsBgColor", "Lyrics bg colour");
        bool("lyricsFlash", "Line flash");
        bool("lyricsKaraoke", "Karaoke fill");
        col("lyricsFillColor", "Lyric fill colour");
        // queue
        bool("queuePeek", "Queue peek");
        num("queuePeekCount", "Queue songs", 1, 5);
        flt("queuePeekScale", "Queue box size", 0.5, 3.0);
        num("queuePeekText", "Queue text size", 70, 200);
        en("queuePeekAlign", "Queue text align", 3);
        bool("queuePeekCovers", "Queue covers");
        en("queuePeekBg", "Queue background", 3);
        num("queuePeekDarkness", "Queue darkness", 0, 80);
        col("queuePeekColor", "Queue text colour");
        en("queuePeekSide", "Queue side", 3);
        pos("queuePeekX", "Queue position X");
        pos("queuePeekY", "Queue position Y");
        // ambient
        bool("ambient", "Ambient glow");
        bool("ambientMograph", "Ambient mograph");
        en("ambientEdges", "Ambient edges", 3);
        num("ambientSize", "Ambient size", 20, 200);
        num("ambientIntensity", "Ambient intensity", 0, 100);
        num("ambientSpeed", "Ambient speed", 1, 10);
        col("ambientColor", "Ambient colour");
        // glow
        bool("glow", "Glow");
        num("glowStrength", "Glow intensity", 0, 100);
        num("glowSpread", "Glow spread", 4, 40);
        num("glowSoftness", "Glow softness", 0, 100);
        col("glowColor", "Glow colour");
        // colours
        col("textColor", "Text colour");
        col("barColor", "Bar colour");
        col("bgColor", "Solid bg colour");
        for (Def d : DEFS) {
            try {
                FIELDS.put(d.id(), SpotifyConfig.class.getField(d.id()));
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException("Mod target without field: " + d.id());
            }
        }
    }

    static Def def(String id) {
        return BY_ID.get(id);
    }

    static Link find(SpotifyConfig cfg, String id) {
        for (Link l : cfg.links) if (id.equals(l.param) && l.src > 0) return l;
        return null;
    }

    /** Returns the config to draw with: the saved one, or a copy with all beat links applied. */
    static SpotifyConfig apply(SpotifyConfig base, AlbumArt art) {
        if (!base.beatEnabled || base.links.isEmpty()) return base;
        SpotifyConfig out = base.copy();
        long now = System.nanoTime();
        for (Link l : base.links) {
            Def d = BY_ID.get(l.param);
            if (d == null || l.src <= 0) continue;
            double v = shape(l, BeatAudio.signal(l.src), now);
            try {
                write(out, d, l, v, art);
            } catch (Throwable ignored) {
                // a bad link must never break rendering
            }
        }
        return out;
    }

    /** Gate, curve and smoothing; keeps the per-link state. Returns 0..1. */
    private static double shape(Link l, double raw, long now) {
        double g = l.gate / 100.0;
        raw = raw < g ? 0 : (g >= 0.999 ? 0 : (raw - g) / (1 - g));
        raw = switch (l.curve) {
            case 1 -> raw * raw;
            case 2 -> Math.sqrt(raw);
            default -> raw;
        };
        double dt = l.lastNs == 0 ? 0.016 : Math.min(0.1, (now - l.lastNs) / 1e9);
        l.lastNs = now;
        double tau = 0.03 + l.smooth / 100.0 * 1.2;
        double k = raw >= l.val ? 1 - Math.exp(-dt / (tau * 0.12)) : 1 - Math.exp(-dt / tau);
        l.val += (raw - l.val) * k;
        return Math.max(0, Math.min(1, l.val));
    }

    private static boolean edge(Link l, double v) {
        if (l.armed && v >= 0.6) {
            l.armed = false;
            return true;
        }
        if (!l.armed && v < 0.3) l.armed = true;
        return false;
    }

    private static void write(SpotifyConfig out, Def d, Link l, double v, AlbumArt art) throws Exception {
        Field f = FIELDS.get(d.id());
        double a = l.amount / 100.0;
        switch (d.kind()) {
            case INT, FLOAT -> {
                double base = ((Number) f.get(out)).doubleValue();
                double val = Math.max(d.lo(), Math.min(d.hi(), base + v * a * d.range()));
                if (d.kind() == K.INT) f.setInt(out, (int) Math.round(val));
                else f.setFloat(out, (float) val);
            }
            case BOOL -> {
                boolean base = f.getBoolean(out);
                boolean trig = v >= 0.5;
                f.setBoolean(out, a >= 0 ? base ^ trig : base ^ !trig);
            }
            case ENUM -> {
                if (edge(l, v)) l.steps += a >= 0 ? 1 : -1;
                int base = f.getInt(out);
                f.setInt(out, Math.floorMod(base + l.steps, d.n()));
            }
            case COLOR -> {
                int cur = f.getInt(out);
                int accent = art != null ? art.accent() & 0xFFFFFF : 0xFFFFFF;
                int base = cur >= 0 ? cur & 0xFFFFFF : accent;
                f.setInt(out, color(l, v, a, base, accent, art));
            }
        }
    }

    private static int color(Link l, double v, double a, int base, int accent, AlbumArt art) {
        float[] hsb = Color.RGBtoHSB((base >> 16) & 0xFF, (base >> 8) & 0xFF, base & 0xFF, null);
        switch (l.mode) {
            case 0 -> {
                float h = (float) (hsb[0] + v * a);
                h -= (float) Math.floor(h);
                return Color.HSBtoRGB(h, hsb[1], hsb[2]) & 0xFFFFFF;
            }
            case 1 -> {
                double mag = Math.abs(a);
                double sc = (1 - mag) + mag * (a >= 0 ? v : 1 - v);
                return Color.HSBtoRGB(hsb[0], hsb[1], (float) (hsb[2] * sc)) & 0xFFFFFF;
            }
            case 2 -> {
                if (edge(l, v)) l.steps += a >= 0 ? 1 : -1;
                return l.steps == 0 ? base : CYCLE[Math.floorMod(l.steps - 1, CYCLE.length)];
            }
            case 3 -> {
                if (edge(l, v)) l.steps += a >= 0 ? 1 : -1;
                int[] pal = art != null ? art.palette() : null;
                if (pal == null || pal.length < 2 || l.steps == 0) return base;
                return pal[Math.floorMod(l.steps - 1, pal.length)] & 0xFFFFFF;
            }
            case 4 -> {
                return mix(base, a >= 0 ? 0xFFFFFF : 0x000000, v * Math.abs(a));
            }
            default -> {
                return mix(base, accent, v * Math.abs(a));
            }
        }
    }

    private static int mix(int a, int b, double f) {
        f = Math.max(0, Math.min(1, f));
        int r = (int) (((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
        int g = (int) (((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
        int bl = (int) ((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return (r << 16) | (g << 8) | bl;
    }
}
