package dev.spotifyhud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Listens (via a small helper process on Windows) to the audio of the Spotify desktop app, or all PC audio,
 * and turns it into bass / mid / treble / level signals plus beat detection. No audio is recorded or saved:
 * only a few numbers per frame leave the helper.
 */
final class BeatAudio {
    private static final Logger LOG = LoggerFactory.getLogger("spotifyhud");

    // normalised signals 0..1 (written by the reader thread)
    private static volatile double nBass, nMid, nTreble, nLevel;
    private static volatile long beatNs = 0;
    private static volatile boolean alt = false;
    static volatile String status = "Off";
    private static volatile int sensitivity = 50;

    private static Process proc;
    private static Thread reader;
    private static int runMode = -1;
    private static long retryAtMs = 0;

    private BeatAudio() {
    }

    static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static double signal(int src) {
        return switch (src) {
            case 1 -> nBass;
            case 2 -> nMid;
            case 3 -> nTreble;
            case 4 -> nLevel;
            case 5 -> beatNs == 0 ? 0 : Math.exp(-(System.nanoTime() - beatNs) / 1e6 / 170.0);
            case 6 -> alt ? 1.0 : 0.0;
            default -> 0.0;
        };
    }

    /** Called every client tick: starts, restarts or stops the helper to match the settings. */
    static synchronized void update(SpotifyConfig cfg, Path configDir) {
        sensitivity = cfg.beatSensitivity;
        boolean want = cfg.beatEnabled;
        if (!want) {
            stop();
            status = "Off";
            return;
        }
        if (!supported()) {
            status = "Beat link needs Windows";
            return;
        }
        boolean alive = proc != null && proc.isAlive();
        if (alive && runMode == cfg.beatSource) return;
        if (alive) stop();
        long now = System.currentTimeMillis();
        if (now < retryAtMs) return;
        retryAtMs = now + 5000;
        start(cfg.beatSource, configDir);
    }

    static synchronized void shutdown() {
        stop();
    }

    private static void stop() {
        if (proc != null) {
            proc.destroy();
            proc = null;
        }
        runMode = -1;
        nBass = nMid = nTreble = nLevel = 0;
    }

    private static void start(int mode, Path configDir) {
        try {
            Path dir = configDir.resolve("spotifyhud-audio");
            Files.createDirectories(dir);
            for (String f : new String[]{"beat_helper.ps1", "beat_helper.cs"}) {
                try (InputStream in = BeatAudio.class.getResourceAsStream("/assets/spotifyhud/audio/" + f)) {
                    if (in == null) throw new IOException("missing resource " + f);
                    Files.copy(in, dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", dir.resolve("beat_helper.ps1").toString(),
                    "-Mode", String.valueOf(mode), "-ParentPid", String.valueOf(ProcessHandle.current().pid()));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            proc = p;
            runMode = mode;
            status = "Starting audio helper...";
            Thread t = new Thread(() -> read(p), "spotifyhud-beat");
            t.setDaemon(true);
            reader = t;
            t.start();
        } catch (Exception e) {
            LOG.warn("Could not start the beat helper", e);
            status = "Helper failed: " + e.getMessage();
        }
    }

    // ---- reader thread: parse lines, normalise (auto gain), detect beats

    private static void read(Process p) {
        double pb = 3, pm = 1, pt = 0.3, pl = 0.003;          // running peaks (auto gain)
        double ab = 0, am = 0, at = 0, al = 0;                // slow averages (contrast)
        double beatAvg = 0;
        long lastBeat = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) {
                    status = line.substring(1).trim();
                    continue;
                }
                if (!line.startsWith("S ")) continue;
                String[] s = line.split(" ");
                if (s.length < 5) continue;
                double b, m, t, l;
                try {
                    b = Double.parseDouble(s[1]);
                    m = Double.parseDouble(s[2]);
                    t = Double.parseDouble(s[3]);
                    l = Double.parseDouble(s[4]);
                } catch (NumberFormatException e) {
                    continue;
                }
                double gain = 0.6 + sensitivity / 100.0 * 1.4;
                pb = Math.max(Math.max(b, 3.0), pb * 0.9985);
                pm = Math.max(Math.max(m, 1.0), pm * 0.9985);
                pt = Math.max(Math.max(t, 0.3), pt * 0.9985);
                pl = Math.max(Math.max(l, 0.003), pl * 0.9985);
                ab += (b - ab) * 0.02;
                am += (m - am) * 0.02;
                at += (t - at) * 0.02;
                al += (l - al) * 0.02;
                nBass = norm(b, ab, pb, gain);
                nMid = norm(m, am, pm, gain);
                nTreble = norm(t, at, pt, gain);
                nLevel = norm(l, al, pl, gain);

                // beat: a bass onset well above its recent average
                double thr = 1.6 - 0.45 * sensitivity / 100.0;
                long now = System.nanoTime();
                if (b > beatAvg * thr && b > 6.0 && (now - lastBeat) > 260_000_000L) {
                    lastBeat = now;
                    beatNs = now;
                    alt = !alt;
                }
                beatAvg += (b - beatAvg) * 0.06;
            }
        } catch (IOException ignored) {
            // helper ended
        }
        if (proc == p) {
            nBass = nMid = nTreble = nLevel = 0;
            if (!status.startsWith("Helper") && !status.startsWith("Could not") && !status.contains("not running")) {
                status = "Audio helper stopped";
            }
        }
    }

    private static double norm(double v, double avg, double peak, double gain) {
        double lo = avg * 0.8;
        double span = Math.max(peak - lo, 1e-9);
        double n = (v - lo) / span * gain;
        return Math.max(0, Math.min(1, n));
    }
}
