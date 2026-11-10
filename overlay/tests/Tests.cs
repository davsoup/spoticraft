using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;

namespace SpotiCraftOverlay
{
    /// <summary>Logic tests + offscreen renders. Runs under Mono on Linux or .NET on Windows: Tests.exe [outDir]</summary>
    static class Tests
    {
        static int failed, passed;

        static void Check(bool ok, string what)
        {
            if (ok) passed++;
            else { failed++; Console.WriteLine("FAIL: " + what); }
        }

        static int Main(string[] args)
        {
            string outDir = args.Length > 0 ? args[0] : ".";
            Directory.CreateDirectory(outDir);
            JsonTests();
            ConfigTests();
            PlaybackTests();
            HotkeyTests();
            ModTests();
            BeatTests();
            Render(outDir);
            Console.WriteLine(passed + " passed, " + failed + " failed");
            return failed == 0 ? 0 : 1;
        }

        static void JsonTests()
        {
            var o = Json.Parse("{\"a\": 1.5, \"b\": [true, false, null, \"x\\\"y\\u00e9\"], \"c\": {\"d\": -2e3}}");
            Check(Math.Abs((double)((Dictionary<string, object>)o)["a"] - 1.5) < 1e-9, "json number");
            Check(Json.Arr(o, "b").Count == 4, "json array");
            Check((string)Json.Arr(o, "b")[3] == "x\"yé", "json escapes");
            Check(Json.Lng(Json.Obj(o, "c"), "d") == -2000, "json exponent");
            string w = Json.Write(o);
            var back = Json.Parse(w);
            Check(Json.Write(back) == w, "json roundtrip stable");
            bool threw = false;
            try { Json.Parse("{\"a\":}"); } catch (FormatException) { threw = true; }
            Check(threw, "json rejects bad input");
        }

        static void ConfigTests()
        {
            var cfg = new OverlayConfig();
            cfg.glowSpread = 99; // out of range
            cfg.links.Add(new Link { param = "glowStrength", src = 5, amount = -30 });
            cfg.Clamp();
            Check(cfg.glowSpread == 40, "clamp glowSpread");
            // round-trip through the same JSON the app writes
            string tmp = Path.Combine(Path.GetTempPath(), "spoticraft-test-" + Guid.NewGuid().ToString("N"));
            Environment.SetEnvironmentVariable("SPOTICRAFT_APPDATA", tmp);
            string note;
            OverlayConfig loaded = OverlayConfig.Load(out note); // writes defaults
            Check(File.Exists(OverlayConfig.FilePath), "config written on first load: " + OverlayConfig.FilePath);
            loaded.hudX = 321;
            loaded.titleScale = 1.25f;
            loaded.links.Add(new Link { param = "barColor", src = 3, amount = 70, mode = 2 });
            loaded.Save();
            OverlayConfig again = OverlayConfig.Load(out note);
            Check(again.hudX == 321 && Math.Abs(again.titleScale - 1.25f) < 1e-4, "config fields survive");
            Check(again.links.Count == 1 && again.links[0].param == "barColor" && again.links[0].mode == 2, "links survive");

            // first-run import from the mod's config
            string tmp2 = Path.Combine(Path.GetTempPath(), "spoticraft-test-" + Guid.NewGuid().ToString("N"));
            Environment.SetEnvironmentVariable("SPOTICRAFT_APPDATA", tmp2);
            Directory.CreateDirectory(Path.GetDirectoryName(OverlayConfig.ModConfigPath));
            File.WriteAllText(OverlayConfig.ModConfigPath,
                "{\"clientId\":\"abc123\",\"hudX\":8,\"hudY\":40,\"style\":2,\"glowColor\":16711680," +
                "\"links\":[{\"param\":\"glowSpread\",\"src\":1,\"amount\":50,\"smooth\":40,\"gate\":0,\"curve\":0,\"mode\":0}]}");
            OverlayConfig imp = OverlayConfig.Load(out note);
            Check(imp.clientId == "abc123" && imp.style == 2 && imp.glowColor == 0xFF0000, "mod import: values");
            Check(imp.hudX == new OverlayConfig().hudX, "mod import: GUI positions not copied");
            Check(imp.links.Count == 1, "mod import: links");
            Check(note.Contains("Imported"), "mod import note");
            try { Directory.Delete(tmp, true); Directory.Delete(tmp2, true); } catch (Exception) { }
        }

        static void PlaybackTests()
        {
            string body = "{\"device\":{\"volume_percent\":42},\"shuffle_state\":true,\"repeat_state\":\"track\"," +
                "\"progress_ms\":61000,\"is_playing\":true,\"item\":{\"name\":\"Song\",\"duration_ms\":200000," +
                "\"artists\":[{\"name\":\"A\"},{\"name\":\"B\"}],\"album\":{\"name\":\"Alb\",\"images\":[" +
                "{\"url\":\"big\",\"width\":640},{\"url\":\"mid\",\"width\":300},{\"url\":\"small\",\"width\":64}]}}," +
                "\"currently_playing_type\":\"track\"}";
            long now = PlaybackState.Now();
            PlaybackState s = SpotifyApi.ParsePlayback(Json.Parse(body), now);
            Check(s.Active && s.Playing && s.Title == "Song" && s.Artist == "A, B" && s.Album == "Alb", "playback fields");
            Check(s.ArtUrl == "mid", "picks smallest image >= 200px");
            Check(s.VolumeSupported && s.VolumePercent == 42 && s.Shuffle && s.Repeat == 2, "volume/shuffle/repeat");
            Check(s.LiveProgressMs() >= 61000 && s.LiveProgressMs() < 63000, "live progress");
            var ep = SpotifyApi.ParsePlayback(Json.Parse(
                "{\"is_playing\":false,\"item\":{\"name\":\"Ep\",\"duration_ms\":5,\"show\":{\"name\":\"Pod\",\"images\":[{\"url\":\"p\",\"width\":300}]}}}"), now);
            Check(ep.Artist == "Pod" && ep.ArtUrl == "p" && !ep.VolumeSupported, "episode fallback");
            var ad = SpotifyApi.ParsePlayback(Json.Parse("{\"currently_playing_type\":\"ad\"}"), now);
            Check(ad.Title == "Advertisement", "ad");
            Check(s.WithVolume(10).VolumePercent == 10 && s.WithPlaying(false).Playing == false, "With*");
        }

        static void HotkeyTests()
        {
            uint m, vk;
            Check(OverlayForm.ParseHotkey("P", out m, out vk) && m == 0 && vk == 0x50, "hotkey P");
            Check(OverlayForm.ParseHotkey("ctrl+shift+F9", out m, out vk) && m == (Native.MOD_CONTROL | Native.MOD_SHIFT) && vk == 0x78, "hotkey combo");
            Check(OverlayForm.ParseHotkey("Alt+5", out m, out vk) && m == Native.MOD_ALT && vk == 0x35, "hotkey digit");
            Check(!OverlayForm.ParseHotkey("Ctrl+", out m, out vk) && !OverlayForm.ParseHotkey("Blorp", out m, out vk), "hotkey invalid");
        }

        static void ModTests()
        {
            Check(Mod.Defs.Count == 64, "all mod targets registered (" + Mod.Defs.Count + ")");
            var cfg = new OverlayConfig { beatEnabled = true, glowStrength = 40 };
            cfg.links.Add(new Link { param = "glowStrength", src = 5, amount = 40, smooth = 0 });
            cfg.links.Add(new Link { param = "textColor", src = 5, amount = 100, smooth = 0, mode = 4 });
            cfg.links.Add(new Link { param = "nonsense", src = 1, amount = 100 });
            OverlayConfig quiet = Mod.Apply(cfg, null);
            Check(quiet != cfg && quiet.glowStrength == 40, "no signal -> base value");
            // fire a beat: loud bass onset after quiet frames
            for (int i = 0; i < 30; i++) BeatAudio.FeedForTest(2, 1, 0.3, 0.01);
            BeatAudio.FeedForTest(60, 5, 1, 0.3);
            Check(BeatAudio.Signal(5) > 0.9, "beat signal fires");
            OverlayConfig loud = null;
            for (int i = 0; i < 4; i++) { System.Threading.Thread.Sleep(16); loud = Mod.Apply(cfg, null); }
            Check(loud.glowStrength > 60, "beat pumps glowStrength (" + loud.glowStrength + ")");
            Check(cfg.glowStrength == 40, "saved config untouched");
            Check((loud.textColor & 0xFFFFFF) == 0xFFFFFF, "flash white keeps white");
            cfg.beatEnabled = false;
            Check(Mod.Apply(cfg, null) == cfg, "disabled -> same object");
            float[] hsb = Colors.RgbToHsb(255, 0, 0);
            Check((Colors.HsbToRgb(hsb[0], hsb[1], hsb[2]) & 0xFFFFFF) == 0xFF0000, "hsb roundtrip");
        }

        static void BeatTests()
        {
            // FFT sanity: a 100 Hz sine lands in the bass band, not treble
            int n = 2048, rate = 44100;
            var re = new double[n];
            var im = new double[n];
            for (int i = 0; i < n; i++) re[i] = Math.Sin(2 * Math.PI * 100 * i / rate);
            BeatCapture.Fft(re, im);
            double bass = BeatCapture.Band(re, im, rate, n, 20, 150);
            double treble = BeatCapture.Band(re, im, rate, n, 2000, 16000);
            Check(bass > 20 * treble, "fft bass vs treble");
        }

        // ------------------------------------------------------------ offscreen renders

        static Bitmap FakeCover()
        {
            var bmp = new Bitmap(300, 300);
            using (var g = Graphics.FromImage(bmp))
            {
                using (var lg = new LinearGradientBrush(new Rectangle(0, 0, 300, 300), Color.FromArgb(120, 20, 60), Color.FromArgb(20, 40, 110), 45f))
                    g.FillRectangle(lg, 0, 0, 300, 300);
                using (var b = new SolidBrush(Color.FromArgb(240, 120, 40))) g.FillEllipse(b, 90, 70, 130, 130);
                using (var b = new SolidBrush(Color.FromArgb(10, 10, 10))) g.FillRectangle(b, 0, 230, 300, 70);
            }
            return bmp;
        }

        static void Render(string outDir)
        {
            var art = new AlbumArt("");
            using (Bitmap cover = FakeCover()) art.Build(cover);
            Check(art.Loaded && art.Art != null && art.Spill != null, "art built");
            Check((art.Accent & 0xFFFFFF) != 0xFFFFFF, "accent picked from cover");
            Check(art.Palette.Length == 20, "palette");

            long now = PlaybackState.Now();
            var s = new PlaybackState(true, true, "Midnight Exposure (Extended Mix)", "DAVSOUP, Baby Jane",
                "Night Roll", "", 83000, 214000, 64, true, true, 1, now);
            var paused = new PlaybackState(true, false, "Short Title", "Artist", "Alb", "", 30000, 180000, 50, true, false, 0, now);

            var hud = new Hud();
            int W = 900, H = 640;
            using (var bmp = new Bitmap(W, H, PixelFormat.Format32bppPArgb))
            using (var g = Graphics.FromImage(bmp))
            {
                g.Clear(Color.FromArgb(255, 34, 38, 46)); // stand-in desktop
                hud.Begin(g, 2f);
                var cfg = new OverlayConfig { hudX = 40, hudY = 40 };
                hud.Render(cfg, s, art, false, -1, null, null, 1, 1);
                Rectangle b1 = hud.Bounds;
                Check(!b1.IsEmpty && b1.Width > 448 && b1.Height > 140, "bounds cover card + glow: " + b1);

                var cfg2 = new OverlayConfig { hudX = 40, hudY = 300, bgMode = 1, rounded = false, glowColor = 0x1DB954, titleScale = 1f };
                hud.Render(cfg2, paused, null, true, -1, null, null, 1, 1);

                var cfg3 = new OverlayConfig { hudX = 560, hudY = 300, hudScale = 0.75f, bgMode = 0, autoFit = true, alignBlock = 1 };
                hud.Render(cfg3, s, art, false, -1, null, null, 1, 1);

                hud.ResetUnits();
                hud.Message(20, 240, "SpotiCraft Overlay", "No Spotify clientId yet", "Press P to set it up");
                hud.Status("No active Spotify device - press play in Spotify first", 280, 240);
                hud.VolumeRow(280, 260, 200, 64);
                hud.Toggle("Beat glow: On", 280, 280, 97, 14, false, true);
                hud.Button("Listen: Spotify", 383, 280, 97, 14, true);
                hud.Handle(20, 20);
                bmp.Save(Path.Combine(outDir, "render_cards.png"), ImageFormat.Png);
            }

            // transition mid-slide + glow pumped by a link
            using (var bmp = new Bitmap(600, 260, PixelFormat.Format32bppPArgb))
            using (var g = Graphics.FromImage(bmp))
            {
                g.Clear(Color.FromArgb(255, 20, 20, 24));
                hud.Begin(g, 2f);
                var cfg = new OverlayConfig { hudX = 60, hudY = 50, glowStrength = 100, glowSpread = 30, glowColor = 0xFF3366 };
                hud.Render(cfg, s, art, false, -1, paused, null, 0.45, 1);
                bmp.Save(Path.Combine(outDir, "render_transition.png"), ImageFormat.Png);
            }

            // speed: one full frame of the default card
            var sw = System.Diagnostics.Stopwatch.StartNew();
            using (var bmp = new Bitmap(900, 400, PixelFormat.Format32bppPArgb))
            using (var g = Graphics.FromImage(bmp))
            {
                hud.Begin(g, 2f);
                var cfg = new OverlayConfig();
                for (int i = 0; i < 60; i++) { hud.Begin(g, 2f); hud.Render(cfg, s, art, false, -1, null, null, 1, 1); }
            }
            Console.WriteLine("avg frame (mono/libgdiplus, 60 frames): " + (sw.ElapsedMilliseconds / 60.0).ToString("0.0") + " ms");
        }
    }
}
