package dev.spotifyhud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Layout, drawing and hit-testing for the Apple-Music-style now-playing card. */
final class HudPanel {
    static final int W = 200, H = 70, PAD = 8, ART = 54, GAP = 8;
    static final int TEXT_OFF = PAD + ART + GAP;       // left edge of the text column
    static final int COL = W - TEXT_OFF - PAD;         // width of the text column
    static final int BAR_Y = 33, BAR_H = 4;
    static final int VOL_Y = 57, VOL_LABEL = 20, VOL_PCT = 26;
    static final int VOL_X_OFF = TEXT_OFF + VOL_LABEL;
    static final int VOL_W = COL - VOL_LABEL - VOL_PCT;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int SOFT = 0xFFC8C8C8;
    private static final int DIM = 0xFF9A9A9A;
    private static final int TRACK = 0x44FFFFFF;
    private static final int RED = 0xFFFF5555;

    private HudPanel() {
    }

    // ------------------------------------------------------------ drawing

    /** progOv: progress override in ms (-1 = none). volOv: volume override percent (-1 = none). */
    static void draw(GuiGraphicsExtractor g, Font font, PlaybackState s, AlbumArt art, int x, int y,
                     boolean interactive, long progOv, int volOv) {
        round(g, x, y, W, H, art != null ? art.panelColor() : 0xE0202020);
        drawArt(g, font, art, x + PAD, y + PAD);

        int tx = x + TEXT_OFF;
        g.text(font, fit(font, s.title().isEmpty() ? "Unknown" : s.title(), COL), tx, y + 8, WHITE, true);
        g.text(font, fit(font, s.artist(), COL), tx, y + 19, SOFT, false);

        long progress = progOv >= 0 ? progOv : s.liveProgressMs();
        double frac = s.durationMs() > 0 ? Math.min(1.0, (double) progress / s.durationMs()) : 0.0;
        bar(g, tx, y + BAR_Y, COL, BAR_H, frac, interactive);

        int rowY = y + BAR_Y + 7;
        String left = fmt(progress);
        String right = fmt(s.durationMs());
        g.text(font, left, tx, rowY, DIM, false);
        g.text(font, right, tx + COL - font.width(right), rowY, DIM, false);
        if (!s.playing()) {
            String p = "Paused";
            g.text(font, p, tx + (COL - font.width(p)) / 2, rowY, WHITE, false);
        }

        if (interactive && s.volumeSupported()) {
            int vol = volOv >= 0 ? volOv : s.volumePercent();
            g.text(font, "Vol", tx, y + VOL_Y - 2, DIM, false);
            bar(g, x + VOL_X_OFF, y + VOL_Y, VOL_W, BAR_H, vol / 100.0, interactive);
            String pct = vol + "%";
            g.text(font, pct, tx + COL - font.width(pct), y + VOL_Y - 2, SOFT, false);
        }
    }

    /** Small text-only card used for setup / idle messages. Returns its bottom y. */
    static int message(GuiGraphicsExtractor g, Font font, int x, int y, String... lines) {
        int h = 10 + lines.length * 11;
        round(g, x, y, W, h, 0xE0202020);
        for (int i = 0; i < lines.length; i++) {
            g.text(font, fit(font, lines[i], W - 16), x + 8, y + 6 + i * 11, i == 0 ? WHITE : SOFT, i == 0);
        }
        return y + h;
    }

    static void status(GuiGraphicsExtractor g, Font font, String text, int x, int y) {
        g.text(font, fit(font, text, W + 60), x, y, RED, true);
    }

    /** Two lyric lines (current bright, next dim) on translucent pills. Height is 27. */
    static void lyrics(GuiGraphicsExtractor g, Font font, String cur, String next, int x, int y) {
        if (!cur.isEmpty()) {
            String c = fit(font, cur, W + 60);
            round(g, x, y, font.width(c) + 10, 13, 0x99000000);
            g.text(font, c, x + 5, y + 2, WHITE, true);
        }
        if (!next.isEmpty()) {
            String n = fit(font, next, W + 60);
            round(g, x, y + 14, font.width(n) + 10, 13, 0x77000000);
            g.text(font, n, x + 5, y + 16, DIM, false);
        }
    }

    static void button(GuiGraphicsExtractor g, Font font, String label, int x, int y, int w, int h, boolean hover) {
        round(g, x, y, w, h, hover ? 0xE0606060 : 0xB0000000);
        g.text(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, WHITE, false);
    }

    private static void drawArt(GuiGraphicsExtractor g, Font font, AlbumArt art, int x, int y) {
        int[] px = art == null ? null : art.pixels();
        if (px == null) {
            round(g, x, y, ART, ART, 0xFF3A3A3A);
            String note = "♪";
            g.text(font, note, x + (ART - font.width(note)) / 2, y + ART / 2 - 4, DIM, false);
            return;
        }
        int n = AlbumArt.SIZE;
        for (int j = 0; j < n; j++) {
            int i = 0;
            while (i < n) {
                if (corner(i, j, n)) {
                    i++;
                    continue;
                }
                int c = px[j * n + i];
                int k = i + 1;
                while (k < n && !corner(k, j, n) && px[j * n + k] == c) k++;
                g.fill(x + i, y + j, x + k, y + j + 1, c);
                i = k;
            }
        }
    }

    private static boolean corner(int i, int j, int n) {
        return Math.min(i, n - 1 - i) + Math.min(j, n - 1 - j) < 2;
    }

    private static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, double frac, boolean knob) {
        frac = Math.max(0, Math.min(1, frac));
        int fw = (int) (w * frac);
        g.fill(x, y, x + w, y + h, TRACK);
        if (fw > 0) g.fill(x, y, x + fw, y + h, WHITE);
        if (knob) {
            int kx = Math.max(x + 2, Math.min(x + w - 2, x + fw));
            g.fill(kx - 2, y - 2, kx + 2, y + h + 2, WHITE);
        }
    }

    /** Rectangle with softly rounded corners. */
    static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x + 2, y, x + w - 2, y + h, color);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, color);
        g.fill(x, y + 2, x + w, y + h - 2, color);
    }

    // ------------------------------------------------------------ hit testing

    static boolean inBar(double mx, double my, int x, int y) {
        return mx >= x + TEXT_OFF - 3 && mx <= x + TEXT_OFF + COL + 3
                && my >= y + BAR_Y - 5 && my <= y + BAR_Y + BAR_H + 5;
    }

    static boolean inVol(double mx, double my, int x, int y) {
        return mx >= x + VOL_X_OFF - 3 && mx <= x + VOL_X_OFF + VOL_W + 3
                && my >= y + VOL_Y - 5 && my <= y + VOL_Y + BAR_H + 5;
    }

    static double barFrac(double mx, int x) {
        return Math.max(0, Math.min(1, (mx - (x + TEXT_OFF)) / COL));
    }

    static double volFrac(double mx, int x) {
        return Math.max(0, Math.min(1, (mx - (x + VOL_X_OFF)) / VOL_W));
    }

    // ------------------------------------------------------------ text helpers

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
