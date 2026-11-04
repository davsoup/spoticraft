package dev.spotifyhud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Layout, drawing and hit-testing for the now-playing card (block style) and the spinning CD style.
 * Backgrounds, glow and darkness are all built from the same pixel-aligned rounded shape, so they stay
 * consistent at every size.
 */
final class HudPanel {
    static final int BLOCK_W = 224, CD_W = 150;
    static final int PAD = 8, ART = 64, GAP = 10, BAR_H = 4;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREEN = 0xFF1DB954;

    // spin state (render thread)
    private static double spinAngle = 0;
    private static long lastNs = 0;

    // baked glow textures (render thread)
    private static final int DEN = 3;
    private static Identifier rectGlowId, radGlowId;
    private static long rectKey = Long.MIN_VALUE, radKey = Long.MIN_VALUE;
    private static int rectT, radN;
    private static int glowCounter = 0;

    private HudPanel() {
    }

    /** Geometry relative to the card's top-left corner, before scaling. */
    record Layout(int style, boolean text, int w, int h, int artX, int artY, int artS, int tx, int tw,
                  int titleY, int artistY, int barY, int timeY, double ts, double as,
                  int titleH, int artistH, int textTop, int badgeY) {
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
            if (!cfg.cdText) {
                int w = d + 8;
                int h = 4 + d + 4 + (cfg.modeIndicators ? 13 : 0);
                return new Layout(1, false, w, h, 4, 4, d, 0, w, 0, 0, 0, 0, ts, as, titleH, artistH, 0, 4 + d + 4);
            }
            int w = Math.max(CD_W, d + 8);
            int titleY = 4 + d + 8;
            int artistY = titleY + titleH + 2;
            int barY = artistY + artistH + 6;
            return new Layout(1, true, w, titleY + content + 5, (w - d) / 2, 4, d, (w - CD_W) / 2 + 6, CD_W - 12,
                    titleY, artistY, barY, barY + BAR_H + 3, ts, as, titleH, artistH, titleY - 4, -1);
        }
        int h = Math.max(ART + 2 * PAD, content + 2 * PAD);
        int top = (h - content) / 2;
        int artistY = top + titleH + 2;
        int barY = artistY + artistH + 6;
        int tx = PAD + ART + GAP;
        return new Layout(0, true, BLOCK_W, h, PAD + cfg.artOffX, (h - ART) / 2 + cfg.artOffY, ART, tx,
                BLOCK_W - tx - PAD, top, artistY, barY, barY + BAR_H + 3, ts, as, titleH, artistH, 0, -1);
    }

    /** Scale applied to the whole block card (the CD resizes only its disc). */
    static double scaleOf(SpotifyConfig cfg) {
        if (cfg.style == 1 || !Gfx.poseUsable()) return 1.0;
        return Math.max(0.5, Math.min(3.0, cfg.hudScale));
    }

    /** Corner radius in screen pixels for the block card (0 = square). */
    static int blockRadius(SpotifyConfig cfg) {
        if (!cfg.rounded) return 0;
        return (int) Math.max(4, Math.min(12, Math.round(4 + 2 * scaleOf(cfg))));
    }

    static int smallRadius(SpotifyConfig cfg) {
        return cfg.rounded ? 4 : 0;
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
        Layout L = layout(cfg);
        double t = cfg.motion ? System.nanoTime() / 1e9 : 0.0;
        if (L.style() == 0) {
            int[] R = cardRect(cfg, x, y);
            drawBlockBackground(g, cfg, art, x, y, R[2], R[3], t);
            double sc = scaleOf(cfg);
            if (Math.abs(sc - 1.0) > 0.01 && Gfx.canTransform(g)) {
                Gfx.push(g);
                Gfx.translate(g, x, y);
                Gfx.scale(g, (float) sc, (float) sc);
                content(g, font, cfg, s, art, L, 0, 0, interactive, progOv);
                Gfx.pop(g);
            } else {
                content(g, font, cfg, s, art, L, x, y, interactive, progOv);
            }
            return;
        }
        // CD
        drawDisc(g, font, art, L, x, y);
        if (L.text()) {
            int px = x + (L.w() - CD_W) / 2;
            int py = y + L.textTop();
            int pw = CD_W;
            int ph = L.h() - L.textTop();
            drawBox(g, art, px, py, pw, ph, smallRadius(cfg), cfg.cdBgMode, cfg, 0xB0, 0x30, t);
            content(g, font, cfg, s, art, L, x, y, interactive, progOv);
        } else if (cfg.modeIndicators) {
            badges(g, font, s, false, x + L.w() / 2, y + L.badgeY());
        }
    }

    /** Art (block only), title, artist, bar, times and badges. Coordinates are layout-local plus (x, y). */
    private static void content(GuiGraphicsExtractor g, Font font, SpotifyConfig cfg, PlaybackState s, AlbumArt art,
                                Layout L, int x, int y, boolean interactive, long progOv) {
        int accent = art != null ? art.accent() : 0xFFFFFFFF;
        int textRGB = (cfg.textColor < 0 ? accent : cfg.textColor) & 0xFFFFFF;
        int barRGB = (cfg.barColor < 0 ? accent : cfg.barColor) & 0xFFFFFF;
        boolean cd = L.style() == 1;
        if (!cd) drawArtSquare(g, font, art, x + L.artX(), y + L.artY(), L.artS());

        int align = cd ? cfg.alignCd : cfg.alignBlock;
        int titleCol = 0xFF000000 | textRGB;
        int artistCol = 0xFF000000 | dim(textRGB, 0.82);
        int timeCol = 0xFF000000 | dim(textRGB, 0.62);

        String title = s.title().isEmpty() ? "Unknown" : s.title();
        aligned(g, font, cfg, title, x + L.tx(), L.tw(), y + L.titleY(), titleCol, true, L.ts(), align);
        aligned(g, font, cfg, s.artist(), x + L.tx(), L.tw(), y + L.artistY(), artistCol, true, L.as(), align);

        long progress = progOv >= 0 ? progOv : s.liveProgressMs();
        double frac = s.durationMs() > 0 ? Math.min(1.0, (double) progress / s.durationMs()) : 0.0;
        bar(g, x + L.barX(), y + L.barY(), L.barW(), BAR_H, frac, 0xFF000000 | barRGB, interactive);

        int rowY = y + L.timeY();
        String left = fmt(progress);
        String right = fmt(s.durationMs());
        g.text(font, left, x + L.tx(), rowY, timeCol, true);
        g.text(font, right, x + L.tx() + L.tw() - font.width(right), rowY, timeCol, true);
        badges(g, font, s, cfg.modeIndicators, x + L.tx() + L.tw() / 2, rowY - 1);
    }

    /** Centred row of small pills: paused, shuffle, repeat. */
    private static void badges(GuiGraphicsExtractor g, Font font, PlaybackState s, boolean showModes, int cx, int y) {
        List<String> items = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        boolean any = showModes && (s.shuffle() || s.repeat() != 0);
        if (!s.playing()) {
            items.add(any ? "||" : "Paused");
            cols.add(WHITE);
        }
        if (showModes && s.shuffle()) {
            items.add("SHF");
            cols.add(GREEN);
        }
        if (showModes && s.repeat() != 0) {
            items.add(s.repeat() == 2 ? "RP1" : "RPT");
            cols.add(GREEN);
        }
        if (items.isEmpty()) return;
        int total = 0;
        for (String it : items) total += font.width(it) + 6;
        total += (items.size() - 1) * 3;
        int bx = cx - total / 2;
        for (int i = 0; i < items.size(); i++) {
            int w = font.width(items.get(i)) + 6;
            round(g, bx, y, w, 11, 0xB0000000);
            g.text(font, items.get(i), bx + 3, y + 2, cols.get(i), false);
            bx += w + 3;
        }
    }

    // ------------------------------------------------------------ rounded shapes

    /** Row bands of a rounded rectangle: {x1, y1, x2, y2}. Everything shaped like a card uses these. */
    static List<int[]> bands(int x, int y, int w, int h, int r) {
        List<int[]> out = new ArrayList<>();
        if (r <= 0 || h < 2 * r + 1 || w < 2 * r + 1) {
            out.add(new int[]{x, y, x + w, y + h});
            return out;
        }
        int[] ins = new int[r];
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            ins[i] = r - (int) Math.round(Math.sqrt(r * (double) r - dy * dy));
        }
        int i = 0;
        while (i < r) {
            int j = i;
            while (j + 1 < r && ins[j + 1] == ins[i]) j++;
            out.add(new int[]{x + ins[i], y + i, x + w - ins[i], y + j + 1});
            i = j + 1;
        }
        out.add(new int[]{x, y + r, x + w, y + h - r});
        int k = 0;
        while (k < r) {
            int m = k;
            int in = ins[r - 1 - k];
            while (m + 1 < r && ins[r - 1 - (m + 1)] == in) m++;
            out.add(new int[]{x + in, y + h - r + k, x + w - in, y + h - r + m + 1});
            k = m + 1;
        }
        return out;
    }

    static void roundR(GuiGraphicsExtractor g, int x, int y, int w, int h, int color, int r) {
        for (int[] b : bands(x, y, w, h, r)) g.fill(b[0], b[1], b[2], b[3], color);
    }

    /** Rounded rectangle with a small fixed radius, used by buttons and pills. */
    static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        roundR(g, x, y, w, h, color, 2);
    }

    /** Draws the moving colour spill clipped to a rounded rectangle. Returns false if it can't. */
    private static boolean spillRect(GuiGraphicsExtractor g, AlbumArt art, int x, int y, int w, int h, int r, double t) {
        if (art == null || art.spillId == null || !Gfx.canTransform(g)) return false;
        int cx = x + w / 2;
        int cy = y + h / 2;
        int size = (int) (Math.hypot(w, h) * 1.2) + 8;
        double dx = 14 * Math.sin(t * 0.35);
        double dy = 8 * Math.sin(t * 0.27 + 1.0);
        double sc = 1.0 + 0.07 * Math.sin(t * 0.5);
        double rot = 0.07 * Math.sin(t * 0.21);
        boolean ok = false;
        for (int[] b : bands(x, y, w, h, r)) {
            if (!Gfx.scissorOn(g, b[0], b[1], b[2], b[3])) return ok;
            Gfx.push(g);
            Gfx.translate(g, (float) (cx + dx), (float) (cy + dy));
            if (Gfx.canRotate(g)) Gfx.rotate(g, (float) rot);
            Gfx.scale(g, (float) sc, (float) sc);
            ok = Gfx.blit(g, art.spillId, -size / 2, -size / 2, size, size, AlbumArt.SPILL, AlbumArt.SPILL);
            Gfx.pop(g);
            Gfx.scissorOff(g);
            if (!ok) return false;
        }
        return true;
    }

    private static int solidColor(SpotifyConfig cfg, AlbumArt art, int alpha) {
        if (cfg.bgColor >= 0) return (alpha << 24) | (cfg.bgColor & 0xFFFFFF);
        return art != null ? art.darkAverage(alpha) : (alpha << 24) | 0x202020;
    }

    /** Generic box: mode 0 = mograph, 1 = solid, 2 = none. Darkness is laid over the mograph. */
    private static void drawBox(GuiGraphicsExtractor g, AlbumArt art, int x, int y, int w, int h, int r, int mode,
                                SpotifyConfig cfg, int solidAlpha, int darkPct, double t) {
        if (mode == 2) return;
        if (mode == 0 && spillRect(g, art, x, y, w, h, r, t)) {
            if (darkPct > 0) roundR(g, x, y, w, h, (int) (darkPct * 2.55) << 24, r);
            return;
        }
        roundR(g, x, y, w, h, solidColor(cfg, art, solidAlpha), r);
    }

    private static void drawBlockBackground(GuiGraphicsExtractor g, SpotifyConfig cfg, AlbumArt art,
                                            int x, int y, int w, int h, double t) {
        int mode = cfg.bgMode;
        drawBox(g, art, x, y, w, h, blockRadius(cfg), mode, cfg, 0xE6, cfg.bgDarkness, t);
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

    // ------------------------------------------------------------ CD disc

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

    // ------------------------------------------------------------ glow (baked soft textures, never rotated)

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
        int r = blockRadius(cfg);
        ensureRectGlow(rgb, spread, cfg.glowStrength, cfg.glowSoftness, r);
        if (rectGlowId == null) return;
        int X = R[0], Y = R[1], W = R[2], H = R[3];
        if (W < 2 * r + 2 || H < 2 * r + 2) return;
        int cs = spread + r;                 // corner piece size in screen pixels
        int cr = cs * DEN;                   // corner piece size in texels
        int t = rectT;
        int mid = 4;                         // stretchable middle (texels)
        int x0 = X - spread, y0 = Y - spread;
        int x1 = X + W - r, y1 = Y + H - r;
        Identifier id = rectGlowId;
        Gfx.blitRegion(g, id, x0, y0, cs, cs, 0, 0, cr, cr, t, t);
        Gfx.blitRegion(g, id, x1, y0, cs, cs, t - cr, 0, cr, cr, t, t);
        Gfx.blitRegion(g, id, x0, y1, cs, cs, 0, t - cr, cr, cr, t, t);
        Gfx.blitRegion(g, id, x1, y1, cs, cs, t - cr, t - cr, cr, cr, t, t);
        Gfx.blitRegion(g, id, x0 + cs, y0, x1 - (x0 + cs), cs, cr, 0, mid, cr, t, t);
        Gfx.blitRegion(g, id, x0 + cs, y1, x1 - (x0 + cs), cs, cr, t - cr, mid, cr, t, t);
        Gfx.blitRegion(g, id, x0, y0 + cs, cs, y1 - (y0 + cs), 0, cr, cr, mid, t, t);
        Gfx.blitRegion(g, id, x1, y0 + cs, cs, y1 - (y0 + cs), t - cr, cr, cr, mid, t, t);
    }

    private static double falloff(double dist, double reach, double softness) {
        if (dist <= 0) return 1.0;
        double t = dist / reach;
        if (t >= 1) return 0.0;
        double k = 1.0 + 3.0 * (1.0 - softness / 100.0);
        return Math.pow(1.0 - t, k);
    }

    private static void ensureRectGlow(int rgb, int spread, int intensity, int softness, int r) {
        long key = ((long) rgb << 32) ^ (spread * 1000003L + intensity * 1009L + softness * 31L + r * 7919L);
        if (key == rectKey && rectGlowId != null) return;
        Gfx.release(rectGlowId);
        rectGlowId = null;
        rectKey = key;
        try {
            int mt = spread * DEN;
            int rt = r * DEN;
            int body = 2 * rt + 4;
            int t = 2 * mt + body;
            int[] px = new int[t * t];
            double peak = 0.9 * intensity / 100.0;
            double half = body / 2.0;
            double c = t / 2.0;
            for (int j = 0; j < t; j++) {
                for (int i = 0; i < t; i++) {
                    double qx = Math.abs(i + 0.5 - c) - (half - rt);
                    double qy = Math.abs(j + 0.5 - c) - (half - rt);
                    double d = Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - rt;
                    // transparent inside the card so no glow can ever show through the corners
                    double inside = Math.max(0, Math.min(1, (d + 1.5) / 1.5));
                    double a = peak * falloff(Math.max(d, 0), mt, softness) * inside;
                    px[j * t + i] = ((int) Math.round(a * 255) << 24) | rgb;
                }
            }
            rectT = t;
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

    static int lyricsPillH(SpotifyConfig cfg) {
        return (int) Math.round(13 * cfg.lyricsScale / 100.0);
    }

    private static int lyricsMaxPx(SpotifyConfig cfg, int boxW) {
        double sc = cfg.lyricsScale / 100.0;
        int padX = (int) Math.round(5 * sc);
        return (int) (((Math.max(boxW, BLOCK_W) + 60) - 2 * padX) / sc);
    }

    /** Greedy word wrap (falls back to character wrap for very long words). Excess lines end in "...". */
    static List<String> wrap(Font font, String s, int maxPx, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (s.isEmpty()) return lines;
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            if (word.isEmpty()) continue;
            String test = cur.length() == 0 ? word : cur + " " + word;
            if (font.width(test) <= maxPx) {
                cur = new StringBuilder(test);
                continue;
            }
            if (cur.length() > 0) {
                lines.add(cur.toString());
                cur = new StringBuilder();
            }
            // word on its own line; split by characters if it is wider than the line
            StringBuilder chunk = new StringBuilder();
            for (int i = 0; i < word.length(); i++) {
                String t2 = chunk.toString() + word.charAt(i);
                if (font.width(t2) > maxPx && chunk.length() > 0) {
                    lines.add(chunk.toString());
                    chunk = new StringBuilder();
                }
                chunk.append(word.charAt(i));
            }
            cur = chunk;
        }
        if (cur.length() > 0) lines.add(cur.toString());
        if (lines.size() > maxLines) {
            List<String> cut = new ArrayList<>(lines.subList(0, maxLines));
            cut.set(maxLines - 1, fit(font, cut.get(maxLines - 1) + "...", maxPx));
            return cut;
        }
        return lines;
    }

    static int lyricsHeight(Font font, SpotifyConfig cfg, String cur, String next, int boxW) {
        int max = lyricsMaxPx(cfg, boxW);
        int n = wrap(font, cur, max, 3).size() + wrap(font, next, max, 2).size();
        if (n == 0) return 0;
        return n * (lyricsPillH(cfg) + 1) - 1;
    }

    /** Current lyric (bright) and next lyric (dim), wrapped onto as many pill lines as they need. */
    static void lyrics(GuiGraphicsExtractor g, Font font, SpotifyConfig cfg, AlbumArt art, String cur, String next,
                       int x, int y, int boxW, int align) {
        double sc = cfg.lyricsScale / 100.0;
        int ph = lyricsPillH(cfg);
        int padX = (int) Math.round(5 * sc);
        int max = lyricsMaxPx(cfg, boxW);
        double t = cfg.motion ? System.nanoTime() / 1e9 : 0.0;
        List<String> curLines = wrap(font, cur, max, 3);
        List<String> nextLines = wrap(font, next, max, 2);
        int total = curLines.size() + nextLines.size();
        for (int n = 0; n < total; n++) {
            boolean bright = n < curLines.size();
            String f = bright ? curLines.get(n) : nextLines.get(n - curLines.size());
            int tw = (int) Math.ceil(font.width(f) * sc);
            int w = tw + 2 * padX;
            int px = align == 1 ? x + (boxW - w) / 2 : align == 2 ? x + boxW - w : x;
            int py = y + n * (ph + 1);
            int r = cfg.rounded ? 3 : 0;
            switch (cfg.lyricsBgMode) {
                case 0 -> roundR(g, px, py, w, ph, bright ? 0x99000000 : 0x77000000, r);
                case 1 -> {
                    int a = bright ? 0xD0 : 0xA0;
                    int col = cfg.lyricsBgColor < 0 ? (art != null ? art.darkAverage(a) : (a << 24) | 0x202020)
                            : (a << 24) | (cfg.lyricsBgColor & 0xFFFFFF);
                    roundR(g, px, py, w, ph, col, r);
                }
                case 2 -> {
                    if (spillRect(g, art, px, py, w, ph, r, t)) roundR(g, px, py, w, ph, bright ? 0x38000000 : 0x60000000, r);
                    else roundR(g, px, py, w, ph, 0x99000000, r);
                }
                default -> {
                }
            }
            Gfx.text(g, font, f, px + padX, py + (int) Math.round(2 * sc), bright ? WHITE : 0xFFB8B8B8, bright, sc);
        }
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

    /**
     * Draws one line of text in a column. Text that is too wide either shrinks to fit (auto-fit) or slowly
     * scrolls to the side and back, like a music player.
     */
    private static void aligned(GuiGraphicsExtractor g, Font font, SpotifyConfig cfg, String s, int colX, int colW,
                                int y, int color, boolean shadow, double scale, int align) {
        int rawW = font.width(s);
        double sc = scale;
        if (cfg.autoFit && rawW * sc > colW) {
            sc = Math.max(0.6, Math.min(scale, colW / (double) rawW));
        }
        int yy = y + (int) Math.round((scale - sc) * 9 / 2.0);
        double tw = rawW * sc;
        if (tw <= colW + 0.5) {
            int w = (int) Math.ceil(tw);
            int px = align == 1 ? colX + (colW - w) / 2 : align == 2 ? colX + colW - w : colX;
            Gfx.text(g, font, s, px, yy, color, shadow, sc);
            return;
        }
        // marquee: pause, slide to the end, pause, slide back
        int h = (int) Math.ceil(9 * sc);
        if (!Gfx.scissorOn(g, colX, yy - 1, colX + colW, yy + h + 2)) {
            Gfx.text(g, font, fit(font, s, (int) (colW / sc)), colX, yy, color, shadow, sc);
            return;
        }
        double over = tw - colW;
        double speed = 18.0, pause = 1.2;
        double move = over / speed;
        double period = 2 * pause + 2 * move;
        double ph = (System.nanoTime() / 1e9) % period;
        double off;
        if (ph < pause) off = 0;
        else if (ph < pause + move) off = (ph - pause) * speed;
        else if (ph < 2 * pause + move) off = over;
        else off = over - (ph - 2 * pause - move) * speed;
        Gfx.text(g, font, s, (int) Math.round(colX - off), yy, color, shadow, sc);
        Gfx.scissorOff(g);
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

    // ------------------------------------------------------------ hit testing (screen coordinates)

    static boolean inBar(SpotifyConfig cfg, double mx, double my, int x, int y) {
        Layout L = layout(cfg);
        if (!L.text()) return false;
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
