package dev.spotifyhud;

/** Immutable snapshot of what Spotify is doing. No Minecraft dependencies. */
public record PlaybackState(
        boolean active,
        boolean playing,
        String title,
        String artist,
        long progressMs,
        long durationMs,
        int volumePercent,
        boolean volumeSupported,
        long fetchedAtMs) {

    public static final PlaybackState INACTIVE =
            new PlaybackState(false, false, "", "", 0, 0, 0, false, 0);

    /** Progress extrapolated to "now" so the bar moves smoothly between polls. */
    public long liveProgressMs() {
        if (!active) return 0;
        long p = progressMs;
        if (playing) p += System.currentTimeMillis() - fetchedAtMs;
        p = Math.max(0, p);
        return durationMs > 0 ? Math.min(p, durationMs) : p;
    }

    public PlaybackState withVolume(int percent) {
        return new PlaybackState(active, playing, title, artist, progressMs, durationMs,
                percent, volumeSupported, fetchedAtMs);
    }

    public PlaybackState withProgress(long ms) {
        return new PlaybackState(active, playing, title, artist, ms, durationMs,
                volumePercent, volumeSupported, System.currentTimeMillis());
    }

    public PlaybackState withPlaying(boolean isPlaying) {
        return new PlaybackState(active, isPlaying, title, artist, liveProgressMs(), durationMs,
                volumePercent, volumeSupported, System.currentTimeMillis());
    }
}
