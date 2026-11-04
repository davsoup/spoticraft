package dev.spotifyhud;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import net.minecraft.client.gui.screens.Screen;
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

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SpotifyHud-IO");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService bg = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "SpotifyHud-BG");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean polling = new AtomicBoolean(false);

    private Path configFile;
    private Path tokenFile;
    static volatile SpotifyHudClient instance;
    volatile SpotifyConfig config = new SpotifyConfig();
    private volatile SpotifyAuth auth;
    private volatile SpotifyApi api;

    volatile PlaybackState state = PlaybackState.INACTIVE;
    volatile boolean panelFull = false;
    volatile int panelBottom = 0;
    volatile int panelW = HudPanel.BLOCK_W;
    static volatile long forceShowUntil = 0;
    // browser data
    volatile java.util.List<SpotifyApi.Item> queueItems = java.util.List.of();
    volatile java.util.List<SpotifyApi.Item> playlistItems = java.util.List.of();
    volatile java.util.List<SpotifyApi.Item> likedItems = java.util.List.of();
    volatile java.util.List<SpotifyApi.Item> searchItems = java.util.List.of();
    volatile boolean browserLoading = false;
    volatile String browserNote = "";
    volatile boolean needsScopes = false;
    private volatile Screen pendingScreen;

    // track-change slide transition
    private volatile PlaybackState lastFull;
    private volatile PlaybackState prevState;
    private volatile AlbumArt prevArt;
    private volatile long transStart = 0;
    private static final long TRANSITION_NS = 450_000_000L;

    private final java.util.concurrent.ConcurrentLinkedQueue<AlbumArt> toRelease = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile AlbumArt art;
    private volatile Lyrics lyrics = new Lyrics();
    private String currentTrack = "";
    private volatile long nextPollAt = 0;
    private volatile long lastCommandAt = 0;
    private volatile String status = "";
    private volatile long statusUntil = 0;

    private KeyMapping openKey;

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------ setup

    @Override
    public void onInitializeClient() {
        instance = this;
        Path dir = FabricLoader.getInstance().getConfigDir();
        configFile = dir.resolve("spotifyhud.json");
        tokenFile = dir.resolve("spotifyhud-token.json");
        reload();

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "keys"));
        openKey = key("open_controls", 19 /* P */, cat);

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
                        .then(ClientCommands.literal("search")
                                .then(ClientCommands.argument("query", StringArgumentType.greedyString())
                                        .executes(c -> {
                                            searchAnd(StringArgumentType.getString(c, "query"), 0);
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("queue")
                                .then(ClientCommands.argument("query", StringArgumentType.greedyString())
                                        .executes(c -> {
                                            searchAnd(StringArgumentType.getString(c, "query"), 1);
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("play")
                                .then(ClientCommands.argument("query", StringArgumentType.greedyString())
                                        .executes(c -> {
                                            searchAnd(StringArgumentType.getString(c, "query"), 2);
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("resetpos").executes(c -> {
                            config.hudX = 8;
                            config.hudY = 40;
                            saveConfig();
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
        Screen ps = pendingScreen;
        if (ps != null && mc.player != null) {
            pendingScreen = null;
            mc.gui.setScreen(ps);
        }
        while (openKey.consumeClick()) {
            if (mc.player != null) mc.gui.setScreen(new SpotifyScreen());
        }

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
                trackCheck(s);
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

    boolean needsLogin() {
        return !config.clientId.isBlank() && !auth.hasLogin();
    }

    void toggleLyrics() {
        config.showLyrics = !config.showLyrics;
        saveConfig();
    }

    void toggleHud() {
        config.showHud = !config.showHud;
        saveConfig();
    }

    void startLogin() {
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

    void togglePlay() {
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

    void skip(boolean forward) {
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

    void setVolume(int percent) {
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

    void toggleShuffle() {
        if (!requireActive()) return;
        boolean on = !state.shuffle();
        state = state.withShuffle(on);
        protectFromPoll();
        SpotifyApi a = api;
        run(() -> a.setShuffle(on));
    }

    void cycleRepeat() {
        if (!requireActive()) return;
        int mode = (state.repeat() + 1) % 3;
        state = state.withRepeat(mode);
        protectFromPoll();
        SpotifyApi a = api;
        run(() -> a.setRepeat(mode));
    }

    void seekTo(long targetMs) {
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

    // ------------------------------------------------------------------ art / lyrics / config

    private synchronized void trackCheck(PlaybackState s) {
        String key = s.active() ? s.trackKey() : "";
        if (!key.equals(currentTrack)) {
            boolean hadTrack = !currentTrack.isEmpty();
            currentTrack = key;
            AlbumArt a = s.active() && !s.artUrl().isEmpty() ? new AlbumArt(s.artUrl()) : null;
            if (a != null) a.load(http, bg);
            AlbumArt old = art;
            AlbumArt stale = prevArt;
            if (stale != null) toRelease.add(stale);
            PlaybackState was = lastFull;
            if (hadTrack && s.active() && was != null) {
                prevState = was;
                prevArt = old;
                transStart = System.nanoTime();
            } else {
                prevState = null;
                prevArt = null;
                if (old != null) toRelease.add(old);
            }
            art = a;
            lyrics = new Lyrics();
        }
        if (s.active() && config.showLyrics) lyrics.fetch(http, bg, s);
    }

    // ------------------------------------------------------------------ browser / queue

    void openBrowser(int tab) {
        loadTab(tab, tab == 0);
        pendingScreen = new SpotifyBrowserScreen(tab);
    }

    java.util.List<SpotifyApi.Item> browserItems(int tab) {
        return switch (tab) {
            case 0 -> queueItems;
            case 1 -> playlistItems;
            case 2 -> likedItems;
            default -> searchItems;
        };
    }

    void loadTab(int tab, boolean force) {
        if (tab == 3) return; // search results come from /spotify search
        if (!force && !browserItems(tab).isEmpty()) return;
        if (!auth.hasLogin()) {
            browserNote = "Not logged in";
            return;
        }
        SpotifyApi a = api;
        browserLoading = true;
        browserNote = "";
        io.execute(() -> {
            try {
                java.util.List<SpotifyApi.Item> list = switch (tab) {
                    case 0 -> a.getQueue();
                    case 1 -> a.getPlaylists();
                    default -> a.getLiked();
                };
                if (tab == 0) queueItems = list;
                else if (tab == 1) playlistItems = list;
                else likedItems = list;
            } catch (SpotifyException e) {
                if (e.status == 403 && e.getMessage().contains("permission")) needsScopes = true;
                browserNote = e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                browserNote = "Spotify: " + describe(e);
            } finally {
                browserLoading = false;
            }
        });
    }

    /** mode: 0 = show results, 1 = queue the top result, 2 = play the top result. */
    void searchAnd(String query, int mode) {
        if (!auth.hasLogin()) {
            setStatus("Not logged in - press P, then Log in", 4000);
            return;
        }
        SpotifyApi a = api;
        setStatus("Searching...", 3000);
        io.execute(() -> {
            try {
                java.util.List<SpotifyApi.Item> res = a.searchTracks(query);
                searchItems = res;
                if (res.isEmpty()) {
                    setStatus("No results for \"" + query + "\"", 4000);
                    return;
                }
                SpotifyApi.Item top = res.get(0);
                if (mode == 1) {
                    a.queueTrack(top.uri());
                    setStatus("Queued: " + top.name(), 4000);
                } else if (mode == 2) {
                    a.playTrack(top.uri());
                    nextPollAt = System.currentTimeMillis() + 600;
                    setStatus("Playing: " + top.name(), 4000);
                } else {
                    browserNote = "";
                    pendingScreen = new SpotifyBrowserScreen(3);
                }
            } catch (SpotifyException e) {
                if (e.status == 403 && e.getMessage().contains("permission")) needsScopes = true;
                setStatus(e.getMessage(), 4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                setStatus("Spotify: " + describe(e), 4000);
            }
        });
    }

    void playItem(SpotifyApi.Item it) {
        SpotifyApi a = api;
        setStatus("Playing: " + it.name(), 3000);
        run(() -> {
            if (it.track()) a.playTrack(it.uri());
            else a.playContext(it.uri());
            nextPollAt = System.currentTimeMillis() + 600;
        });
    }

    void queueItem(SpotifyApi.Item it) {
        SpotifyApi a = api;
        setStatus("Queued: " + it.name(), 3000);
        run(() -> {
            a.queueTrack(it.uri());
            queueItems = java.util.List.of(); // reload next time the queue tab opens
        });
    }

    void relogin() {
        auth.logout();
        needsScopes = false;
        startLogin();
    }

    int accentColor() {
        AlbumArt a = art;
        return a != null ? a.accent() : 0xFFFFFFFF;
    }

    void saveConfig() {
        try {
            config.save(configFile);
        } catch (Exception e) {
            LOG.warn("Could not save config", e);
        }
    }

    // ------------------------------------------------------------------ HUD

    private void renderHud(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        if (!config.showHud && System.currentTimeMillis() > forceShowUntil) return;
        if (System.currentTimeMillis() - SpotifyScreen.lastRender < 200) return; // control screen draws it
        drawHud(g, false, -1);
    }

    /** Draws the card (or a setup message), lyrics and status line. Used by the HUD and the control screen. */
    void drawHud(GuiGraphicsExtractor g, boolean interactive, long progOv) {
        SpotifyConfig cfg = config;
        Font font = Minecraft.getInstance().font;
        AlbumArt dead;
        while ((dead = toRelease.poll()) != null) dead.release();
        int x = cfg.hudX;
        int y = cfg.hudY;
        int bottom;
        boolean full = false;
        int w = HudPanel.BLOCK_W;
        PlaybackState s = state;

        if (cfg.clientId.isBlank()) {
            bottom = HudPanel.message(g, font, x, y, "Spotify HUD", "Set clientId in",
                    "config/spotifyhud.json", "then run /spotify reload");
        } else if (!auth.hasLogin()) {
            bottom = auth.isLoginInProgress()
                    ? HudPanel.message(g, font, x, y, "Spotify HUD", "Waiting for browser login...")
                    : HudPanel.message(g, font, x, y, "Spotify HUD", "Press P, then click",
                            "Log in to Spotify");
        } else if (!s.active()) {
            bottom = HudPanel.message(g, font, x, y, "Nothing playing", "Start Spotify on any device");
        } else {
            PlaybackState oldS = prevState;
            AlbumArt oldArt = prevArt;
            double p = 1.0;
            if (oldS != null) {
                p = (System.nanoTime() - transStart) / (double) TRANSITION_NS;
                if (p >= 1.0) {
                    if (oldArt != null) toRelease.add(oldArt);
                    prevState = null;
                    prevArt = null;
                    oldS = null;
                    oldArt = null;
                }
            }
            HudPanel.render(g, font, cfg, s, art, x, y, interactive, progOv, oldS, oldArt, p);
            lastFull = s;
            int[] R = HudPanel.cardRect(cfg, x, y);
            bottom = y + R[3];
            w = R[2];
            full = true;
        }
        panelFull = full;
        panelBottom = bottom;
        panelW = w;

        int below = bottom + (interactive ? 94 : 3);
        boolean hasStatus = System.currentTimeMillis() < statusUntil && !status.isEmpty();
        if (hasStatus) {
            HudPanel.status(g, font, status, x, below);
            below += 11;
        }

        if (full && cfg.showLyrics && lyrics.status == Lyrics.READY) {
            long p = progOv >= 0 ? progOv : s.liveProgressMs();
            Lyrics.Snap sn = lyrics.at(p);
            String cur = sn.cur();
            if (!cur.isEmpty() && sn.sinceCur() > 10000 && sn.untilNext() > 3000) cur = ""; // long instrumental gap
            String next = sn.next();
            boolean showNext = !next.isEmpty() && (cur.isEmpty() ? sn.untilNext() <= 2500 : sn.untilNext() <= 7000);
            if (!showNext) next = "";
            if (!cur.isEmpty() || !next.isEmpty()) {
                int above = y - 29 - cfg.lyricsOffset;
                int ly = above >= 0 ? above : below + Math.max(0, cfg.lyricsOffset);
                HudPanel.lyrics(g, font, cur, next, x, ly, w, cfg.alignLyrics);
            }
        }
    }
}
