package dev.spotifyhud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/** Layout, drawing and hit-testing for the now-playing card (block style) and the spinning CD style. */
final class HudPanel {
    static final int BLOCK_W = 224, CD_W = 150;
    static final int PAD = 8, ART = 64, GAP = 10, BAR_H = 4;

    private static final int WHITE = 0xFFFFFFFF;

    // spin state (render thread)
    private static double spinAngle = 0;
    private static long lastNs = 0;

    // baked glow textures (render thread)
    private static final int DEN = 3, RT = 9, BODY = 2 * RT + 4;
    private static Identifier rectGlowId, radGlowId;
    private static long rectKey = Long.MIN_VALUE, radKey = Long.MIN_VALUE;
    private static int rectT, rectCR, radN;
    private static int glowCounter = 0;

    private HudPanel() {
    }

    /** Geometry relative to the card's top-left corner, before scaling. */
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
            int d = Math.max(40, Math.min(220, cfg.cdSize));
            int w = Math.max(CD_W, d + 8);
            int titleY = 4 + d + 8;
            int artistY = titleY + titleH + 2;
            int barY = artistY + artistH + 6;
            return new Layout(1, w, titleY + content + 5, (w - d) / 2, 4, d, (w - CD_W) / 2 + 6, CD_W - 12,
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

    /** Scale applied to the whole block card (the CD resizes only its disc). */
    static double scaleOf(SpotifyConfig cfg) {
        if (cfg.style == 1 || !Gfx.poseUsable()) return 1.0;
        return Math.max(0.5, Math.min(3.0, cfg.hudScale));
    }

    /** On-screen rectangle of the card: {x, y, w, h}. */
    static int[] cardRect(SpotifyConfig cfg, int x, int y) {
        Layout L = layout(cfg);
        double sc = scaleOf(cfg);
        return new int[]{x, y, (int) Math.round(L.w() * sc), (int) Math.round(L.h() * sc)};
    }

    /** Rectangle whose corners are the resize handles: the card (block) or the disc (CD). */
    static int[] resizeRect(SpotifyConfig cfg, int x, int y) {
        Layout L = layout(cfg);
        if (L.style() == 1) return new int[]{x + L.artX(), y + L.artY(), L.artS(), L.artS()};
        return cardRect(cfg, x, y);
    }

    // ------------------------------------------------------------ main entry

    /**
     * Draws glow + card. When oldS is non-null and p < 1 the old track slides out to the left while the new
     * one slides in from the right.
     */
    static void render(GuiGraphicsExtractor g, Font font, SpotifyConfig cfg, PlaybackState s, AlbumArt art,
                       int x, int y, boolean interactive, long progOv,
                       PlaybackState oldS, AlbumArt oldArt, double p) {
        Layout L = layout(cfg);
        int[] R = cardRect(cfg, x, y);
        if (art != null) art.ensureTextures();
        if (oldArt != null) oldArt.ensureTextures();

        long now = System.nanoTime();
        if (lastNs != 0 && s.playing() && cfg.motion) {
            spinAngle += 1.1 * Math.min(0.1, (now - lastNs) / 1e9);
            if (spinAngle > Math.PI * 2) spinAngle -= Math.PI * 2;
        }
        lastNs = now;

        int accent = art != null ? art.accent() : 0xFFFFFFFF;
        int glowRGB = (cfg.glowColor < 0 ? accent : cfg.glowColor) & 0xFFFFFF;
        if (cfg.glow && cfg.glowStrength > 0) drawGlow(g, cfg, L, R, x, y, glowRGB);

        boolean trans = oldS != null && p < 1 && Gfx.canTransform(g);
        if (trans && Gfx.scissorOn(g, R[0], R[1], R[0] + R[2], R[1] + R[3])) {
            double e = 1 - Math.pow(1 - p, 3);
            Gfx.push(g);
            Gfx.translate(g, (float) (-e * R[2]), 0f);
            card(g, font, cfg, oldS.withProgress(oldS.durationMs()), oldArt, x, y, false, -1);
            Gfx.pop(g);
            Gfx.push(g);
            Gfx.translate(g, (float) ((1 - e) * R[2]), 0f);
            card(g, font, cfg, s, art, x, y, interactive, progOv);
            Gfx.pop(g);
            Gfx.scissorOff(g);
            return;
        }
        card(g, font, cfg, s, art, x, y, interactive, progOv);
    }

    private static void card(GuiGraphicsExtractor g, Font font, SpotifyConfig cfg, PlaybackState s, AlbumArt art,
                             int x, int y, boolean interactive, long progOv) {
        double sc = scaleOf(cfg);
        if (Math.abs(sc - 1.0) > 0.01 && Gfx.canTransform(g)) {
            Gfx.push(g);
            Gfx.translate(g, x, y);
            Gfx.scale(g, (float) sc, (float) sc);
            cardInner(g, font, cfg, s, art, 0, 0, interactive, progOv);
            Gfx.pop(g);
        } else {
            cardInner(g, font, cfg, s, art, x, y, interactive, progOv);
        }
    }

    private static void cardInner(GuiGraphicsExtractor g, Font font, SpotifyConfig cfg, PlaybackState s, AlbumArt art,
                                  int x, int y, boolean interactive, long progOv) {
        Layout L = layout(cfg);
        int accent = art != null ? art.accent() : 0xFFFFFFFF;
        int textRGB = (cfg.textColor < 0 ? accent : cfg.textColor) & 0xFFFFFF;
        int barRGB = (cfg.barColor < 0 ? accent : cfg.barColor) & 0xFFFFFF;
        double t = cfg.motion ? System.nanoTime() / 1e9 : 0.0;
        boolean cd = L.style() == 1;

        if (cd) {
            drawDisc(g, font, art, L, x, y);
            if (cfg.bgMode != 2) {
                round(g, x + (L.w() - CD_W) / 2, y + L.textTop(), CD_W, L.h() - L.textTop(), backdropColor(cfg, art, 0xB0));
            }
        } else {
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
            String pz = "Paused";
            g.text(font, pz, x + L.tx() + (L.tw() - font.width(pz)) / 2, rowY, titleCol, true);
        }
    }

    // ------------------------------------------------------------ block background

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

    // ------------------------------------------------------------ CD

    private static void drawDisc(GuiGraphicsExtractor g, Font font, AlbumArt art, Layout L, int x, int y) {
        int d = L.artS();
        int cx = x + L.artX() + d / 2;
        int cy = y + L.artY() + d / 2;
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
            drawArtSquare(g, font, art, cx - 32, cy - 32, 64);
        }
    }

    // ------------------------------------------------------------ glow (baked soft textures)

    private static void drawGlow(GuiGraphicsExtractor g, SpotifyConfig cfg, Layout L, int[] R, int x, int y, int rgb) {
        int spread = cfg.glowSpread;
        if (L.style() == 1) {
            int d = L.artS();
            int size = d + 2 * spread;
            ensureRadGlow(rgb, spread, cfg.glowStrength, cfg.glowSoftness, d);
            int cx = x + L.artX() + d / 2;
            int cy = y + L.artY() + d / 2;
            Gfx.blit(g, radGlowId, cx - size / 2, cy - size / 2, size, size, radN, radN);
            return;
        }
        ensureRectGlow(rgb, spread, cfg.glowStrength, cfg.glowSoftness);
        if (rectGlowId == null) return;
        double sc = scaleOf(cfg);
        int mt = spread * DEN;
        int cr = mt + RT;
        int t = rectT;
        double spreadG = spread * sc;
        double cs = cr / (double) DEN * sc;
        double rtG = RT / (double) DEN * sc;
        int X = R[0], Y = R[1], W = R[2], H = R[3];
        int x0 = (int) Math.round(X - spreadG);
        int y0 = (int) Math.round(Y - spreadG);
        int csI = (int) Math.round(cs);
        int x1 = (int) Math.round(X + W - rtG);
        int y1 = (int) Math.round(Y + H - rtG);
        int mid = BODY - 2 * RT; // texels of stretchable middle
        Identifier id = rectGlowId;
        Gfx.blitRegion(g, id, x0, y0, csI, csI, 0, 0, cr, cr, t, t);
        Gfx.blitRegion(g, id, x1, y0, csI, csI, t - cr, 0, cr, cr, t, t);
        Gfx.blitRegion(g, id, x0, y1, csI, csI, 0, t - cr, cr, cr, t, t);
        Gfx.blitRegion(g, id, x1, y1, csI, csI, t - cr, t - cr, cr, cr, t, t);
        Gfx.blitRegion(g, id, x0 + csI, y0, x1 - (x0 + csI), csI, cr, 0, mid, cr, t, t);
        Gfx.blitRegion(g, id, x0 + csI, y1, x1 - (x0 + csI), csI, cr, t - cr, mid, cr, t, t);
        Gfx.blitRegion(g, id, x0, y0 + csI, csI, y1 - (y0 + csI), 0, cr, cr, mid, t, t);
        Gfx.blitRegion(g, id, x1, y0 + csI, csI, y1 - (y0 + csI), t - cr, cr, cr, mid, t, t);
    }

    private static double falloff(double dist, double reach, double softness) {
        if (dist <= 0) return 1.0;
        double t = dist / reach;
        if (t >= 1) return 0.0;
        double k = 1.0 + 3.0 * (1.0 - softness / 100.0);
        return Math.pow(1.0 - t, k);
    }

    private static void ensureRectGlow(int rgb, int spread, int intensity, int softness) {
        long key = ((long) rgb << 32) ^ (spread * 1000003L + intensity * 1009L + softness * 31L);
        if (key == rectKey && rectGlowId != null) return;
        Gfx.release(rectGlowId);
        rectGlowId = null;
        rectKey = key;
        try {
            int mt = spread * DEN;
            int t = 2 * mt + BODY;
            int[] px = new int[t * t];
            double peak = 0.9 * intensity / 100.0;
            double half = BODY / 2.0;
            double c = t / 2.0;
            for (int j = 0; j < t; j++) {
                for (int i = 0; i < t; i++) {
                    double qx = Math.abs(i + 0.5 - c) - (half - RT);
                    double qy = Math.abs(j + 0.5 - c) - (half - RT);
                    double d = Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - RT;
                    double a = peak * falloff(d, mt, softness);
                    px[j * t + i] = ((int) Math.round(a * 255) << 24) | rgb;
                }
            }
            rectT = t;
            rectCR = mt + RT;
            rectGlowId = Gfx.registerPng(png(px, t, t), "glow/r" + (++glowCounter));
        } catch (Throwable ignored) {
            rectGlowId = null;
        }
    }

    private static void ensureRadGlow(int rgb, int spread, int intensity, int softness, int d) {
        int dq = d / 4 * 4;
        long key = ((long) rgb << 32) ^ (spread * 1000003L + intensity * 1009L + softness * 31L + dq * 17L + 1);
        if (key == radKey && radGlowId != null) return;
        Gfx.release(radGlowId);
        radGlowId = null;
        radKey = key;
        try {
            double size = d + 2.0 * spread;
            int n = (int) Math.max(64, Math.min(256, Math.round(size * DEN)));
            int[] px = new int[n * n];
            double c = n / 2.0;
            double r0 = (d / 2.0) / size * n;
            double peak = 0.9 * intensity / 100.0;
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    double r = Math.hypot(i + 0.5 - c, j + 0.5 - c);
                    double a = peak * falloff(r - r0, c - r0, softness);
                    px[j * n + i] = ((int) Math.round(a * 255) << 24) | rgb;
                }
            }
            radN = n;
            radGlowId = Gfx.registerPng(png(px, n, n), "glow/c" + (++glowCounter));
        } catch (Throwable ignored) {
            radGlowId = null;
        }
    }

    private static byte[] png(int[] argb, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
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

    /** Two lyric lines (current bright, next dim) on translucent pills, aligned inside a box of width boxW. Height is 27. */
    static void lyrics(GuiGraphicsExtractor g, Font font, String cur, String next, int x, int y, int boxW, int align) {
        int maxW = Math.max(boxW, BLOCK_W) + 60;
        if (!cur.isEmpty()) {
            String c = fit(font, cur, maxW);
            int w = font.width(c) + 10;
            int px = pillX(x, boxW, w, align);
            round(g, px, y, w, 13, 0x99000000);
            g.text(font, c, px + 5, y + 2, WHITE, true);
        }
        if (!next.isEmpty()) {
            String n = fit(font, next, maxW);
            int w = font.width(n) + 10;
            int px = pillX(x, boxW, w, align);
            round(g, px, y + 14, w, 13, 0x77000000);
            g.text(font, n, px + 5, y + 16, 0xFF9A9A9A, false);
        }
    }

    private static int pillX(int x, int boxW, int w, int align) {
        return align == 1 ? x + (boxW - w) / 2 : align == 2 ? x + boxW - w : x;
    }

    static void button(GuiGraphicsExtractor g, Font font, String label, int x, int y, int w, int h, boolean hover) {
        round(g, x, y, w, h, hover ? 0xE0606060 : 0xB0000000);
        g.text(font, fit(font, label, w - 4), x + (w - Math.min(w - 4, font.width(label))) / 2, y + (h - 8) / 2, WHITE, false);
    }

    static void toggleButton(GuiGraphicsExtractor g, Font font, String label, int x, int y, int w, int h,
                             boolean hover, boolean on) {
        round(g, x, y, w, h, on ? (hover ? 0xF03CD070 : 0xE01DB954) : (hover ? 0xE0606060 : 0xB0000000));
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

    /** Resize handle: a small square on a corner. */
    static void handle(GuiGraphicsExtractor g, int cx, int cy) {
        g.fill(cx - 3, cy - 3, cx + 3, cy + 3, 0xFF000000);
        g.fill(cx - 2, cy - 2, cx + 2, cy + 2, WHITE);
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

    // ------------------------------------------------------------ hit testing (screen coordinates)

    static boolean inBar(SpotifyConfig cfg, double mx, double my, int x, int y) {
        Layout L = layout(cfg);
        double sc = scaleOf(cfg);
        return mx >= x + L.barX() * sc - 3 && mx <= x + (L.barX() + L.barW()) * sc + 3
                && my >= y + L.barY() * sc - 5 && my <= y + (L.barY() + BAR_H) * sc + 5;
    }

    static double barFrac(SpotifyConfig cfg, double mx, int x) {
        Layout L = layout(cfg);
        double sc = scaleOf(cfg);
        return Math.max(0, Math.min(1, (mx - (x + L.barX() * sc)) / (L.barW() * sc)));
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
