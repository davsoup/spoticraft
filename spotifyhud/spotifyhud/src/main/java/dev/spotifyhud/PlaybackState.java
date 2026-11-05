package dev.spotifyhud;

/** Immutable snapshot of what Spotify is doing. No Minecraft dependencies. */
public record PlaybackState(
        boolean active,
        boolean playing,
        String title,
        String artist,
        String album,
        String artUrl,
        long progressMs,
        long durationMs,
        int volumePercent,
        boolean volumeSupported,
        boolean shuffle,
        int repeat,
        long fetchedAtMs) {

    public static final PlaybackState INACTIVE =
            new PlaybackState(false, false, "", "", "", "", 0, 0, 0, false, false, 0, 0);

    /** Identifies a track across polls (used to reload art and lyrics when it changes). */
    public String trackKey() {
        return title + "\u0001" + artist + "\u0001" + durationMs;
    }

    /** Progress extrapolated to "now" so the bar moves smoothly between polls. */
    public long liveProgressMs() {
        if (!active) return 0;
        long p = progressMs;
        if (playing) p += System.currentTimeMillis() - fetchedAtMs;
        p = Math.max(0, p);
        return durationMs > 0 ? Math.min(p, durationMs) : p;
    }

    /** repeat: 0 = off, 1 = playlist/album, 2 = track. */
    public PlaybackState withShuffle(boolean on) {
        return new PlaybackState(active, playing, title, artist, album, artUrl, progressMs, durationMs,
                volumePercent, volumeSupported, on, repeat, fetchedAtMs);
    }

    public PlaybackState withRepeat(int mode) {
        return new PlaybackState(active, playing, title, artist, album, artUrl, progressMs, durationMs,
                volumePercent, volumeSupported, shuffle, mode, fetchedAtMs);
    }

    public PlaybackState withVolume(int percent) {
        return new PlaybackState(active, playing, title, artist, album, artUrl, progressMs, durationMs,
                percent, volumeSupported, shuffle, repeat, fetchedAtMs);
    }

    public PlaybackState withProgress(long ms) {
        return new PlaybackState(active, playing, title, artist, album, artUrl, ms, durationMs,
                volumePercent, volumeSupported, shuffle, repeat, System.currentTimeMillis());
    }

    public PlaybackState withPlaying(boolean isPlaying) {
        return new PlaybackState(active, isPlaying, title, artist, album, artUrl, liveProgressMs(), durationMs,
                volumePercent, volumeSupported, shuffle, repeat, System.currentTimeMillis());
    }
}
