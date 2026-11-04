package dev.spotifyhud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Control screen (default key O). The mouse cursor is free here, so you can drag the HUD around,
 * click/drag the progress bar to seek and the volume bar to set volume.
 */
public final class SpotifyScreen extends Screen {
    /** Last time this screen drew; the normal HUD hides itself while this is fresh. */
    static volatile long lastRender = 0;

    private static final int NONE = 0, MOVE = 1, SEEK = 2, VOL = 3;
    private static final int BTN_W = 36, BTN_H = 14, BTN_GAP = 6, TOG_W = 97;

    private int mode = NONE;
    private int offX, offY;
    private double seekFrac;
    private int volPct = -1;
    private long lastVolSend = 0;

    public SpotifyScreen() {
        super(Component.literal("Spotify"));
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
        int volOv = mode == VOL ? volPct : -1;
        c.drawHud(g, true, progOv, volOv);

        int x = c.config.hudX;
        int y = c.config.hudY;
        int bottom = c.panelBottom;
        if (c.panelFull) {
            int by = bottom + 4;
            String[] labels = {"|<", s.playing() ? "||" : ">", ">|"};
            for (int i = 0; i < 3; i++) {
                int bx = buttonX(x, i);
                boolean hover = mouseX >= bx && mouseX < bx + BTN_W && mouseY >= by && mouseY < by + BTN_H;
                HudPanel.button(g, font, labels[i], bx, by, BTN_W, BTN_H, hover);
            }
        }
        if (c.needsLogin()) {
            int by = bottom + 4;
            boolean hover = inRect(mouseX, mouseY, x, by, HudPanel.W, BTN_H);
            HudPanel.button(g, font, "Log in to Spotify", x, by, HudPanel.W, BTN_H, hover);
        }
        int ty = toggleY(c);
        boolean h1 = inRect(mouseX, mouseY, x, ty, TOG_W, BTN_H);
        boolean h2 = inRect(mouseX, mouseY, x + TOG_W + BTN_GAP, ty, TOG_W, BTN_H);
        HudPanel.button(g, font, "Lyrics: " + (c.config.showLyrics ? "On" : "Off"), x, ty, TOG_W, BTN_H, h1);
        HudPanel.button(g, font, "HUD: " + (c.config.showHud ? "On" : "Off"), x + TOG_W + BTN_GAP, ty, TOG_W, BTN_H, h2);
        String hint = "Drag the card to move it  |  click or drag the bars  |  Esc to close";
        g.text(font, hint, (width - font.width(hint)) / 2, height - 14, 0xFFCCCCCC, true);
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int toggleY(SpotifyHudClient c) {
        return c.panelBottom + (c.panelFull || c.needsLogin() ? 22 : 4);
    }

    private static int buttonX(int panelX, int i) {
        int total = 3 * BTN_W + 2 * BTN_GAP;
        return panelX + (HudPanel.W - total) / 2 + i * (BTN_W + BTN_GAP);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        SpotifyHudClient c = SpotifyHudClient.instance;
        if (c == null) return super.mouseClicked(event, doubleClick);
        double mx = event.x();
        double my = event.y();
        int x = c.config.hudX;
        int y = c.config.hudY;
        PlaybackState s = c.state;

        if (c.panelFull) {
            if (HudPanel.inBar(mx, my, x, y)) {
                mode = SEEK;
                seekFrac = HudPanel.barFrac(mx, x);
                return true;
            }
            if (s.volumeSupported() && HudPanel.inVol(mx, my, x, y)) {
                mode = VOL;
                volPct = (int) Math.round(HudPanel.volFrac(mx, x) * 100);
                sendVolume(c, false);
                return true;
            }
            int by = c.panelBottom + 4;
            if (my >= by && my < by + BTN_H) {
                for (int i = 0; i < 3; i++) {
                    int bx = buttonX(x, i);
                    if (mx >= bx && mx < bx + BTN_W) {
                        if (i == 0) c.skip(false);
                        else if (i == 1) c.togglePlay();
                        else c.skip(true);
                        return true;
                    }
                }
            }
        }
        if (c.needsLogin() && inRect(mx, my, x, c.panelBottom + 4, HudPanel.W, BTN_H)) {
            c.startLogin();
            return true;
        }
        int ty = toggleY(c);
        if (inRect(mx, my, x, ty, TOG_W, BTN_H)) {
            c.toggleLyrics();
            return true;
        }
        if (inRect(mx, my, x + TOG_W + BTN_GAP, ty, TOG_W, BTN_H)) {
            c.toggleHud();
            return true;
        }
        if (mx >= x && mx <= x + HudPanel.W && my >= y && my <= y + HudPanel.H) {
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
        int x = c.config.hudX;
        if (mode == MOVE) {
            c.config.hudX = clamp((int) event.x() - offX, 0, Math.max(0, width - HudPanel.W));
            c.config.hudY = clamp((int) event.y() - offY, 0, Math.max(0, height - HudPanel.H - 44));
        } else if (mode == SEEK) {
            seekFrac = HudPanel.barFrac(mx, x);
        } else if (mode == VOL) {
            volPct = (int) Math.round(HudPanel.volFrac(mx, x) * 100);
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
