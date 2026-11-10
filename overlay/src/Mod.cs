using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Reflection;

namespace SpotiCraftOverlay
{
    /// <summary>
    /// The modulation engine (port of the mod's Mod.java): every customization setting is a target, and any music
    /// signal (bass, mid, treble, level, beat, alternate) can be wired to it with amount, smoothing, gate and curve.
    /// The HUD draws from a modulated copy of the config, so saved settings are never changed.
    /// </summary>
    public static class Mod
    {
        public enum K { Int, Float, Bool, Enum, Color }

        public sealed class Def
        {
            public readonly string Id, Label;
            public readonly K Kind;
            public readonly double Lo, Hi, Range;
            public readonly int N;
            internal FieldInfo Field;

            public Def(string id, string label, K kind, double lo, double hi, double range, int n)
            {
                Id = id; Label = label; Kind = kind; Lo = lo; Hi = hi; Range = range; N = n;
            }
        }

        public static readonly string[] Sources = { "None", "Bass", "Mid", "Treble", "Level", "Beat", "Alternate" };
        public static readonly string[] Curves = { "Linear", "Punchy", "Soft" };
        public static readonly string[] ColorModes = { "Hue shift", "Brightness", "Cycle colors", "Cycle album", "Flash white", "Flash album" };
        static readonly int[] Cycle = { 0xFF4D4D, 0xFF9F43, 0xFFE066, 0x5EE08A, 0x4DE1E8, 0x5B8CFF, 0xA66BFF, 0xFF6BD6, 0xFFFFFF };

        public static readonly List<Def> Defs = new List<Def>();
        static readonly Dictionary<string, Def> ById = new Dictionary<string, Def>();

        static void Num(string id, string label, double lo, double hi) { Add(new Def(id, label, K.Int, lo, hi, hi - lo, 0)); }
        static void Flt(string id, string label, double lo, double hi) { Add(new Def(id, label, K.Float, lo, hi, hi - lo, 0)); }
        static void Pos(string id, string label) { Add(new Def(id, label, K.Int, -16000, 16000, 200, 0)); }
        static void Bl(string id, string label) { Add(new Def(id, label, K.Bool, 0, 1, 1, 0)); }
        static void En(string id, string label, int n) { Add(new Def(id, label, K.Enum, 0, n - 1, n, n)); }
        static void Col(string id, string label) { Add(new Def(id, label, K.Color, 0, 0, 1, 0)); }

        static void Add(Def d)
        {
            d.Field = typeof(OverlayConfig).GetField(d.Id);
            if (d.Field == null) throw new InvalidOperationException("Mod target without field: " + d.Id);
            Defs.Add(d);
            ById[d.Id] = d;
        }

        static Mod()
        {
            // general
            En("style", "Style", 3);
            Bl("showHud", "HUD");
            Bl("showLyrics", "Lyrics");
            Bl("motion", "Motion");
            Bl("rounded", "Round corners");
            Flt("titleScale", "Title size", 1.0, 2.0);
            Bl("autoFit", "Auto-fit text");
            Bl("modeIndicators", "Shuffle/repeat badge");
            Pos("hudX", "HUD position X");
            Pos("hudY", "HUD position Y");
            // block
            En("alignBlock", "Block text align", 3);
            En("bgMode", "Block background", 3);
            Num("bgDarkness", "Block darkness", 0, 80);
            Num("artOffX", "Cover X", -20, 20);
            Num("artOffY", "Cover Y", -20, 20);
            Flt("hudScale", "Block size", 0.5, 3.0);
            // cd
            Bl("cdText", "CD text");
            En("alignCd", "CD text align", 3);
            En("cdBgMode", "CD text box", 3);
            Num("cdDarkness", "CD darkness", 0, 80);
            Num("cdSize", "CD size", 40, 220);
            // vinyl
            Bl("vinylText", "Vinyl text");
            En("alignVinyl", "Vinyl text align", 3);
            En("vinylBgMode", "Vinyl text box", 3);
            Num("vinylDarkness", "Vinyl darkness", 0, 80);
            Num("vinylSize", "Record size", 40, 220);
            Bl("vinylTint", "Record tint");
            Bl("vinylArm", "Tonearm");
            Col("vinylArmColor", "Tonearm colour");
            // lyrics
            En("alignLyrics", "Lyrics align", 3);
            Num("lyricsScale", "Lyrics size", 70, 200);
            Num("lyricsOffset", "Lyrics distance", -30, 120);
            En("lyricsBgMode", "Lyrics background", 4);
            Col("lyricsBgColor", "Lyrics bg colour");
            Bl("lyricsFlash", "Line flash");
            Bl("lyricsKaraoke", "Karaoke fill");
            Col("lyricsFillColor", "Lyric fill colour");
            // queue
            Bl("queuePeek", "Queue peek");
            Num("queuePeekCount", "Queue songs", 1, 5);
            Flt("queuePeekScale", "Queue box size", 0.5, 3.0);
            Num("queuePeekText", "Queue text size", 70, 200);
            En("queuePeekAlign", "Queue text align", 3);
            Bl("queuePeekCovers", "Queue covers");
            En("queuePeekBg", "Queue background", 3);
            Num("queuePeekDarkness", "Queue darkness", 0, 80);
            Col("queuePeekColor", "Queue text colour");
            En("queuePeekSide", "Queue side", 3);
            Pos("queuePeekX", "Queue position X");
            Pos("queuePeekY", "Queue position Y");
            // ambient
            Bl("ambient", "Ambient glow");
            Bl("ambientMograph", "Ambient mograph");
            En("ambientEdges", "Ambient edges", 3);
            Num("ambientSize", "Ambient size", 20, 200);
            Num("ambientIntensity", "Ambient intensity", 0, 100);
            Num("ambientSpeed", "Ambient speed", 1, 10);
            Col("ambientColor", "Ambient colour");
            // glow
            Bl("glow", "Glow");
            Num("glowStrength", "Glow intensity", 0, 100);
            Num("glowSpread", "Glow spread", 4, 40);
            Num("glowSoftness", "Glow softness", 0, 100);
            Col("glowColor", "Glow colour");
            // colours
            Col("textColor", "Text colour");
            Col("barColor", "Bar colour");
            Col("bgColor", "Solid bg colour");
        }

        public static Def Get(string id)
        {
            Def d;
            return ById.TryGetValue(id, out d) ? d : null;
        }

        public static Link Find(OverlayConfig cfg, string id)
        {
            foreach (Link l in cfg.links) if (l.param == id && l.src > 0) return l;
            return null;
        }

        /// <summary>Returns the config to draw with: the saved one, or a copy with all beat links applied.</summary>
        public static OverlayConfig Apply(OverlayConfig b, AlbumArt art)
        {
            if (!b.beatEnabled || b.links.Count == 0) return b;
            OverlayConfig o = b.Copy();
            long now = Stopwatch.GetTimestamp();
            foreach (Link l in b.links)
            {
                Def d = Get(l.param);
                if (d == null || l.src <= 0) continue;
                double v = Shape(l, BeatAudio.Signal(l.src), now);
                try { Write(o, d, l, v, art); }
                catch (Exception) { } // a bad link must never break rendering
            }
            return o;
        }

        /// <summary>Gate, curve and smoothing; keeps the per-link state. Returns 0..1.</summary>
        static double Shape(Link l, double raw, long now)
        {
            double g = l.gate / 100.0;
            raw = raw < g ? 0 : (g >= 0.999 ? 0 : (raw - g) / (1 - g));
            if (l.curve == 1) raw = raw * raw;
            else if (l.curve == 2) raw = Math.Sqrt(raw);
            double dt = l.lastTicks == 0 ? 0.016 : Math.Min(0.1, (now - l.lastTicks) / (double)Stopwatch.Frequency);
            l.lastTicks = now;
            double tau = 0.03 + l.smooth / 100.0 * 1.2;
            double k = raw >= l.val ? 1 - Math.Exp(-dt / (tau * 0.12)) : 1 - Math.Exp(-dt / tau);
            l.val += (raw - l.val) * k;
            return Math.Max(0, Math.Min(1, l.val));
        }

        static bool Edge(Link l, double v)
        {
            if (l.armed && v >= 0.6) { l.armed = false; return true; }
            if (!l.armed && v < 0.3) l.armed = true;
            return false;
        }

        static void Write(OverlayConfig o, Def d, Link l, double v, AlbumArt art)
        {
            FieldInfo f = d.Field;
            double a = l.amount / 100.0;
            switch (d.Kind)
            {
                case K.Int:
                case K.Float:
                {
                    double b = Convert.ToDouble(f.GetValue(o));
                    double val = Math.Max(d.Lo, Math.Min(d.Hi, b + v * a * d.Range));
                    if (d.Kind == K.Int) f.SetValue(o, (int)Math.Round(val));
                    else f.SetValue(o, (float)val);
                    break;
                }
                case K.Bool:
                {
                    bool b = (bool)f.GetValue(o);
                    bool trig = v >= 0.5;
                    f.SetValue(o, a >= 0 ? b ^ trig : b ^ !trig);
                    break;
                }
                case K.Enum:
                {
                    if (Edge(l, v)) l.steps += a >= 0 ? 1 : -1;
                    int b = (int)f.GetValue(o);
                    f.SetValue(o, FloorMod(b + l.steps, d.N));
                    break;
                }
                case K.Color:
                {
                    int cur = (int)f.GetValue(o);
                    int accent = art != null ? art.Accent & 0xFFFFFF : 0xFFFFFF;
                    int b = cur >= 0 ? cur & 0xFFFFFF : accent;
                    f.SetValue(o, Color(l, v, a, b, accent, art));
                    break;
                }
            }
        }

        static int FloorMod(int x, int n) { int r = x % n; return r < 0 ? r + n : r; }

        static int Color(Link l, double v, double a, int b, int accent, AlbumArt art)
        {
            float[] hsb = Colors.RgbToHsb((b >> 16) & 0xFF, (b >> 8) & 0xFF, b & 0xFF);
            switch (l.mode)
            {
                case 0:
                {
                    float h = (float)(hsb[0] + v * a);
                    h -= (float)Math.Floor(h);
                    return Colors.HsbToRgb(h, hsb[1], hsb[2]) & 0xFFFFFF;
                }
                case 1:
                {
                    double mag = Math.Abs(a);
                    double sc = (1 - mag) + mag * (a >= 0 ? v : 1 - v);
                    return Colors.HsbToRgb(hsb[0], hsb[1], (float)(hsb[2] * sc)) & 0xFFFFFF;
                }
                case 2:
                    if (Edge(l, v)) l.steps += a >= 0 ? 1 : -1;
                    return l.steps == 0 ? b : Cycle[FloorMod(l.steps - 1, Cycle.Length)];
                case 3:
                {
                    if (Edge(l, v)) l.steps += a >= 0 ? 1 : -1;
                    int[] pal = art != null ? art.Palette : null;
                    if (pal == null || pal.Length < 2 || l.steps == 0) return b;
                    return pal[FloorMod(l.steps - 1, pal.Length)] & 0xFFFFFF;
                }
                case 4:
                    return Colors.Mix(b, a >= 0 ? 0xFFFFFF : 0x000000, v * Math.Abs(a));
                default:
                    return Colors.Mix(b, accent, v * Math.Abs(a));
            }
        }
    }

    /// <summary>Colour helpers with Java's HSB semantics so links behave exactly like in the mod.</summary>
    public static class Colors
    {
        public static float[] RgbToHsb(int r, int g, int b)
        {
            int cmax = Math.Max(r, Math.Max(g, b));
            int cmin = Math.Min(r, Math.Min(g, b));
            float bri = cmax / 255f;
            float sat = cmax != 0 ? (cmax - cmin) / (float)cmax : 0;
            float hue = 0;
            if (sat != 0)
            {
                float rc = (cmax - r) / (float)(cmax - cmin);
                float gc = (cmax - g) / (float)(cmax - cmin);
                float bc = (cmax - b) / (float)(cmax - cmin);
                if (r == cmax) hue = bc - gc;
                else if (g == cmax) hue = 2f + rc - bc;
                else hue = 4f + gc - rc;
                hue /= 6f;
                if (hue < 0) hue += 1f;
            }
            return new[] { hue, sat, bri };
        }

        /// <summary>Returns 0xFFRRGGBB.</summary>
        public static int HsbToRgb(float hue, float sat, float bri)
        {
            int r = 0, g = 0, b = 0;
            if (sat == 0)
            {
                r = g = b = (int)(bri * 255f + 0.5f);
            }
            else
            {
                float h = (hue - (float)Math.Floor(hue)) * 6f;
                float f = h - (float)Math.Floor(h);
                float p = bri * (1f - sat);
                float q = bri * (1f - sat * f);
                float t = bri * (1f - sat * (1f - f));
                switch ((int)h)
                {
                    case 0: r = (int)(bri * 255f + .5f); g = (int)(t * 255f + .5f); b = (int)(p * 255f + .5f); break;
                    case 1: r = (int)(q * 255f + .5f); g = (int)(bri * 255f + .5f); b = (int)(p * 255f + .5f); break;
                    case 2: r = (int)(p * 255f + .5f); g = (int)(bri * 255f + .5f); b = (int)(t * 255f + .5f); break;
                    case 3: r = (int)(p * 255f + .5f); g = (int)(q * 255f + .5f); b = (int)(bri * 255f + .5f); break;
                    case 4: r = (int)(t * 255f + .5f); g = (int)(p * 255f + .5f); b = (int)(bri * 255f + .5f); break;
                    default: r = (int)(bri * 255f + .5f); g = (int)(p * 255f + .5f); b = (int)(q * 255f + .5f); break;
                }
            }
            return unchecked((int)0xFF000000) | (r << 16) | (g << 8) | b;
        }

        public static int Mix(int a, int b, double f)
        {
            f = Math.Max(0, Math.Min(1, f));
            int r = (int)(((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
            int g = (int)(((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
            int bl = (int)((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
            return (r << 16) | (g << 8) | bl;
        }

        public static int Dim(int rgb, double f)
        {
            int r = (int)(((rgb >> 16) & 0xFF) * f);
            int g = (int)(((rgb >> 8) & 0xFF) * f);
            int b = (int)((rgb & 0xFF) * f);
            return (r << 16) | (g << 8) | b;
        }
    }
}
