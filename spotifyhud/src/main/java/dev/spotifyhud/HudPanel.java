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
    private static double armLift = 0;
    /** Queue-peek row under the mouse on the control screen (-1 = none). */
    static int peekHover = -1;

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
        if (cfg.style != 0) {
            int d = Math.max(40, Math.min(220, cfg.dSize()));
            if (!cfg.dText()) {
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
        if (cfg.style != 0 || !Gfx.poseUsable()) return 1.0;
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
        if (lastNs != 0) {
            double dt = Math.min(0.1, (now - lastNs) / 1e9);
            double target = s.playing() ? 0.0 : 1.0;
            armLift += (target - armLift) * Math.min(1.0, dt * 5.0);
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
        // CD / vinyl
        if (cfg.style == 2) drawVinyl(g, font, cfg, s, art, L, x, y, progOv);
        else drawDisc(g, font, art, L, x, y);
        if (L.text()) {
            int px = x + (L.w() - CD_W) / 2;
            int py = y + L.textTop();
            int pw = CD_W;
            int ph = L.h() - L.textTop();
            drawBox(g, art, px, py, pw, ph, smallRadius(cfg), cfg.dBox(), cfg, 0xB0, cfg.dDark(), t);
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

        int align = cfg.textAlign();
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

    // ------------------------------------------------------------ vinyl record

    private static void drawVinyl(GuiGraphicsExtractor g, Font font, SpotifyConfig cfg, PlaybackState s, AlbumArt art,
                                  Layout L, int x, int y, long progOv) {
        int d = L.artS();
        int cx = x + L.artX() + d / 2;
        int cy = y + L.artY() + d / 2;
        net.minecraft.resources.Identifier id = null;
        if (art != null) id = cfg.vinylTint && art.vinylTintId != null ? art.vinylTintId : art.vinylId;
        if (id != null) {
            if (Gfx.canRotate(g)) {
                Gfx.push(g);
                Gfx.translate(g, cx, cy);
                Gfx.rotate(g, (float) (spinAngle * 0.6));
                Gfx.blit(g, id, -d / 2, -d / 2, d, d, AlbumArt.TEX, AlbumArt.TEX);
                Gfx.pop(g);
            } else {
                Gfx.blit(g, id, cx - d / 2, cy - d / 2, d, d, AlbumArt.TEX, AlbumArt.TEX);
            }
        } else {
            drawArtSquare(g, font, art, cx - 32, cy - 32, 64);
        }
        if (!cfg.vinylArm) return;
        long progress = progOv >= 0 ? progOv : s.liveProgressMs();
        double frac = s.durationMs() > 0 ? Math.min(1.0, (double) progress / s.durationMs()) : 0.0;
        double rr = d * (0.47 - 0.26 * frac);
        rr += (d * 0.62 - rr) * armLift;                 // lifts off the record when paused
        double ang = Math.toRadians(-18 + 10 * armLift);
        double tipX = cx + rr * Math.cos(ang);
        double tipY = cy + rr * Math.sin(ang);
        double pivX = cx + d * 0.5 - 1;
        double pivY = cy - d * 0.5 + 3;
        int rgb = (cfg.vinylArmColor < 0 ? (art != null ? art.accent() : 0xFFFFFFFF) : cfg.vinylArmColor) & 0xFFFFFF;
        line(g, pivX + 2, pivY + 2, tipX + 2, tipY + 2, 2, 0x66000000);
        line(g, pivX, pivY, tipX, tipY, 2, 0xFF000000 | rgb);
        int hx = (int) Math.round(tipX), hy = (int) Math.round(tipY);
        g.fill(hx - 3, hy - 2, hx + 3, hy + 3, 0xFF000000 | dim(rgb, 0.75));
        int px = (int) Math.round(pivX), py = (int) Math.round(pivY);
        g.fill(px - 4, py - 4, px + 4, py + 4, 0xFF000000 | dim(rgb, 0.55));
        g.fill(px - 2, py - 2, px + 2, py + 2, 0xFF000000 | rgb);
    }

    private static void line(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int th, int color) {
        int steps = (int) Math.max(1, Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0))));
        for (int i = 0; i <= steps; i++) {
            int px = (int) Math.round(x0 + (x1 - x0) * i / steps);
            int py = (int) Math.round(y0 + (y1 - y0) * i / steps);
            g.fill(px, py, px + th, py + th, color);
        }
    }

    // ------------------------------------------------------------ queue peek

    static final int PEEK_W = 150, PEEK_ROW = 20, PEEK_HEAD = 12;

    static int peekHeight(int n) {
        return PEEK_HEAD + n * PEEK_ROW + 3;
    }

    /** Where the queue peek sits: {x, y, w, h}. */
    static int[] peekRect(SpotifyConfig cfg, int n, int cardX, int cardY, int cardW, int cardH,
                          boolean interactive, int screenW) {
        int h = peekHeight(n);
        int side = cfg.queuePeekSide;
        if (side == 0 && screenW > 0 && cardX + cardW + 6 + PEEK_W > screenW) side = 1;
        if (side == 1 && cardX - 6 - PEEK_W < 0) side = screenW > 0 && cardX + cardW + 6 + PEEK_W <= screenW ? 0 : 2;
        return switch (side) {
            case 0 -> new int[]{cardX + cardW + 6, cardY, PEEK_W, h};
            case 1 -> new int[]{cardX - 6 - PEEK_W, cardY, PEEK_W, h};
            default -> new int[]{cardX, cardY + cardH + 4 + (interactive ? 94 : 3), PEEK_W, h};
        };
    }

    static void peek(GuiGraphicsExtractor g, Font font, SpotifyConfig cfg, AlbumArt art,
                     java.util.List<SpotifyApi.Item> items, CoverCache covers, int[] R) {
        int n = Math.min(items.size(), cfg.queuePeekCount);
        if (n <= 0) return;
        int accent = art != null ? art.accent() : 0xFFFFFFFF;
        int textRGB = (cfg.queuePeekColor < 0 ? accent : cfg.queuePeekColor) & 0xFFFFFF;
        double t = cfg.motion ? System.nanoTime() / 1e9 : 0.0;
        int r = cfg.rounded ? 5 : 0;
        drawBox(g, art, R[0], R[1], R[2], R[3], r, cfg.queuePeekBg, cfg, 0xC8, cfg.queuePeekDarkness, t);
        g.text(font, "Up next", R[0] + 6, R[1] + 3, 0xFF000000 | dim(textRGB, 0.65), true);
        for (int i = 0; i < n; i++) {
            SpotifyApi.Item it = items.get(i);
            int ry = R[1] + PEEK_HEAD + i * PEEK_ROW;
            if (i == peekHover) roundR(g, R[0] + 2, ry, R[2] - 4, PEEK_ROW, 0x30FFFFFF, cfg.rounded ? 3 : 0);
            int tx = R[0] + 6;
            if (cfg.queuePeekCovers) {
                int cs = 16;
                net.minecraft.resources.Identifier id = covers == null ? null : covers.get(it.art());
                if (id == null || !Gfx.blit(g, id, tx, ry + 2, cs, cs, CoverCache.SIZE, CoverCache.SIZE)) {
                    round(g, tx, ry + 2, cs, cs, 0xFF3A3A3A);
                }
                tx += cs + 5;
            }
            int colW = R[0] + R[2] - 6 - tx;
            aligned(g, font, cfg, it.name(), tx, colW, ry + 1, 0xFF000000 | textRGB, true, 1.0, 0);
            aligned(g, font, cfg, it.sub(), tx, colW, ry + 10, 0xFF000000 | dim(textRGB, 0.65), true, 1.0, 0);
        }
    }

    /** Index of the queue-peek row at (mx, my), or -1. */
    static int peekRow(SpotifyConfig cfg, int n, int[] R, double mx, double my) {
        n = Math.min(n, cfg.queuePeekCount);
        for (int i = 0; i < n; i++) {
            int ry = R[1] + PEEK_HEAD + i * PEEK_ROW;
            if (mx >= R[0] && mx < R[0] + R[2] && my >= ry && my < ry + PEEK_ROW) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------ ambient screen-edge glow

    private static int lerpRgb(int a, int b, double f) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
        int gg = (int) (((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
        int bl = (int) ((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return (r << 16) | (gg << 8) | bl;
    }

    private static int paletteAt(int[] pal, double k) {
        int n = pal.length;
        k = ((k % n) + n) % n;
        int i = (int) Math.floor(k);
        return lerpRgb(pal[i] & 0xFFFFFF, pal[(i + 1) % n] & 0xFFFFFF, k - i);
    }

    /** Soft colour wash along the screen edges, tinted by the album. */
    static void ambient(GuiGraphicsExtractor g, SpotifyConfig cfg, AlbumArt art, int sw, int sh) {
        if (!cfg.ambient || cfg.ambientIntensity <= 0 || art == null || sw <= 0 || sh <= 0) return;
        double t = cfg.motion ? System.nanoTime() / 1e9 : 0.0;
        int size = Math.min(cfg.ambientSize, Math.min(sw, sh) / 2);
        final int LAYERS = 14;
        int step = Math.max(1, size / LAYERS);
        int layers = Math.max(1, size / step);
        double pulse = cfg.motion ? 1.0 + 0.15 * Math.sin(t * 0.8) : 1.0;
        double peak = 0.55 * cfg.ambientIntensity / 100.0 * pulse;
        boolean mo = cfg.ambientMograph;
        int[] pal = art.palette();
        int base = (cfg.ambientColor < 0 ? art.accent() : cfg.ambientColor) & 0xFFFFFF;
        double speed = 0.05 * cfg.ambientSpeed;
        for (int e = 0; e < 4; e++) {
            boolean horiz = e == 0 || e == 2;
            if (cfg.ambientEdges == 1 && horiz) continue;
            if (cfg.ambientEdges == 2 && !horiz) continue;
            int segs = mo ? (horiz ? 10 : 7) : 1;
            int len = horiz ? sw : sh;
            for (int sg = 0; sg < segs; sg++) {
                int a0 = len * sg / segs, a1 = len * (sg + 1) / segs;
                double u = (e + (sg + 0.5) / segs) / 4.0;
                int rgb = base;
                double mod = 1.0;
                if (mo) {
                    if (cfg.ambientColor < 0 && pal.length > 1) rgb = paletteAt(pal, u * pal.length + t * speed * pal.length * 0.25);
                    mod = 0.7 + 0.3 * Math.sin(t * (0.4 + speed * 4) + u * Math.PI * 4);
                }
                for (int i = 0; i < layers; i++) {
                    double a = peak * mod * Math.pow(1.0 - (i + 0.5) / layers, 2.2);
                    int ai = (int) Math.min(255, Math.round(a * 255));
                    if (ai < 1) continue;
                    int col = (ai << 24) | rgb;
                    int d0 = i * step, d1 = (i + 1) * step;
                    switch (e) {
                        case 0 -> g.fill(a0, d0, a1, d1, col);
                        case 2 -> g.fill(a0, sh - d1, a1, sh - d0, col);
                        case 1 -> g.fill(sw - d1, a0, sw - d0, a1, col);
                        default -> g.fill(d0, a0, d1, a1, col);
                    }
                }
            }
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
    static int mulA(int c, double f) {
        int a = (int) Math.round(((c >>> 24) & 0xFF) * Math.max(0, Math.min(1, f)));
        return (a << 24) | (c & 0xFFFFFF);
    }

    static void lyrics(GuiGraphicsExtractor g, Font font, SpotifyConfig cfg, AlbumArt art, String cur, String next,
                       int x, int y, int boxW, int align, double appear, double flash, double nextFade, double fill) {
        if (!cfg.lyricsFlash) flash = 0;
        if (!cfg.lyricsKaraoke) fill = -1;
        int fillRGB = (cfg.lyricsFillColor < 0 ? (art != null ? art.accent() : 0xFFFFFFFF) : cfg.lyricsFillColor) & 0xFFFFFF;
        int accentRGB = (art != null ? art.accent() : 0xFFFFFFFF) & 0xFFFFFF;
        double sc = cfg.lyricsScale / 100.0;
        int ph = lyricsPillH(cfg);
        int padX = (int) Math.round(5 * sc);
        int max = lyricsMaxPx(cfg, boxW);
        double t = cfg.motion ? System.nanoTime() / 1e9 : 0.0;
        List<String> curLines = wrap(font, cur, max, 3);
        List<String> nextLines = wrap(font, next, max, 2);
        int total = curLines.size() + nextLines.size();
        double curTotalW = 0;
        for (String cl : curLines) curTotalW += font.width(cl);
        double filledPx = fill < 0 ? -1 : fill * curTotalW;
        double offW = 0;
        for (int n = 0; n < total; n++) {
            boolean bright = n < curLines.size();
            String f = bright ? curLines.get(n) : nextLines.get(n - curLines.size());
            int tw = (int) Math.ceil(font.width(f) * sc);
            int w = tw + 2 * padX;
            int px = align == 1 ? x + (boxW - w) / 2 : align == 2 ? x + boxW - w : x;
            int py = y + n * (ph + 1);
            double al = bright ? appear : nextFade;
            if (bright) py += (int) Math.round((1 - appear) * (ph + 1));
            if (al < 0.03) continue;
            int r = cfg.rounded ? 3 : 0;
            if (bright && flash > 0.02) {
                for (int k = 3; k >= 1; k--) {
                    int a = (int) (flash * 46 / k);
                    roundR(g, px - k * 2, py - k * 2, w + k * 4, ph + k * 4, (a << 24) | accentRGB, r + k * 2);
                }
            }
            switch (cfg.lyricsBgMode) {
                case 0 -> roundR(g, px, py, w, ph, mulA(bright ? 0x99000000 : 0x77000000, al), r);
                case 1 -> {
                    int a = bright ? 0xD0 : 0xA0;
                    int col = cfg.lyricsBgColor < 0 ? (art != null ? art.darkAverage(a) : (a << 24) | 0x202020)
                            : (a << 24) | (cfg.lyricsBgColor & 0xFFFFFF);
                    roundR(g, px, py, w, ph, mulA(col, al), r);
                }
                case 2 -> {
                    if (spillRect(g, art, px, py, w, ph, r, t)) roundR(g, px, py, w, ph, bright ? 0x38000000 : 0x60000000, r);
                    else roundR(g, px, py, w, ph, 0x99000000, r);
                }
                default -> {
                }
            }
            int tx = px + padX, ty = py + (int) Math.round(2 * sc);
            if (bright && flash > 0.04) {
                int ha = (int) (flash * 110);
                if (ha >= 6) {
                    int hc = (ha << 24) | accentRGB;
                    Gfx.text(g, font, f, tx - 1, ty, hc, false, sc);
                    Gfx.text(g, font, f, tx + 1, ty, hc, false, sc);
                    Gfx.text(g, font, f, tx, ty - 1, hc, false, sc);
                    Gfx.text(g, font, f, tx, ty + 1, hc, false, sc);
                }
            }
            int ta = (int) Math.round(255 * al);
            if (ta >= 6 && bright && filledPx >= 0) {
                double lw = font.width(f);
                double part = Math.max(0, Math.min(lw, filledPx - offW));
                offW += lw;
                Gfx.text(g, font, f, tx, ty, (ta << 24) | 0xA8A8A8, true, sc);       // not sung yet
                if (part > 0) {
                    int cutX = tx + (int) Math.ceil(part * sc);
                    if (part >= lw - 0.5) {
                        Gfx.text(g, font, f, tx, ty, (ta << 24) | fillRGB, true, sc);
                    } else if (Gfx.scissorOn(g, tx - 1, py - 1, cutX, py + ph + 1)) {
                        Gfx.text(g, font, f, tx, ty, (ta << 24) | fillRGB, true, sc);
                        Gfx.scissorOff(g);
                    }
                }
            } else if (ta >= 6) {
                Gfx.text(g, font, f, tx, ty, (ta << 24) | (bright ? 0xFFFFFF : 0xB8B8B8), bright && al > 0.9, sc);
            }
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
