package dev.spotifyhud;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SpotifyHudClient implements ClientModInitializer {
    public static final String MOD_ID = "spotifyhud";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static final int GREEN = 0xFF1DB954;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int RED = 0xFFFF5555;
    private static final int PANEL_BG = 0xA0000000;
    private static final int BAR_BG = 0xFF404040;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SpotifyHud-IO");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean polling = new AtomicBoolean(false);

    private Path configFile;
    private Path tokenFile;
    private volatile SpotifyConfig config = new SpotifyConfig();
    private volatile SpotifyAuth auth;
    private volatile SpotifyApi api;

    private volatile PlaybackState state = PlaybackState.INACTIVE;
    private volatile long nextPollAt = 0;
    private volatile long lastCommandAt = 0;
    private volatile String status = "";
    private volatile long statusUntil = 0;

    private KeyMapping loginKey, playPauseKey, nextKey, prevKey, volUpKey, volDownKey, seekFwdKey, seekBackKey, hudKey;

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------ setup

    @Override
    public void onInitializeClient() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        configFile = dir.resolve("spotifyhud.json");
        tokenFile = dir.resolve("spotifyhud-token.json");
        reload();

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "keys"));
        loginKey = key("login", 51 /* SEMICOLON */, cat);
        playPauseKey = key("play_pause", 49 /* BACKSLASH */, cat);
        nextKey = key("next", 48 /* RIGHT_BRACKET */, cat);
        prevKey = key("previous", 47 /* LEFT_BRACKET */, cat);
        volUpKey = key("volume_up", 46 /* EQUAL */, cat);
        volDownKey = key("volume_down", 45 /* MINUS */, cat);
        seekFwdKey = key("seek_forward", 55 /* PERIOD */, cat);
        seekBackKey = key("seek_back", 54 /* COMMA */, cat);
        hudKey = key("toggle_hud", 52 /* APOSTROPHE */, cat);

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "now_playing"),
                this::renderHud);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("spotify")
                        .then(ClientCommands.literal("login").executes(c -> {
                            startLogin();
                            return 1;
                        }))
                        .then(ClientCommands.literal("reload").executes(c -> {
                            reload();
                            setStatus("Config reloaded", 3000);
                            return 1;
                        }))
                        .then(ClientCommands.literal("pause").executes(c -> {
                            togglePlay();
                            return 1;
                        }))
                        .then(ClientCommands.literal("next").executes(c -> {
                            skip(true);
                            return 1;
                        }))
                        .then(ClientCommands.literal("previous").executes(c -> {
                            skip(false);
                            return 1;
                        }))
                        .then(ClientCommands.literal("volume")
                                .then(ClientCommands.argument("percent", IntegerArgumentType.integer(0, 100))
                                        .executes(c -> {
                                            setVolume(IntegerArgumentType.getInteger(c, "percent"));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("seek")
                                .then(ClientCommands.argument("seconds", IntegerArgumentType.integer(0))
                                        .executes(c -> {
                                            seekTo(IntegerArgumentType.getInteger(c, "seconds") * 1000L);
                                            return 1;
                                        })))));
    }

    private static KeyMapping key(String id, int scancode, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + "." + id, InputConstants.Type.KEYBOARD, scancode, category));
    }

    private void reload() {
        SpotifyConfig cfg = SpotifyConfig.load(configFile);
        SpotifyAuth newAuth = new SpotifyAuth(cfg, tokenFile, http);
        config = cfg;
        auth = newAuth;
        api = new SpotifyApi(newAuth, http);
        state = PlaybackState.INACTIVE;
        nextPollAt = 0;
    }

    // ------------------------------------------------------------------ tick / polling

    private void tick(Minecraft mc) {
        while (loginKey.consumeClick()) startLogin();
        while (hudKey.consumeClick()) config.showHud = !config.showHud; // session-only; edit the config to persist
        while (playPauseKey.consumeClick()) togglePlay();
        while (nextKey.consumeClick()) skip(true);
        while (prevKey.consumeClick()) skip(false);
        while (volUpKey.consumeClick()) changeVolume(config.volumeStep);
        while (volDownKey.consumeClick()) changeVolume(-config.volumeStep);
        while (seekFwdKey.consumeClick()) seekBy(config.seekStepSeconds * 1000L);
        while (seekBackKey.consumeClick()) seekBy(-config.seekStepSeconds * 1000L);

        poll(mc);
    }

    private void poll(Minecraft mc) {
        if (mc.player == null || !auth.hasLogin()) return;
        long now = System.currentTimeMillis();
        if (now < nextPollAt || !polling.compareAndSet(false, true)) return;
        nextPollAt = now + config.pollIntervalMs;
        SpotifyApi a = api;
        io.execute(() -> {
            try {
                PlaybackState s = a.getPlayback();
                // Right after a volume/seek command Spotify may still report the old value; skip that update.
                if (System.currentTimeMillis() - lastCommandAt > 1500) state = s;
            } catch (SpotifyException e) {
                if (e.retryAfterSeconds > 0) nextPollAt = System.currentTimeMillis() + e.retryAfterSeconds * 1000L;
                setStatus(e.getMessage(), 4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                setStatus("Spotify: " + describe(e), 4000);
            } finally {
                polling.set(false);
            }
        });
    }

    // ------------------------------------------------------------------ actions

    private void startLogin() {
        auth.startLogin(msg -> setStatus(msg, 8000));
    }

    private boolean requireActive() {
        if (!auth.hasLogin()) {
            setStatus("Not logged in - press the Spotify login key", 3000);
            return false;
        }
        if (!state.active()) {
            setStatus("Nothing playing - start Spotify on a device", 3000);
            return false;
        }
        return true;
    }

    private void protectFromPoll() {
        long now = System.currentTimeMillis();
        lastCommandAt = now;
        nextPollAt = now + 1600;
    }

    private void togglePlay() {
        if (!requireActive()) return;
        PlaybackState s = state;
        boolean willPlay = !s.playing();
        state = s.withPlaying(willPlay);
        protectFromPoll();
        SpotifyApi a = api;
        run(() -> {
            if (willPlay) a.play();
            else a.pause();
        });
    }

    private void skip(boolean forward) {
        if (!requireActive()) return;
        SpotifyApi a = api;
        run(() -> {
            if (forward) a.next();
            else a.previous();
            nextPollAt = System.currentTimeMillis() + 500; // pick up the new track quickly
        });
    }

    private void changeVolume(int delta) {
        if (!requireActive()) return;
        setVolume(state.volumePercent() + delta);
    }

    private void setVolume(int percent) {
        if (!requireActive()) return;
        int target = Math.max(0, Math.min(100, percent));
        state = state.withVolume(target);
        protectFromPoll();
        SpotifyApi a = api;
        run(() -> a.setVolume(target));
    }

    private void seekBy(long deltaMs) {
        if (!requireActive()) return;
        seekTo(state.liveProgressMs() + deltaMs);
    }

    private void seekTo(long targetMs) {
        if (!requireActive()) return;
        PlaybackState s = state;
        long max = s.durationMs() > 0 ? s.durationMs() - 500 : Long.MAX_VALUE;
        long target = Math.max(0, Math.min(targetMs, max));
        state = s.withProgress(target);
        protectFromPoll();
        SpotifyApi a = api;
        run(() -> a.seek(target));
    }

    private void run(Action action) {
        io.execute(() -> {
            try {
                action.run();
            } catch (SpotifyException e) {
                lastCommandAt = 0; // undo optimistic UI on the next poll
                nextPollAt = System.currentTimeMillis() + 300;
                if (e.retryAfterSeconds > 0) nextPollAt = System.currentTimeMillis() + e.retryAfterSeconds * 1000L;
                setStatus(e.getMessage(), 4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                lastCommandAt = 0;
                nextPollAt = System.currentTimeMillis() + 300;
                setStatus("Spotify: " + describe(e), 4000);
            }
        });
    }

    private void setStatus(String message, long durationMs) {
        status = message == null ? "" : message;
        statusUntil = System.currentTimeMillis() + durationMs;
    }

    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------ HUD

    private void renderHud(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        SpotifyConfig cfg = config;
        if (!cfg.showHud) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int x = cfg.hudX;
        int y = cfg.hudY;
        int w = cfg.hudWidth;
        int lineH = 11;

        int bottom;
        if (cfg.clientId.isBlank()) {
            bottom = messagePanel(g, font, x, y, w, "Spotify HUD", "Set clientId in", "config/spotifyhud.json",
                    "then run /spotify reload");
        } else if (!auth.hasLogin()) {
            bottom = auth.isLoginInProgress()
                    ? messagePanel(g, font, x, y, w, "Spotify HUD", "Waiting for browser login...")
                    : messagePanel(g, font, x, y, w, "Spotify HUD", "Press the Spotify login key",
                            "(default ;) or /spotify login");
        } else {
            PlaybackState s = state;
            if (!s.active()) {
                bottom = messagePanel(g, font, x, y, w, "Nothing playing", "Start Spotify on any device");
            } else {
                int h = 48;
                g.fill(x, y, x + w, y + h, PANEL_BG);
                g.fill(x, y, x + 2, y + h, GREEN);

                int pad = 8;
                int inner = w - pad - 6;
                g.text(font, fit(font, s.title().isEmpty() ? "Unknown" : s.title(), inner), x + pad, y + 5, WHITE, true);
                g.text(font, fit(font, s.artist(), inner), x + pad, y + 5 + lineH, GRAY, false);

                // progress bar
                int barX = x + pad;
                int barW = w - pad - 6;
                int barY = y + 29;
                long progress = s.liveProgressMs();
                double frac = s.durationMs() > 0 ? Math.min(1.0, (double) progress / s.durationMs()) : 0.0;
                g.fill(barX, barY, barX + barW, barY + 3, BAR_BG);
                g.fill(barX, barY, barX + (int) (barW * frac), barY + 3, GREEN);

                // time + volume row
                int rowY = y + 35;
                String left = fmt(progress);
                String right = fmt(s.durationMs());
                g.text(font, left, barX, rowY, GRAY, false);
                g.text(font, right, barX + barW - font.width(right), rowY, GRAY, false);
                String mid = (s.playing() ? "" : "Paused  ") + (s.volumeSupported() ? "Vol " + s.volumePercent() + "%" : "");
                if (!mid.isEmpty()) {
                    g.text(font, mid, barX + (barW - font.width(mid)) / 2, rowY, s.playing() ? GRAY : WHITE, false);
                }
                bottom = y + h;
            }
        }

        if (System.currentTimeMillis() < statusUntil && !status.isEmpty()) {
            g.text(font, fit(font, status, w), x, bottom + 3, RED, true);
        }
    }

    /** Draws a small text-only panel and returns its bottom y. */
    private int messagePanel(GuiGraphicsExtractor g, Font font, int x, int y, int w, String... lines) {
        int h = 8 + lines.length * 11;
        g.fill(x, y, x + w, y + h, PANEL_BG);
        g.fill(x, y, x + 2, y + h, GREEN);
        for (int i = 0; i < lines.length; i++) {
            g.text(font, fit(font, lines[i], w - 14), x + 8, y + 5 + i * 11, i == 0 ? WHITE : GRAY, i == 0);
        }
        return y + h;
    }

    private static String fit(Font font, String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        String ellipsis = "...";
        int end = s.length();
        while (end > 0 && font.width(s.substring(0, end) + ellipsis) > maxWidth) end--;
        return s.substring(0, end) + ellipsis;
    }

    private static String fmt(long ms) {
        long total = Math.max(0, ms) / 1000;
        return (total / 60) + ":" + String.format("%02d", total % 60);
    }
}
