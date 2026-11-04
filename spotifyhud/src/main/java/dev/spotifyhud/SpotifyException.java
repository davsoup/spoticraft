package dev.spotifyhud;

/** Error from Spotify (or from the login flow) with a message that is safe to show on the HUD. */
public class SpotifyException extends Exception {
    public final int status;
    public final long retryAfterSeconds;

    public SpotifyException(int status, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
