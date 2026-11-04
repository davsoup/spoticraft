package dev.spotifyhud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Layout, drawing and hit-testing for the now-playing card (block style) and the spinning CD style. */
final class HudPanel {
    static final int BLOCK_W = 224, CD_W = 150, CD_D = 76;
    static final int PAD = 8, ART = 64, GAP = 10, BAR_H = 4;
    static final int GLOW_LAYERS = 8;

    private static final int WHITE = 0xFFFFFFFF;

    // spin + glow texture state (render thread)
    private static double spinAngle = 0;
    private static long lastNs = 0;
    private static Identifier glowId;
    private static int glowKey = Integer.MIN_VALUE;

    private HudPanel() {
    }

    /** Geometry relative to the card's top-left corner. */
    record Layout(int style, int w, int h, int artX, int artY, int artS, int tx, int tw,
                  int titleY, int artistY, int barY, int timeY, double ts, double as,
                  int titleH, int artistH, int textTop) {
        int barX() {
            return tx;
        }

        int barW() {
            return tw;
        }
    }

    static Layout layout(SpotifyConfig cfg) {
        double ts = Math.max(1.0, Math.min(2.0, cfg.titleScale));
        double as = 1.0 + (ts - 1.0) * 0.6;
        int titleH = (int) Math.ceil(9 * ts);
        int artistH = (int) Math.ceil(9 * as);
        int content = titleH + 2 + artistH + 6 + BAR_H + 3 + 9;
        if (cfg.style == 1) {
            int titleY = 4 + CD_D + 8;
            int artistY = titleY + titleH + 2;
            int barY = artistY + artistH + 6;
            return new Layout(1, CD_W, titleY + content + 5, (CD_W - CD_D) / 2, 4, CD_D, 6, CD_W - 12,
                    titleY, artistY, barY, barY + BAR_H + 3, ts, as, titleH, artistH, titleY - 4);
        }
        int h = Math.max(ART + 2 * PAD, content + 2 * PAD);
        int top = (h - content) / 2;
        int artistY = top + titleH + 2;
        int barY = artistY + artistH + 6;
        int tx = PAD + ART + GAP;
        return new Layout(0, BLOCK_W, h, PAD, (h - ART) / 2, ART, tx, BLOCK_W - tx - PAD,
                top, artistY, barY, barY + BAR_H + 3, ts, as, titleH, artistH, 0);
    }

    // ------------------------------------------------------------ main draw

    static Layout draw(GuiGraphicsExtractor g, Font font, SpotifyConfig cfg, PlaybackState s, AlbumArt art,
                       int x, int y, boolean interactive, long progOv) {
        Layout L = layout(cfg);
        if (art != null) art.ensureTextures();

        int accent = art != null ? art.accent() : 0xFFFFFFFF;
        int textRGB = (cfg.textColor < 0 ? accent : cfg.textColor) & 0xFFFFFF;
        int barRGB = (cfg.barColor < 0 ? accent : cfg.barColor) & 0xFFFFFF;
        int glowRGB = (cfg.glowColor < 0 ? accent : cfg.glowColor) & 0xFFFFFF;

        double t = cfg.motion ? System.nanoTime() / 1e9 : 0.0;
        boolean cd = L.style() == 1;

        if (cd) {
            drawCd(g, cfg, s, art, L, x, y, glowRGB);
            int pillTop = y + L.textTop();
            if (cfg.bgMode != 2) {
                round(g, x, pillTop, L.w(), L.h() - L.textTop(), backdropColor(cfg, art, 0xB0));
            }
        } else {
            if (cfg.glow) glowRect(g, x, y, L.w(), L.h(), glowRGB, cfg.glowStrength);
            drawBackground(g, cfg, art, L, x, y, t);
            drawArtSquare(g, font, art, x + L.artX(), y + L.artY(), L.artS());
        }

        int align = cd ? cfg.alignCd : cfg.alignBlock;
        int titleCol = 0xFF000000 | textRGB;
        int artistCol = 0xFF000000 | dim(textRGB, 0.82);
        int timeCol = 0xFF000000 | dim(textRGB, 0.62);

        String title = s.title().isEmpty() ? "Unknown" : s.title();
        aligned(g, font, title, x + L.tx(), L.tw(), y + L.titleY(), titleCol, true, L.ts(), align);
        aligned(g, font, s.artist(), x + L.tx(), L.tw(), y + L.artistY(), artistCol, true, L.as(), align);

        long progress = progOv >= 0 ? progOv : s.liveProgressMs();
        double frac = s.durationMs() > 0 ? Math.min(1.0, (double) progress / s.durationMs()) : 0.0;
        bar(g, x + L.barX(), y + L.barY(), L.barW(), BAR_H, frac, 0xFF000000 | barRGB, interactive);

        int rowY = y + L.timeY();
        String left = fmt(progress);
        String right = fmt(s.durationMs());
        g.text(font, left, x + L.tx(), rowY, timeCol, true);
        g.text(font, right, x + L.tx() + L.tw() - font.width(right), rowY, timeCol, true);
        if (!s.playing()) {
            String p = "Paused";
            g.text(font, p, x + L.tx() + (L.tw() - font.width(p)) / 2, rowY, titleCol, true);
        }
        return L;
    }

    // ------------------------------------------------------------ block style pieces

    private static int backdropColor(SpotifyConfig cfg, AlbumArt art, int alpha) {
        if (cfg.bgMode == 1 && cfg.bgColor >= 0) return (alpha << 24) | (cfg.bgColor & 0xFFFFFF);
        return art != null ? art.darkAverage(alpha) : (alpha << 24) | 0x202020;
    }

    private static void drawBackground(GuiGraphicsExtractor g, SpotifyConfig cfg, AlbumArt art, Layout L,
                                       int x, int y, double t) {
        int w = L.w(), h = L.h();
        if (cfg.bgMode == 2) return;
        if (cfg.bgMode == 1) {
            round(g, x, y, w, h, backdropColor(cfg, art, 0xE6));
            return;
        }
        boolean spilled = false;
        if (art != null && art.spillId != null && Gfx.canTransform(g)) {
            int cx = x + w / 2;
            int cy = y + h / 2;
            int size = (int) (Math.hypot(w, h) * 1.2);
            double dx = 14 * Math.sin(t * 0.35);
            double dy = 8 * Math.sin(t * 0.27 + 1.0);
            double sc = 1.0 + 0.07 * Math.sin(t * 0.5);
            double rot = 0.07 * Math.sin(t * 0.21);
            int[][] rects = {{x + 2, y, x + w - 2, y + h}, {x + 1, y + 1, x + w - 1, y + h - 1}, {x, y + 2, x + w, y + h - 2}};
            // draw each band separately so the corners stay rounded
            int[][] bands = {
                    {x + 2, y, x + w - 2, y + 1}, {x + 1, y + 1, x + w - 1, y + 2},
                    {x, y + 2, x + w, y + h - 2},
                    {x + 1, y + h - 2, x + w - 1, y + h - 1}, {x + 2, y + h - 1, x + w - 2, y + h}};
            for (int[] r : bands) {
                if (!Gfx.scissorOn(g, r[0], r[1], r[2], r[3])) break;
                Gfx.push(g);
                Gfx.translate(g, (float) (cx + dx), (float) (cy + dy));
                if (Gfx.canRotate(g)) Gfx.rotate(g, (float) rot);
                Gfx.scale(g, (float) sc, (float) sc);
                spilled = Gfx.blit(g, art.spillId, -size / 2, -size / 2, size, size, AlbumArt.SPILL, AlbumArt.SPILL);
                Gfx.pop(g);
                Gfx.scissorOff(g);
                if (!spilled) break;
            }
        }
        if (!spilled) {
            round(g, x, y, w, h, backdropColor(cfg, art, 0xE6));
            return;
        }
        if (cfg.bgDarkness > 0) {
            round(g, x, y, w, h, ((int) (cfg.bgDarkness * 2.55) << 24));
        }
    }

    private static void glowRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int rgb, int strength) {
        double a = 0.06 * (strength / 60.0);
        int alpha = (int) Math.max(1, Math.min(120, a * 255));
        for (int i = GLOW_LAYERS; i >= 1; i--) {
            round(g, x - i, y - i, w + 2 * i, h + 2 * i, (alpha << 24) | rgb);
        }
    }

    private static void drawArtSquare(GuiGraphicsExtractor g, Font font, AlbumArt art, int x, int y, int size) {
        if (art != null && art.artId != null
                && Gfx.blit(g, art.artId, x, y, size, size, AlbumArt.TEX, AlbumArt.TEX)) {
            return;
        }
        int[] px = art == null ? null : art.pixels();
        if (px == null) {
            round(g, x, y, size, size, 0xFF3A3A3A);
            String note = "♪";
            g.text(font, note, x + (size - font.width(note)) / 2, y + size / 2 - 4, 0xFF9A9A9A, false);
            return;
        }
        int n = AlbumArt.FALLBACK;
        for (int j = 0; j < n; j++) {
            int i = 0;
            while (i < n) {
                int c = px[j * n + i];
                int k = i + 1;
                while (k < n && px[j * n + k] == c) k++;
                g.fill(x + i, y + j, x + k, y + j + 1, c);
                i = k;
            }
        }
    }

    // ------------------------------------------------------------ CD style

    private static void drawCd(GuiGraphicsExtractor g, SpotifyConfig cfg, PlaybackState s, AlbumArt art,
                               Layout L, int x, int y, int glowRGB) {
        int d = L.artS();
        int cx = x + L.artX() + d / 2;
        int cy = y + L.artY() + d / 2;

        long now = System.nanoTime();
        if (lastNs != 0 && s.playing() && cfg.motion) {
            spinAngle += 1.1 * Math.min(0.1, (now - lastNs) / 1e9);
            if (spinAngle > Math.PI * 2) spinAngle -= Math.PI * 2;
        }
        lastNs = now;

        if (cfg.glow) {
            int gl = 18;
            int size = d + 2 * gl;
            ensureGlow(glowRGB, cfg.glowStrength, size, d);
            Gfx.blit(g, glowId, cx - size / 2, cy - size / 2, size, size, GLOW_TEX, GLOW_TEX);
        }

        if (art != null && art.discId != null) {
            if (Gfx.canRotate(g)) {
                Gfx.push(g);
                Gfx.translate(g, cx, cy);
                Gfx.rotate(g, (float) spinAngle);
                Gfx.blit(g, art.discId, -d / 2, -d / 2, d, d, AlbumArt.TEX, AlbumArt.TEX);
                Gfx.pop(g);
            } else {
                Gfx.blit(g, art.discId, cx - d / 2, cy - d / 2, d, d, AlbumArt.TEX, AlbumArt.TEX);
            }
        } else {
            drawArtSquare(g, net.minecraft.client.Minecraft.getInstance().font, art, cx - 32, cy - 32, 64);
        }
    }

    private static final int GLOW_TEX = 128;

    private static void ensureGlow(int rgb, int strength, int size, int disc) {
        int key = rgb * 31 + strength * 7 + size;
        if (key == glowKey && glowId != null) return;
        try {
            if (glowId != null) Gfx.release(glowId);
            int[] px = new int[GLOW_TEX * GLOW_TEX];
            double c = GLOW_TEX / 2.0;
            double r0 = (disc / 2.0) * (GLOW_TEX / (double) size);
            double peak = 0.85 * strength / 100.0;
            for (int j = 0; j < GLOW_TEX; j++) {
                for (int i = 0; i < GLOW_TEX; i++) {
                    double r = Math.hypot(i + 0.5 - c, j + 0.5 - c);
                    double tt = Math.max(0, Math.min(1, (r - r0) / (c - r0)));
                    double a = peak * (1 - tt) * (1 - tt);
                    px[j * GLOW_TEX + i] = ((int) (a * 255) << 24) | rgb;
                }
            }
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(GLOW_TEX, GLOW_TEX,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, GLOW_TEX, GLOW_TEX, px, 0, GLOW_TEX);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            glowId = Gfx.registerPng(out.toByteArray(), "glow/" + (Math.abs(key) % 100000) + "_" + System.nanoTime() % 100000);
        } catch (Throwable t) {
            glowId = null;
        }
        glowKey = key;
    }

    // ------------------------------------------------------------ messages / lyrics / buttons

    /** Small text-only card used for setup / idle messages. Returns its bottom y. */
    static int message(GuiGraphicsExtractor g, Font font, int x, int y, String... lines) {
        int h = 10 + lines.length * 11;
        round(g, x, y, BLOCK_W, h, 0xE0202020);
        for (int i = 0; i < lines.length; i++) {
            g.text(font, fit(font, lines[i], BLOCK_W - 16), x + 8, y + 6 + i * 11, i == 0 ? WHITE : 0xFFC8C8C8, i == 0);
        }
        return y + h;
    }

    static void status(GuiGraphicsExtractor g, Font font, String text, int x, int y) {
        g.text(font, fit(font, text, BLOCK_W + 60), x, y, 0xFFFF5555, true);
    }

    /** Two lyric lines (current bright, next dim) on translucent pills. Height is 27. */
    static void lyrics(GuiGraphicsExtractor g, Font font, String cur, String next, int x, int y) {
        if (!cur.isEmpty()) {
            String c = fit(font, cur, BLOCK_W + 60);
            round(g, x, y, font.width(c) + 10, 13, 0x99000000);
            g.text(font, c, x + 5, y + 2, WHITE, true);
        }
        if (!next.isEmpty()) {
            String n = fit(font, next, BLOCK_W + 60);
            round(g, x, y + 14, font.width(n) + 10, 13, 0x77000000);
            g.text(font, n, x + 5, y + 16, 0xFF9A9A9A, false);
        }
    }

    static void button(GuiGraphicsExtractor g, Font font, String label, int x, int y, int w, int h, boolean hover) {
        round(g, x, y, w, h, hover ? 0xE0606060 : 0xB0000000);
        g.text(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, WHITE, false);
    }

    /** Volume pill used by the control screen. */
    static void volumeRow(GuiGraphicsExtractor g, Font font, int x, int y, int w, int vol) {
        round(g, x, y, w, 14, 0xB0000000);
        g.text(font, "Vol", x + 6, y + 3, 0xFFC8C8C8, false);
        bar(g, x + VOL_BAR_X, y + 5, w - VOL_BAR_X - VOL_RIGHT, BAR_H, vol / 100.0, WHITE, true);
        String pct = vol + "%";
        g.text(font, pct, x + w - 6 - font.width(pct), y + 3, 0xFFC8C8C8, false);
    }

    static final int VOL_BAR_X = 26, VOL_RIGHT = 34;

    static boolean inVolume(double mx, double my, int x, int y, int w) {
        return mx >= x + VOL_BAR_X - 3 && mx <= x + w - VOL_RIGHT + 3 && my >= y && my <= y + 14;
    }

    static double volFrac(double mx, int x, int w) {
        return Math.max(0, Math.min(1, (mx - (x + VOL_BAR_X)) / (double) (w - VOL_BAR_X - VOL_RIGHT)));
    }

    // ------------------------------------------------------------ primitives

    private static void aligned(GuiGraphicsExtractor g, Font font, String s, int colX, int colW, int y,
                                int color, boolean shadow, double scale, int align) {
        String f = fit(font, s, (int) (colW / scale));
        int tw = (int) Math.ceil(font.width(f) * scale);
        int px = align == 1 ? colX + (colW - tw) / 2 : align == 2 ? colX + colW - tw : colX;
        Gfx.text(g, font, f, px, y, color, shadow, scale);
    }

    private static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, double frac, int color, boolean knob) {
        frac = Math.max(0, Math.min(1, frac));
        int fw = (int) (w * frac);
        g.fill(x, y, x + w, y + h, 0x55FFFFFF);
        if (fw > 0) g.fill(x, y, x + fw, y + h, color);
        if (knob) {
            int kx = Math.max(x + 2, Math.min(x + w - 2, x + fw));
            g.fill(kx - 2, y - 2, kx + 2, y + h + 2, WHITE);
        }
    }

    /** Rectangle with softly rounded corners (no overlapping fills, so translucent colours stay even). */
    static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        if (h < 5 || w < 5) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x + 2, y, x + w - 2, y + 1, color);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, color);
        g.fill(x, y + 2, x + w, y + h - 2, color);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, color);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, color);
    }

    // ------------------------------------------------------------ hit testing

    static boolean inBar(Layout L, double mx, double my, int x, int y) {
        return mx >= x + L.barX() - 3 && mx <= x + L.barX() + L.barW() + 3
                && my >= y + L.barY() - 5 && my <= y + L.barY() + BAR_H + 5;
    }

    static double barFrac(Layout L, double mx, int x) {
        return Math.max(0, Math.min(1, (mx - (x + L.barX())) / L.barW()));
    }

    // ------------------------------------------------------------ text helpers

    private static int dim(int rgb, double f) {
        int r = (int) (((rgb >> 16) & 0xFF) * f);
        int g = (int) (((rgb >> 8) & 0xFF) * f);
        int b = (int) ((rgb & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    static String fit(Font font, String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        String ellipsis = "...";
        int end = s.length();
        while (end > 0 && font.width(s.substring(0, end) + ellipsis) > maxWidth) end--;
        return s.substring(0, end) + ellipsis;
    }

    static String fmt(long ms) {
        long total = Math.max(0, ms) / 1000;
        return (total / 60) + ":" + String.format("%02d", total % 60);
    }
}
