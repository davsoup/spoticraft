package dev.spotifyhud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Control screen (key P). The cursor is free here: drag the card to move it, drag a corner to resize it,
 * click/drag the bars to seek or set volume, use the buttons for everything else.
 */
public final class SpotifyScreen extends BaseScreen {
    /** Last time this screen drew; the normal HUD hides itself while this is fresh. */
    static volatile long lastRender = 0;

    private static final int NONE = 0, MOVE = 1, SEEK = 2, VOL = 3, RESIZE = 4;
    private static final int BTN_W = 26, BTN_H = 14, BTN_GAP = 6, HANDLE = 6, PB = 5, PB_GAP = 4;

    private int mode = NONE;
    private int offX, offY;
    private double seekFrac;
    private int volPct = -1;
    private long lastVolSend = 0;
    private int corner, anchorX, anchorY;

    public SpotifyScreen() {
        super(Component.literal("Spotify"));
    }

    // row i below the card: 0 volume/login, 1 playback, 2 lyrics+hud, 3 style+customize, 4 queue
    private static int rowY(SpotifyHudClient c, int i) {
        return c.panelBottom + 4 + 18 * i;
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int buttonX(int panelX, int w, int i) {
        int total = PB * BTN_W + (PB - 1) * PB_GAP;
        return panelX + (w - total) / 2 + i * (BTN_W + PB_GAP);
    }

    private static int halfW(int w) {
        return (w - BTN_GAP) / 2;
    }

    private static int cornerX(int[] r, int i) {
        return i % 2 == 1 ? r[0] + r[2] : r[0];
    }

    private static int cornerY(int[] r, int i) {
        return i >= 2 ? r[1] + r[3] : r[1];
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        lastRender = System.currentTimeMillis();
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c == null) return;
        Font font = Minecraft.getInstance().font;
        g.fill(0, 0, width, height, 0x40000000);

        PlaybackState s = c.state;
        SpotifyConfig cfg = c.config;
        long progOv = mode == SEEK ? (long) (seekFrac * s.durationMs()) : -1;
        c.drawHud(g, true, progOv);

        int x = cfg.hudX;
        int y = cfg.hudY;
        int w = Math.max(c.panelW, 150);

        if (c.panelFull) {
            int[] r = HudPanel.resizeRect(cfg, x, y);
            for (int i = 0; i < 4; i++) HudPanel.handle(g, cornerX(r, i), cornerY(r, i));
            if (s.volumeSupported()) {
                HudPanel.volumeRow(g, font, x, rowY(c, 0), w, mode == VOL ? volPct : s.volumePercent());
            }
            int by = rowY(c, 1);
            String[] labels = {"Shf", "|<", s.playing() ? "||" : ">", ">|", s.repeat() == 2 ? "Rp1" : "Rpt"};
            for (int i = 0; i < PB; i++) {
                int bx = buttonX(x, w, i);
                boolean hover = inRect(mouseX, mouseY, bx, by, BTN_W, BTN_H);
                boolean on = (i == 0 && s.shuffle()) || (i == 4 && s.repeat() != 0);
                if (i == 0 || i == 4) HudPanel.toggleButton(g, font, labels[i], bx, by, BTN_W, BTN_H, hover, on);
                else HudPanel.button(g, font, labels[i], bx, by, BTN_W, BTN_H, hover);
            }
        }
        if (c.needsLogin()) {
            int by = rowY(c, 0);
            HudPanel.button(g, font, "Log in to Spotify", x, by, w, BTN_H, inRect(mouseX, mouseY, x, by, w, BTN_H));
        }
        int hw = halfW(w);
        int r2 = rowY(c, 2);
        int r3 = rowY(c, 3);
        int r4 = rowY(c, 4);
        HudPanel.button(g, font, "Lyrics: " + (cfg.showLyrics ? "On" : "Off"), x, r2, hw, BTN_H, inRect(mouseX, mouseY, x, r2, hw, BTN_H));
        HudPanel.button(g, font, "HUD: " + (cfg.showHud ? "On" : "Off"), x + hw + BTN_GAP, r2, hw, BTN_H,
                inRect(mouseX, mouseY, x + hw + BTN_GAP, r2, hw, BTN_H));
        HudPanel.button(g, font, "Style: " + (cfg.style == 0 ? "Block" : "CD"), x, r3, hw, BTN_H, inRect(mouseX, mouseY, x, r3, hw, BTN_H));
        HudPanel.button(g, font, "Customize", x + hw + BTN_GAP, r3, hw, BTN_H,
                inRect(mouseX, mouseY, x + hw + BTN_GAP, r3, hw, BTN_H));
        HudPanel.button(g, font, "Queue & Playlists", x, r4, w, BTN_H, inRect(mouseX, mouseY, x, r4, w, BTN_H));

        String hint = "Drag the card to move  |  drag a corner to resize  |  click or drag the bars  |  Esc to close";
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
        int w = Math.max(c.panelW, 150);
        PlaybackState s = c.state;

        if (c.panelFull) {
            int[] r = HudPanel.resizeRect(cfg, x, y);
            for (int i = 0; i < 4; i++) {
                if (Math.abs(mx - cornerX(r, i)) <= HANDLE && Math.abs(my - cornerY(r, i)) <= HANDLE) {
                    mode = RESIZE;
                    corner = i;
                    anchorX = i % 2 == 1 ? r[0] : r[0] + r[2];
                    anchorY = i >= 2 ? r[1] : r[1] + r[3];
                    return true;
                }
            }
            if (HudPanel.inBar(cfg, mx, my, x, y)) {
                mode = SEEK;
                seekFrac = HudPanel.barFrac(cfg, mx, x);
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
                for (int i = 0; i < PB; i++) {
                    int bx = buttonX(x, w, i);
                    if (mx >= bx && mx < bx + BTN_W) {
                        if (i == 0) c.toggleShuffle();
                        else if (i == 1) c.skip(false);
                        else if (i == 2) c.togglePlay();
                        else if (i == 3) c.skip(true);
                        else c.cycleRepeat();
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
        int r4 = rowY(c, 4);
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
        if (inRect(mx, my, x, r4, w, BTN_H)) {
            c.saveConfig();
            c.openBrowser(0);
            return true;
        }
        int ph = c.panelBottom - y;
        if (inRect(mx, my, x, y, c.panelW, ph)) {
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
        double my = event.y();
        SpotifyConfig cfg = c.config;
        int x = cfg.hudX;
        int w = Math.max(c.panelW, 150);
        if (mode == MOVE) {
            int ph = c.panelBottom - cfg.hudY;
            cfg.hudX = clamp((int) mx - offX, 0, Math.max(0, width - c.panelW));
            cfg.hudY = clamp((int) my - offY, 0, Math.max(0, height - ph - 100));
        } else if (mode == SEEK) {
            seekFrac = HudPanel.barFrac(cfg, mx, x);
        } else if (mode == VOL) {
            volPct = (int) Math.round(HudPanel.volFrac(mx, x, w) * 100);
            sendVolume(c, false);
        } else if (mode == RESIZE) {
            resize(cfg, mx, my);
        }
        return true;
    }

    private void resize(SpotifyConfig cfg, double mx, double my) {
        boolean right = corner % 2 == 1;
        boolean bottom = corner >= 2;
        if (cfg.style == 1) {
            int d = clamp((int) Math.max(Math.abs(mx - anchorX), Math.abs(my - anchorY)), 40, 220);
            cfg.cdSize = d;
            int w = Math.max(HudPanel.CD_W, d + 8);
            cfg.hudX = right ? anchorX - (w - d) / 2 : anchorX - (w + d) / 2;
            cfg.hudY = bottom ? anchorY - 4 : anchorY - 4 - d;
        } else {
            HudPanel.Layout L = HudPanel.layout(cfg);
            double sc = Math.max(Math.abs(mx - anchorX) / L.w(), Math.abs(my - anchorY) / L.h());
            sc = Math.max(0.5, Math.min(3.0, sc));
            cfg.hudScale = (float) sc;
            int nw = (int) Math.round(L.w() * sc);
            int nh = (int) Math.round(L.h() * sc);
            cfg.hudX = right ? anchorX : anchorX - nw;
            cfg.hudY = bottom ? anchorY : anchorY - nh;
        }
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c != null && mode != NONE) {
            if (mode == MOVE || mode == RESIZE) {
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
