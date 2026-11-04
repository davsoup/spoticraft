package dev.spotifyhud;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Downloads cover art and shrinks it to a small pixel grid that the HUD paints with plain fills.
 * No Minecraft texture APIs involved, so nothing here can break on a game update.
 */
final class AlbumArt {
    static final int SIZE = 54;

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private final String url;
    private volatile int[] pixels; // ARGB, SIZE*SIZE, null until loaded
    private volatile int average = 0xFF202020;

    AlbumArt(String url) {
        this.url = url == null ? "" : url;
    }

    int[] pixels() {
        return pixels;
    }

    /** Panel background derived from the cover's average colour (darkened, slightly translucent). */
    int panelColor() {
        int c = average;
        int r = (int) (((c >> 16) & 0xFF) * 0.40);
        int g = (int) (((c >> 8) & 0xFF) * 0.40);
        int b = (int) ((c & 0xFF) * 0.40);
        return 0xE0000000 | (r << 16) | (g << 8) | b;
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
                int w = src.getWidth();
                int h = src.getHeight();
                int side = Math.min(w, h);
                int ox = (w - side) / 2;
                int oy = (h - side) / 2;
                int[] out = new int[SIZE * SIZE];
                long sr = 0, sg = 0, sb = 0;
                for (int j = 0; j < SIZE; j++) {
                    int y0 = oy + j * side / SIZE;
                    int y1 = Math.max(y0 + 1, oy + (j + 1) * side / SIZE);
                    for (int i = 0; i < SIZE; i++) {
                        int x0 = ox + i * side / SIZE;
                        int x1 = Math.max(x0 + 1, ox + (i + 1) * side / SIZE);
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
                        int rr = (int) (r / n), gg = (int) (g / n), bb = (int) (b / n);
                        out[j * SIZE + i] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
                        sr += rr;
                        sg += gg;
                        sb += bb;
                    }
                }
                int total = SIZE * SIZE;
                average = 0xFF000000 | ((int) (sr / total) << 16) | ((int) (sg / total) << 8) | (int) (sb / total);
                pixels = out;
            } catch (Throwable ignored) {
                // no art is fine; the HUD shows a placeholder
            }
        });
    }
}
