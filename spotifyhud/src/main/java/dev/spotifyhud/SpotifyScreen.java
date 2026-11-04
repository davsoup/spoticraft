package dev.spotifyhud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Control screen (key P). The cursor is free here: drag the card to move it, click/drag the bars to
 * seek or set volume, use the buttons for everything else.
 */
public final class SpotifyScreen extends Screen {
    /** Last time this screen drew; the normal HUD hides itself while this is fresh. */
    static volatile long lastRender = 0;

    private static final int NONE = 0, MOVE = 1, SEEK = 2, VOL = 3;
    private static final int BTN_W = 36, BTN_H = 14, BTN_GAP = 6;

    private int mode = NONE;
    private int offX, offY;
    private double seekFrac;
    private int volPct = -1;
    private long lastVolSend = 0;

    public SpotifyScreen() {
        super(Component.literal("Spotify"));
    }

    // row i below the card: 0 volume/login, 1 playback, 2 lyrics+hud, 3 style+customize
    private static int rowY(SpotifyHudClient c, int i) {
        return c.panelBottom + 4 + 18 * i;
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int buttonX(int panelX, int w, int i) {
        int total = 3 * BTN_W + 2 * BTN_GAP;
        return panelX + (w - total) / 2 + i * (BTN_W + BTN_GAP);
    }

    private static int halfW(int w) {
        return (w - BTN_GAP) / 2;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        lastRender = System.currentTimeMillis();
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c == null) return;
        Font font = Minecraft.getInstance().font;
        g.fill(0, 0, width, height, 0x55000000);

        PlaybackState s = c.state;
        long progOv = mode == SEEK ? (long) (seekFrac * s.durationMs()) : -1;
        c.drawHud(g, true, progOv);

        SpotifyConfig cfg = c.config;
        int x = cfg.hudX;
        int w = c.panelW;

        if (c.panelFull) {
            if (s.volumeSupported()) {
                HudPanel.volumeRow(g, font, x, rowY(c, 0), w, mode == VOL ? volPct : s.volumePercent());
            }
            int by = rowY(c, 1);
            String[] labels = {"|<", s.playing() ? "||" : ">", ">|"};
            for (int i = 0; i < 3; i++) {
                int bx = buttonX(x, w, i);
                HudPanel.button(g, font, labels[i], bx, by, BTN_W, BTN_H, inRect(mouseX, mouseY, bx, by, BTN_W, BTN_H));
            }
        }
        if (c.needsLogin()) {
            int by = rowY(c, 0);
            HudPanel.button(g, font, "Log in to Spotify", x, by, w, BTN_H, inRect(mouseX, mouseY, x, by, w, BTN_H));
        }
        int hw = halfW(w);
        int r2 = rowY(c, 2);
        int r3 = rowY(c, 3);
        HudPanel.button(g, font, "Lyrics: " + (cfg.showLyrics ? "On" : "Off"), x, r2, hw, BTN_H, inRect(mouseX, mouseY, x, r2, hw, BTN_H));
        HudPanel.button(g, font, "HUD: " + (cfg.showHud ? "On" : "Off"), x + hw + BTN_GAP, r2, hw, BTN_H,
                inRect(mouseX, mouseY, x + hw + BTN_GAP, r2, hw, BTN_H));
        HudPanel.button(g, font, "Style: " + (cfg.style == 0 ? "Block" : "CD"), x, r3, hw, BTN_H, inRect(mouseX, mouseY, x, r3, hw, BTN_H));
        HudPanel.button(g, font, "Customize", x + hw + BTN_GAP, r3, hw, BTN_H,
                inRect(mouseX, mouseY, x + hw + BTN_GAP, r3, hw, BTN_H));

        String hint = "Drag the card to move it  |  click or drag the bars  |  Esc to close";
        g.text(font, hint, (width - font.width(hint)) / 2, height - 14, 0xFFCCCCCC, true);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c == null) return super.mouseClicked(event, doubleClick);
        double mx = event.x();
        double my = event.y();
        SpotifyConfig cfg = c.config;
        int x = cfg.hudX;
        int y = cfg.hudY;
        int w = c.panelW;
        PlaybackState s = c.state;

        if (c.panelFull) {
            HudPanel.Layout L = HudPanel.layout(cfg);
            if (HudPanel.inBar(L, mx, my, x, y)) {
                mode = SEEK;
                seekFrac = HudPanel.barFrac(L, mx, x);
                return true;
            }
            if (s.volumeSupported() && HudPanel.inVolume(mx, my, x, rowY(c, 0), w)) {
                mode = VOL;
                volPct = (int) Math.round(HudPanel.volFrac(mx, x, w) * 100);
                sendVolume(c, false);
                return true;
            }
            int by = rowY(c, 1);
            if (my >= by && my < by + BTN_H) {
                for (int i = 0; i < 3; i++) {
                    int bx = buttonX(x, w, i);
                    if (mx >= bx && mx < bx + BTN_W) {
                        if (i == 0) c.skip(false);
                        else if (i == 1) c.togglePlay();
                        else c.skip(true);
                        return true;
                    }
                }
            }
        }
        if (c.needsLogin() && inRect(mx, my, x, rowY(c, 0), w, BTN_H)) {
            c.startLogin();
            return true;
        }
        int hw = halfW(w);
        int r2 = rowY(c, 2);
        int r3 = rowY(c, 3);
        if (inRect(mx, my, x, r2, hw, BTN_H)) {
            c.toggleLyrics();
            return true;
        }
        if (inRect(mx, my, x + hw + BTN_GAP, r2, hw, BTN_H)) {
            c.toggleHud();
            return true;
        }
        if (inRect(mx, my, x, r3, hw, BTN_H)) {
            cfg.style = cfg.style == 0 ? 1 : 0;
            c.saveConfig();
            return true;
        }
        if (inRect(mx, my, x + hw + BTN_GAP, r3, hw, BTN_H)) {
            c.saveConfig();
            Minecraft.getInstance().gui.setScreen(new SpotifyCustomizeScreen());
            return true;
        }
        int ph = c.panelBottom - y;
        if (inRect(mx, my, x, y, w, ph)) {
            mode = MOVE;
            offX = (int) mx - x;
            offY = (int) my - y;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c == null || mode == NONE) return super.mouseDragged(event, dragX, dragY);
        double mx = event.x();
        SpotifyConfig cfg = c.config;
        int x = cfg.hudX;
        int w = c.panelW;
        if (mode == MOVE) {
            int ph = c.panelBottom - cfg.hudY;
            cfg.hudX = clamp((int) event.x() - offX, 0, Math.max(0, width - w));
            cfg.hudY = clamp((int) event.y() - offY, 0, Math.max(0, height - ph - 80));
        } else if (mode == SEEK) {
            seekFrac = HudPanel.barFrac(HudPanel.layout(cfg), mx, x);
        } else if (mode == VOL) {
            volPct = (int) Math.round(HudPanel.volFrac(mx, x, w) * 100);
            sendVolume(c, false);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c != null && mode != NONE) {
            if (mode == MOVE) {
                c.saveConfig();
            } else if (mode == SEEK) {
                c.seekTo((long) (seekFrac * c.state.durationMs()));
            } else if (mode == VOL) {
                sendVolume(c, true);
            }
            mode = NONE;
            volPct = -1;
            return true;
        }
        mode = NONE;
        return super.mouseReleased(event);
    }

    private void sendVolume(SpotifyHudClient c, boolean force) {
        long now = System.currentTimeMillis();
        if (force || now - lastVolSend > 250) {
            lastVolSend = now;
            c.setVolume(volPct);
        }
    }

    @Override
    public void onClose() {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c != null) c.saveConfig();
        lastRender = 0;
        super.onClose();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
