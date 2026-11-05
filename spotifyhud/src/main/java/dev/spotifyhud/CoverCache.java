package dev.spotifyhud;

import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** Small cover thumbnails (queue peek). Download on a worker thread, register textures on the render thread. */
final class CoverCache {
    static final int SIZE = 64;
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final class Entry {
        volatile byte[] png;
        volatile boolean failed;
        Identifier id;
        boolean registered;
    }

    private final Map<String, Entry> map = new ConcurrentHashMap<>();
    private final HttpClient http;
    private final Executor ex;

    CoverCache(HttpClient http, Executor ex) {
        this.http = http;
        this.ex = ex;
    }

    /** Render thread. Returns the texture, or null while it is still loading. */
    Identifier get(String url) {
        if (url == null || url.isEmpty()) return null;
        Entry e = map.get(url);
        if (e == null) {
            Entry fresh = new Entry();
            if (map.putIfAbsent(url, fresh) == null) {
                e = fresh;
                ex.execute(() -> fetch(url, fresh));
            } else {
                e = map.get(url);
            }
        }
        if (e == null) return null;
        if (!e.registered && e.png != null) {
            e.registered = true;
            e.id = Gfx.registerPng(e.png, "cover/" + COUNTER.incrementAndGet());
            e.png = null;
        }
        return e.id;
    }

    /** Render thread. Frees thumbnails that are no longer in the list. */
    void retain(Set<String> keep) {
        Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> me = it.next();
            if (keep.contains(me.getKey())) continue;
            Entry e = me.getValue();
            if (e.png == null && !e.registered && !e.failed) continue; // still downloading
            Gfx.release(e.id);
            it.remove();
        }
    }

    private void fetch(String url, Entry e) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                e.failed = true;
                return;
            }
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(res.body()));
            if (src == null) {
                e.failed = true;
                return;
            }
            int[] px = AlbumArt.resample(src, SIZE);
            BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, SIZE, SIZE, px, 0, SIZE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            e.png = out.toByteArray();
        } catch (Throwable t) {
            e.failed = true;
        }
    }
}
