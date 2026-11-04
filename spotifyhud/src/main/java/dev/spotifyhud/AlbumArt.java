package dev.spotifyhud;

import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads cover art once and prepares everything the HUD needs from it:
 * an HD rounded texture, a CD-disc texture, a blurred colour-spill texture, accent colours
 * and a small pixel fallback in case textures can't be created.
 */
final class AlbumArt {
    static final int TEX = 256;       // art + disc texture size
    static final int SPILL = 256;     // spill texture size
    static final int FALLBACK = 64;   // pixel fallback grid

    private static final AtomicInteger COUNTER = new AtomicInteger();

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private final String url;
    private volatile byte[] artPng, discPng, spillPng;
    private volatile int[] pixels;           // FALLBACK x FALLBACK ARGB
    private volatile int average = 0xFF202020;
    private volatile int accent = 0xFFFFFFFF;
    private volatile boolean loaded;

    // render-thread state
    private boolean texTried;
    Identifier artId, discId, spillId;

    AlbumArt(String url) {
        this.url = url == null ? "" : url;
    }

    int[] pixels() {
        return pixels;
    }

    /** Bright, pleasant colour picked from the cover (RGB with full alpha). */
    int accent() {
        return accent;
    }

    /** Dark version of the cover's average colour, with the given alpha. */
    int darkAverage(int alpha) {
        int c = average;
        int r = (int) (((c >> 16) & 0xFF) * 0.38);
        int g = (int) (((c >> 8) & 0xFF) * 0.38);
        int b = (int) ((c & 0xFF) * 0.38);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    /** Creates GPU textures once the image is ready. Render thread only. */
    void ensureTextures() {
        if (!loaded || texTried) return;
        texTried = true;
        int n = COUNTER.incrementAndGet();
        artId = Gfx.registerPng(artPng, "art/" + n + "a");
        discId = Gfx.registerPng(discPng, "art/" + n + "d");
        spillId = Gfx.registerPng(spillPng, "art/" + n + "s");
        artPng = null;
        discPng = null;
        spillPng = null;
    }

    void release() {
        Gfx.release(artId);
        Gfx.release(discId);
        Gfx.release(spillId);
        artId = discId = spillId = null;
    }

    void load(HttpClient http, Executor ex) {
        if (url.isEmpty()) return;
        ex.execute(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
                HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) return;
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(res.body()));
                if (src == null) return;
                build(src);
            } catch (Throwable ignored) {
                // no art is fine; the HUD shows a placeholder
            }
        });
    }

    // ------------------------------------------------------------------ image processing (worker thread)

    private void build(BufferedImage src) throws Exception {
        int[] a = resample(src, TEX);

        // fallback pixels (average 4x4 blocks)
        int f = TEX / FALLBACK;
        int[] fb = new int[FALLBACK * FALLBACK];
        for (int j = 0; j < FALLBACK; j++) {
            for (int i = 0; i < FALLBACK; i++) {
                long r = 0, g = 0, b = 0;
                for (int y = 0; y < f; y++) {
                    for (int x = 0; x < f; x++) {
                        int p = a[(j * f + y) * TEX + i * f + x];
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                    }
                }
                int n = f * f;
                fb[j * FALLBACK + i] = 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
            }
        }

        // 6x6 colour cells -> average, accent, spill
        final int G = 6;
        int cell = TEX / G;
        float[][][] cells = new float[G][G][3];
        double sr = 0, sg = 0, sb = 0;
        double bestScore = -1;
        float[] bestHsb = {0, 0.5f, 1f};
        for (int cj = 0; cj < G; cj++) {
            for (int ci = 0; ci < G; ci++) {
                long r = 0, g = 0, b = 0;
                for (int y = 0; y < cell; y++) {
                    for (int x = 0; x < cell; x++) {
                        int p = a[(cj * cell + y) * TEX + ci * cell + x];
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                    }
                }
                int n = cell * cell;
                float rr = r / (float) n, gg = g / (float) n, bb = b / (float) n;
                float[] hsb = Color.RGBtoHSB((int) rr, (int) gg, (int) bb, null);
                hsb[1] = Math.min(1f, hsb[1] * 1.35f); // richer colour for the spill
                int boosted = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
                cells[cj][ci][0] = (boosted >> 16) & 0xFF;
                cells[cj][ci][1] = (boosted >> 8) & 0xFF;
                cells[cj][ci][2] = boosted & 0xFF;
                sr += rr;
                sg += gg;
                sb += bb;
                double score = hsb[1] * (0.4 + hsb[2]);
                if (score > bestScore) {
                    bestScore = score;
                    bestHsb = hsb;
                }
            }
        }
        int tot = G * G;
        average = 0xFF000000 | ((int) (sr / tot) << 16) | ((int) (sg / tot) << 8) | (int) (sb / tot);
        accent = Color.HSBtoRGB(bestHsb[0], Math.min(bestHsb[1], 0.65f), 1f) | 0xFF000000;

        byte[] artBytes = png(roundedArt(a));
        byte[] discBytes = png(disc(a));
        byte[] spillBytes = png(spill(cells, G));
        artPng = artBytes;
        discPng = discBytes;
        spillPng = spillBytes;
        pixels = fb;
        loaded = true;
    }

    /** Centre-crops to a square and box-filters down to size x size (opaque ARGB). */
    private static int[] resample(BufferedImage src, int size) {
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        int ox = (w - side) / 2;
        int oy = (h - side) / 2;
        int[] out = new int[size * size];
        for (int j = 0; j < size; j++) {
            int y0 = oy + (int) ((long) j * side / size);
            int y1 = Math.max(y0 + 1, oy + (int) ((long) (j + 1) * side / size));
            for (int i = 0; i < size; i++) {
                int x0 = ox + (int) ((long) i * side / size);
                int x1 = Math.max(x0 + 1, ox + (int) ((long) (i + 1) * side / size));
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int p = src.getRGB(xx, yy);
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                        n++;
                    }
                }
                out[j * size + i] = 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
            }
        }
        return out;
    }

    private static int[] roundedArt(int[] a) {
        int[] o = a.clone();
        double rad = TEX * 0.09;
        for (int y = 0; y < TEX; y++) {
            for (int x = 0; x < TEX; x++) {
                double dx = Math.max(0, Math.max(rad - (x + 0.5), (x + 0.5) - (TEX - rad)));
                double dy = Math.max(0, Math.max(rad - (y + 0.5), (y + 0.5) - (TEX - rad)));
                double d = Math.sqrt(dx * dx + dy * dy);
                double cover = clamp01(rad + 0.5 - d);
                if (cover < 1) o[y * TEX + x] = withAlpha(o[y * TEX + x], cover);
            }
        }
        return o;
    }

    /** Circular CD: antialiased edge, transparent centre hole with a darker ring around it. */
    private static int[] disc(int[] a) {
        int[] o = new int[TEX * TEX];
        double c = TEX / 2.0;
        double R = TEX / 2.0;
        double hole = TEX * 0.07;
        double ring = TEX * 0.15;
        for (int y = 0; y < TEX; y++) {
            for (int x = 0; x < TEX; x++) {
                double dx = x + 0.5 - c;
                double dy = y + 0.5 - c;
                double r = Math.sqrt(dx * dx + dy * dy);
                double cover = clamp01(R - r) * clamp01(r - hole + 0.5);
                if (cover <= 0) continue;
                int p = a[y * TEX + x];
                if (r < ring) p = mix(p, 0xFF101010, 0.55 * clamp01((ring - r) / 3.0));
                if (r > R - 4) p = mix(p, 0xFF000000, 0.3 * clamp01((r - (R - 4)) / 4.0));
                o[y * TEX + x] = withAlpha(p, cover);
            }
        }
        return o;
    }

    /** Soft, warped, blotchy blend of the cover's colours (like a blurred colour spill). */
    private static int[] spill(float[][][] cells, int G) {
        int[] o = new int[SPILL * SPILL];
        double tau = Math.PI * 2;
        for (int py = 0; py < SPILL; py++) {
            double v = py / (SPILL - 1.0);
            for (int px = 0; px < SPILL; px++) {
                double u = px / (SPILL - 1.0);
                double wu = u + 0.11 * Math.sin(tau * (v * 1.1) + 0.7) + 0.06 * Math.sin(tau * (v * 2.3) + 2.1);
                double wv = v + 0.11 * Math.sin(tau * (u * 1.2) + 1.9) + 0.06 * Math.sin(tau * (u * 2.1) + 0.3);
                double gx = clamp01(wu) * (G - 1);
                double gy = clamp01(wv) * (G - 1);
                int ix = Math.min(G - 2, (int) gx);
                int iy = Math.min(G - 2, (int) gy);
                double fx = smooth(gx - ix);
                double fy = smooth(gy - iy);
                float[] c00 = cells[iy][ix], c10 = cells[iy][ix + 1], c01 = cells[iy + 1][ix], c11 = cells[iy + 1][ix + 1];
                double shade = 1.0 + 0.10 * Math.sin(tau * (u * 1.7 + v * 1.3) + 1.0);
                int[] ch = new int[3];
                for (int k = 0; k < 3; k++) {
                    double top = c00[k] + (c10[k] - c00[k]) * fx;
                    double bot = c01[k] + (c11[k] - c01[k]) * fx;
                    ch[k] = (int) Math.max(0, Math.min(255, (top + (bot - top) * fy) * shade));
                }
                o[py * SPILL + px] = 0xFF000000 | (ch[0] << 16) | (ch[1] << 8) | ch[2];
            }
        }
        return o;
    }

    private static byte[] png(int[] argb) throws Exception {
        int size = (int) Math.round(Math.sqrt(argb.length));
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, size, size, argb, 0, size);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static int withAlpha(int argb, double cover) {
        return ((int) Math.round(255 * cover) << 24) | (argb & 0xFFFFFF);
    }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
