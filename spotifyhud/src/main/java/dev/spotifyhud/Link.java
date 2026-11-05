package dev.spotifyhud;

/** One beat link: a music signal driving one setting (like a node wire in a visual patcher). */
final class Link {
    /** Name of the SpotifyConfig field being driven (see {@link Mod}). */
    public String param = "";
    /** 0 none, 1 bass, 2 mid, 3 treble, 4 level, 5 beat, 6 alternate. */
    public int src = 1;
    /** -100 .. 100 (% of the setting's range). */
    public int amount = 50;
    /** 0 = snappy .. 100 = slow fall-off. */
    public int smooth = 40;
    /** Ignore signal below this level (0 .. 90 %). */
    public int gate = 0;
    /** 0 linear, 1 punchy, 2 soft. */
    public int curve = 0;
    /** Colour settings only: see {@link Mod#COLOR_MODES}. */
    public int mode = 0;

    transient double val;
    transient long lastNs;
    transient int steps;
    transient boolean armed = true;
}
