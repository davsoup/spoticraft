using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;

namespace SpotiCraftOverlay
{
    /// <summary>
    /// Layout and drawing for the now-playing card (port of HudPanel.java). Everything is laid out in "units"
    /// (the mod's GUI pixels); the overlay maps units to screen pixels with uiScale. The block card has its own
    /// size on top of that (hudScale).
    /// </summary>
    sealed class Hud
    {
        public const int BLOCK_W = 224, PAD = 8, ART = 64, GAP = 10, BAR_H = 4;
        const int WHITE = unchecked((int)0xFFFFFFFF), GREEN = unchecked((int)0xFF1DB954);
        const float FONT_EM = 6.9f; // em size (units) whose line height matches the mod's 9 px font

        public Graphics G;
        /// <summary>Screen pixels per unit for this frame.</summary>
        public float U = 2f;
        /// <summary>Union of everything drawn this frame (screen pixels).</summary>
        public Rectangle Bounds;
        /// <summary>Desktop position of the overlay's top-left pixel (left/upper monitors make it negative).</summary>
        public int OriginX, OriginY;

        readonly Dictionary<string, Font> fonts = new Dictionary<string, Font>();
        readonly StringFormat typo;
        readonly string family;
        readonly Dictionary<string, Bitmap> glowCache = new Dictionary<string, Bitmap>();
        readonly Queue<string> glowOrder = new Queue<string>();
        readonly Stopwatch clock = Stopwatch.StartNew();

        public Hud()
        {
            typo = (StringFormat)StringFormat.GenericTypographic.Clone();
            typo.FormatFlags |= StringFormatFlags.MeasureTrailingSpaces | StringFormatFlags.NoWrap;
            family = "Segoe UI";
            try { using (var f = new FontFamily(family)) { } }
            catch (Exception) { family = FontFamily.GenericSansSerif.Name; }
        }

        public double Seconds { get { return clock.Elapsed.TotalSeconds; } }

        // ------------------------------------------------------------ frame setup

        public void Begin(Graphics g, float u) { Begin(g, u, 0, 0); }

        public void Begin(Graphics g, float u, int originX, int originY)
        {
            G = g;
            OriginX = originX;
            OriginY = originY;
            U = u;
            Bounds = Rectangle.Empty;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.InterpolationMode = InterpolationMode.HighQualityBilinear;
            g.PixelOffsetMode = PixelOffsetMode.Half;
            g.TextRenderingHint = TextRenderingHint.AntiAlias;
            g.CompositingMode = CompositingMode.SourceOver;
            ResetUnits();
        }

        /// <summary>World transform = units (no card scale).</summary>
        public void ResetUnits()
        {
            G.ResetTransform();
            G.ScaleTransform(U, U);
        }

        /// <summary>Records a unit-space rectangle (under the plain unit transform) as drawn.</summary>
        public void Mark(float x, float y, float w, float h)
        {
            var r = Rectangle.FromLTRB((int)Math.Floor(x * U) - 2, (int)Math.Floor(y * U) - 2,
                (int)Math.Ceiling((x + w) * U) + 2, (int)Math.Ceiling((y + h) * U) + 2);
            Bounds = Bounds.IsEmpty ? r : Rectangle.Union(Bounds, r);
        }

        // ------------------------------------------------------------ layout

        public sealed class Layout
        {
            public float W, H, ArtX, ArtY, ArtS, Tx, Tw, TitleY, ArtistY, BarY, TimeY, Ts, As;
        }

        public static Layout GetLayout(OverlayConfig cfg)
        {
            float ts = Math.Max(1f, Math.Min(2f, cfg.titleScale));
            float az = 1f + (ts - 1f) * 0.6f;
            float titleH = (float)Math.Ceiling(9 * ts), artistH = (float)Math.Ceiling(9 * az);
            float content = titleH + 2 + artistH + 6 + BAR_H + 3 + 9;
            float h = Math.Max(ART + 2 * PAD, content + 2 * PAD);
            float top = (h - content) / 2;
            float artistY = top + titleH + 2;
            float barY = artistY + artistH + 6;
            float tx = PAD + ART + GAP;
            return new Layout
            {
                W = BLOCK_W, H = h, ArtX = PAD + cfg.artOffX, ArtY = (h - ART) / 2 + cfg.artOffY, ArtS = ART,
                Tx = tx, Tw = BLOCK_W - tx - PAD, TitleY = top, ArtistY = artistY, BarY = barY,
                TimeY = barY + BAR_H + 3, Ts = ts, As = az
            };
        }

        public static float ScaleOf(OverlayConfig cfg) { return Math.Max(0.5f, Math.Min(3f, cfg.hudScale)); }

        /// <summary>Card rectangle in units: x, y (from hudX/hudY), w, h.</summary>
        public RectangleF CardRect(OverlayConfig cfg)
        {
            Layout L = GetLayout(cfg);
            float sc = ScaleOf(cfg);
            return new RectangleF((cfg.hudX - OriginX) / U, (cfg.hudY - OriginY) / U, L.W * sc, L.H * sc);
        }

        /// <summary>Corner radius in units for the block card (0 = square).</summary>
        public static float BlockRadius(OverlayConfig cfg)
        {
            if (!cfg.rounded) return 0;
            return (float)Math.Max(4, Math.Min(12, Math.Round(4 + 2 * ScaleOf(cfg))));
        }

        // ------------------------------------------------------------ main entry

        double spinAngle, armLift;
        double lastSec = -1;

        /// <summary>Draws glow + card. While p &lt; 1 the old track slides out and the new one slides in.</summary>
        public void Render(OverlayConfig cfg, PlaybackState s, AlbumArt art, bool interactive, long progOv,
                           PlaybackState oldS, AlbumArt oldArt, double p, int dir)
        {
            double now = Seconds;
            if (lastSec >= 0)
            {
                double dt = Math.Min(0.1, now - lastSec);
                if (s.Playing && cfg.motion) spinAngle = (spinAngle + 1.1 * dt) % (Math.PI * 2);
                armLift += ((s.Playing ? 0.0 : 1.0) - armLift) * Math.Min(1.0, dt * 5.0);
            }
            lastSec = now;

            RectangleF R = CardRect(cfg);
            int accent = Accent(art);
            int glowRgb = (cfg.glowColor < 0 ? accent : cfg.glowColor) & 0xFFFFFF;
            if (cfg.glow && cfg.glowStrength > 0) DrawGlow(cfg, R, glowRgb);
            Mark(R.X, R.Y, R.Width, R.Height);

            if (oldS != null && p < 1)
            {
                double e = 1 - Math.Pow(1 - p, 3);
                GraphicsState st = G.Save();
                G.SetClip(R, CombineMode.Intersect);
                G.TranslateTransform((float)(-dir * e * R.Width), 0);
                Card(cfg, oldS.WithProgress(oldS.DurationMs), oldArt, R, false, -1);
                G.Restore(st);
                st = G.Save();
                G.SetClip(R, CombineMode.Intersect);
                G.TranslateTransform((float)(dir * (1 - e) * R.Width), 0);
                Card(cfg, s, art, R, interactive, progOv);
                G.Restore(st);
                return;
            }
            Card(cfg, s, art, R, interactive, progOv);
        }

        void Card(OverlayConfig cfg, PlaybackState s, AlbumArt art, RectangleF R, bool interactive, long progOv)
        {
            Layout L = GetLayout(cfg);
            double t = cfg.motion ? Seconds : 0.0;
            GraphicsState st = G.Save();
            G.TranslateTransform(R.X, R.Y);
            float sc = ScaleOf(cfg);
            G.ScaleTransform(sc, sc);
            float r = BlockRadius(cfg) / sc;
            DrawBox(art, 0, 0, L.W, L.H, r, cfg.bgMode, cfg, 0xE6, cfg.bgDarkness, t);
            Content(cfg, s, art, L, interactive, progOv);
            G.Restore(st);
        }

        void Content(OverlayConfig cfg, PlaybackState s, AlbumArt art, Layout L, bool interactive, long progOv)
        {
            int accent = Accent(art);
            int textRgb = (cfg.textColor < 0 ? accent : cfg.textColor) & 0xFFFFFF;
            int barRgb = (cfg.barColor < 0 ? accent : cfg.barColor) & 0xFFFFFF;
            DrawArtSquare(art, L.ArtX, L.ArtY, L.ArtS);

            int align = cfg.TextAlign();
            int titleCol = unchecked((int)0xFF000000) | textRgb;
            int artistCol = unchecked((int)0xFF000000) | Colors.Dim(textRgb, 0.82);
            int timeCol = unchecked((int)0xFF000000) | Colors.Dim(textRgb, 0.62);

            string title = string.IsNullOrEmpty(s.Title) ? "Unknown" : s.Title;
            Aligned(cfg, title, L.Tx, L.Tw, L.TitleY, titleCol, true, L.Ts, align, true);
            Aligned(cfg, s.Artist, L.Tx, L.Tw, L.ArtistY, artistCol, true, L.As, align, false);

            long progress = progOv >= 0 ? progOv : s.LiveProgressMs();
            double frac = s.DurationMs > 0 ? Math.Min(1.0, (double)progress / s.DurationMs) : 0.0;
            Bar(L.Tx, L.BarY, L.Tw, BAR_H, frac, unchecked((int)0xFF000000) | barRgb, interactive);

            string left = Fmt(progress), right = Fmt(s.DurationMs);
            Text(left, L.Tx, L.TimeY, timeCol, true, 1f, false);
            Text(right, L.Tx + L.Tw - Width(right, 1f, false), L.TimeY, timeCol, true, 1f, false);
            Badges(s, cfg.modeIndicators, L.Tx + L.Tw / 2, L.TimeY - 1);
        }

        void Badges(PlaybackState s, bool showModes, float cx, float y)
        {
            var items = new List<string>();
            var cols = new List<int>();
            bool any = showModes && (s.Shuffle || s.Repeat != 0);
            if (!s.Playing) { items.Add(any ? "||" : "Paused"); cols.Add(WHITE); }
            if (showModes && s.Shuffle) { items.Add("SHF"); cols.Add(GREEN); }
            if (showModes && s.Repeat != 0) { items.Add(s.Repeat == 2 ? "RP1" : "RPT"); cols.Add(GREEN); }
            if (items.Count == 0) return;
            const float bs = 0.85f;
            float total = 0;
            foreach (string it in items) total += Width(it, bs, true) + 6;
            total += (items.Count - 1) * 3;
            float bx = cx - total / 2;
            for (int i = 0; i < items.Count; i++)
            {
                float w = Width(items[i], bs, true) + 6;
                FillRound(bx, y, w, 11, unchecked((int)0xB0000000), 2);
                Text(items[i], bx + 3, y + 1.5f, cols[i], false, bs, true);
                bx += w + 3;
            }
        }

        // ------------------------------------------------------------ shapes

        static GraphicsPath RoundPath(float x, float y, float w, float h, float r)
        {
            var p = new GraphicsPath();
            r = Math.Max(0, Math.Min(r, Math.Min(w, h) / 2));
            if (r < 0.01f) { p.AddRectangle(new RectangleF(x, y, w, h)); return p; }
            float d = 2 * r;
            p.AddArc(x, y, d, d, 180, 90);
            p.AddArc(x + w - d, y, d, d, 270, 90);
            p.AddArc(x + w - d, y + h - d, d, d, 0, 90);
            p.AddArc(x, y + h - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }

        public void FillRound(float x, float y, float w, float h, int argb, float r)
        {
            using (var b = new SolidBrush(Color.FromArgb(argb)))
            {
                if (r <= 0.01f) { G.FillRectangle(b, x, y, w, h); return; }
                using (var p = RoundPath(x, y, w, h, r)) G.FillPath(b, p);
            }
        }

        public void FillRect(float x, float y, float w, float h, int argb)
        {
            using (var b = new SolidBrush(Color.FromArgb(argb))) G.FillRectangle(b, x, y, w, h);
        }

        /// <summary>The moving colour spill clipped to a rounded rectangle. Returns false if it can't.</summary>
        bool SpillRect(AlbumArt art, float x, float y, float w, float h, float r, double t, double alpha)
        {
            if (art == null || !art.Loaded || art.Spill == null) return false;
            float cx = x + w / 2, cy = y + h / 2;
            float size = (float)((Math.Sqrt(w * w + h * h) + 64) / 0.88) + 8;
            double dx = 14 * Math.Sin(t * 0.35), dy = 8 * Math.Sin(t * 0.27 + 1.0);
            double sc = 1.0 + 0.07 * Math.Sin(t * 0.5), rot = 0.07 * Math.Sin(t * 0.21);
            GraphicsState st = G.Save();
            using (var path = RoundPath(x, y, w, h, r)) G.SetClip(path, CombineMode.Intersect);
            G.TranslateTransform((float)(cx + dx), (float)(cy + dy));
            G.RotateTransform((float)(rot * 180 / Math.PI));
            G.ScaleTransform((float)sc, (float)sc);
            var dest = new RectangleF(-size / 2, -size / 2, size, size);
            if (alpha >= 0.999)
            {
                G.DrawImage(art.Spill, dest, new RectangleF(0, 0, AlbumArt.SPILL, AlbumArt.SPILL), GraphicsUnit.Pixel);
            }
            else
            {
                using (var ia = Tint(1, 1, 1, (float)alpha, false))
                    G.DrawImage(art.Spill, ToPoints(dest), new RectangleF(0, 0, AlbumArt.SPILL, AlbumArt.SPILL), GraphicsUnit.Pixel, ia);
            }
            G.Restore(st);
            return true;
        }

        static PointF[] ToPoints(RectangleF r)
        {
            return new[] { new PointF(r.Left, r.Top), new PointF(r.Right, r.Top), new PointF(r.Left, r.Bottom) };
        }

        int SolidColor(OverlayConfig cfg, AlbumArt art, int alpha)
        {
            if (cfg.bgColor >= 0) return (alpha << 24) | (cfg.bgColor & 0xFFFFFF);
            return art != null && art.Loaded ? art.DarkAverage(alpha) : (alpha << 24) | 0x202020;
        }

        /// <summary>Generic box: mode 0 = mograph, 1 = solid, 2 = none. Darkness is laid over the mograph.</summary>
        void DrawBox(AlbumArt art, float x, float y, float w, float h, float r, int mode, OverlayConfig cfg,
                     int solidAlpha, int darkPct, double t)
        {
            if (mode == 2) return;
            if (mode == 0 && SpillRect(art, x, y, w, h, r, t, 1.0))
            {
                if (darkPct > 0) FillRound(x, y, w, h, (int)(darkPct * 2.55) << 24, r);
                return;
            }
            FillRound(x, y, w, h, SolidColor(cfg, art, solidAlpha), r);
        }

        void DrawArtSquare(AlbumArt art, float x, float y, float size)
        {
            if (art != null && art.Loaded && art.Art != null)
            {
                G.DrawImage(art.Art, new RectangleF(x, y, size, size),
                    new RectangleF(0, 0, AlbumArt.TEX, AlbumArt.TEX), GraphicsUnit.Pixel);
                return;
            }
            FillRound(x, y, size, size, unchecked((int)0xFF3A3A3A), size * 0.09f);
            string note = "♪";
            float s = 1.6f;
            Text(note, x + (size - Width(note, s, false)) / 2, y + size / 2 - 7, unchecked((int)0xFF9A9A9A), false, s, false);
        }

        // ------------------------------------------------------------ glow (baked soft masks, tinted per frame)

        const int DEN = 2; // mask texels per unit

        /// <summary>
        /// Soft glow hugging the card's rounded shape. The alpha mask is baked per size/spread/softness and cached;
        /// colour and strength are applied when drawing, so beat-linked strength or colour never rebakes it.
        /// </summary>
        void DrawGlow(OverlayConfig cfg, RectangleF R, int rgb)
        {
            int spread = Math.Max(4, (cfg.glowSpread + 1) / 2 * 2); // quantised: beat links don't rebake every frame
            int soft = (int)Math.Round(cfg.glowSoftness / 10f) * 10;
            float r = BlockRadius(cfg);
            int wq = (int)Math.Round(R.Width * DEN), hq = (int)Math.Round(R.Height * DEN);
            string key = wq + "x" + hq + "r" + r + "s" + spread + "f" + soft;
            Bitmap mask;
            if (!glowCache.TryGetValue(key, out mask))
            {
                mask = BakeRectGlow(wq, hq, (int)(r * DEN), spread * DEN, soft);
                glowCache[key] = mask;
                glowOrder.Enqueue(key);
                while (glowOrder.Count > 32)
                {
                    string old = glowOrder.Dequeue();
                    Bitmap ob;
                    if (glowCache.TryGetValue(old, out ob)) { ob.Dispose(); glowCache.Remove(old); }
                }
            }
            float strength = Math.Max(0, Math.Min(1, cfg.glowStrength / 100f));
            var dest = new RectangleF(R.X - spread, R.Y - spread, R.Width + 2 * spread, R.Height + 2 * spread);
            using (var ia = Tint(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, strength, true))
            {
                DrawImageDevice(mask, dest, ia);
            }
            Mark(dest.X, dest.Y, dest.Width, dest.Height);
        }

        /// <summary>
        /// Draws an image with ImageAttributes into a unit-space rectangle by mapping it to device pixels first.
        /// The PointF[]/RectangleF + ImageAttributes overloads don't apply the world transform consistently
        /// across GDI+ implementations; the integer-rectangle overload does what it says everywhere.
        /// </summary>
        void DrawImageDevice(Image img, RectangleF unitDest, ImageAttributes ia)
        {
            var pts = new[] { new PointF(unitDest.Left, unitDest.Top), new PointF(unitDest.Right, unitDest.Bottom) };
            using (Matrix mtx = G.Transform) mtx.TransformPoints(pts);
            var r = Rectangle.FromLTRB((int)Math.Round(pts[0].X), (int)Math.Round(pts[0].Y),
                (int)Math.Round(pts[1].X), (int)Math.Round(pts[1].Y));
            GraphicsState st = G.Save();
            G.ResetTransform();
            G.DrawImage(img, r, 0, 0, img.Width, img.Height, GraphicsUnit.Pixel, ia);
            G.Restore(st);
        }

        /// <summary>Colour matrix: replace RGB with a colour (if setRgb) and scale alpha.</summary>
        static ImageAttributes Tint(float r, float g, float b, float a, bool setRgb)
        {
            var m = setRgb
                ? new ColorMatrix(new[]
                {
                    new float[] { 0, 0, 0, 0, 0 }, new float[] { 0, 0, 0, 0, 0 }, new float[] { 0, 0, 0, 0, 0 },
                    new float[] { 0, 0, 0, a, 0 }, new float[] { r, g, b, 0, 1 }
                })
                : new ColorMatrix(new[]
                {
                    new float[] { r, 0, 0, 0, 0 }, new float[] { 0, g, 0, 0, 0 }, new float[] { 0, 0, b, 0, 0 },
                    new float[] { 0, 0, 0, a, 0 }, new float[] { 0, 0, 0, 0, 1 }
                });
            var ia = new ImageAttributes();
            ia.SetColorMatrix(m);
            ia.SetWrapMode(WrapMode.TileFlipXY); // no dark seams at the edges when stretched
            return ia;
        }

        static double Falloff(double dist, double reach, double softness)
        {
            if (dist <= 0) return 1.0;
            double t = dist / reach;
            if (t >= 1) return 0.0;
            double k = 1.0 + 3.0 * (1.0 - softness / 100.0);
            return Math.Pow(1.0 - t, k);
        }

        /// <summary>White mask: card (w x h, radius r) in the middle, glow fading out over 'spread' texels.</summary>
        public static Bitmap BakeRectGlow(int w, int h, int r, int spread, int softness)
        {
            int tw = w + 2 * spread, th = h + 2 * spread;
            var px = new int[tw * th];
            double peak = 0.9;
            double cx = tw / 2.0, cy = th / 2.0, hx = w / 2.0, hy = h / 2.0;
            for (int j = 0; j < th; j++)
            {
                double qy = Math.Abs(j + 0.5 - cy) - (hy - r);
                for (int i = 0; i < tw; i++)
                {
                    double qx = Math.Abs(i + 0.5 - cx) - (hx - r);
                    double d = Math.Sqrt(Math.Pow(Math.Max(qx, 0), 2) + Math.Pow(Math.Max(qy, 0), 2))
                               + Math.Min(Math.Max(qx, qy), 0) - r;
                    // transparent inside the card so no glow can ever show through the corners
                    double inside = Math.Max(0, Math.Min(1, (d + 1.5) / 1.5));
                    double a = peak * Falloff(Math.Max(d, 0), spread, softness) * inside;
                    int al = (int)Math.Round(a * 255);
                    px[j * tw + i] = (al << 24) | 0xFFFFFF;
                }
            }
            var bmp = new Bitmap(tw, th, PixelFormat.Format32bppArgb);
            BitmapData bd = bmp.LockBits(new Rectangle(0, 0, tw, th), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
            for (int y = 0; y < th; y++) System.Runtime.InteropServices.Marshal.Copy(px, y * tw, bd.Scan0 + y * bd.Stride, tw);
            bmp.UnlockBits(bd);
            return bmp;
        }

        // ------------------------------------------------------------ messages / controls

        /// <summary>Small text-only card for setup / idle messages (unit space). Returns its bottom y.</summary>
        public float Message(float x, float y, params string[] lines)
        {
            float h = 10 + lines.Length * 11;
            FillRound(x, y, BLOCK_W, h, unchecked((int)0xE0161616), 4);
            for (int i = 0; i < lines.Length; i++)
                Text(Fit(lines[i], BLOCK_W - 16, 1f, i == 0), x + 8, y + 5 + i * 11,
                    i == 0 ? WHITE : unchecked((int)0xFFC8C8C8), i == 0, 1f, i == 0);
            Mark(x, y, BLOCK_W, h);
            return y + h;
        }

        public void Status(string text, float x, float y)
        {
            string s = Fit(text, BLOCK_W + 60, 1f, false);
            Text(s, x, y, unchecked((int)0xFFFF6B6B), true, 1f, false);
            Mark(x, y, Width(s, 1f, false) + 2, 11);
        }

        public void Button(string label, float x, float y, float w, float h, bool hover)
        {
            FillRound(x, y, w, h, hover ? unchecked((int)0xE0606060) : unchecked((int)0xC0000000), 2.5f);
            string s = Fit(label, w - 4, 1f, false);
            Text(s, x + (w - Width(s, 1f, false)) / 2, y + (h - 9) / 2, WHITE, false, 1f, false);
            Mark(x, y, w, h);
        }

        public void Toggle(string label, float x, float y, float w, float h, bool hover, bool on)
        {
            int c = on ? (hover ? unchecked((int)0xF03CD070) : unchecked((int)0xE01DB954))
                       : (hover ? unchecked((int)0xE0606060) : unchecked((int)0xC0000000));
            FillRound(x, y, w, h, c, 2.5f);
            string s = Fit(label, w - 4, 1f, false);
            Text(s, x + (w - Width(s, 1f, false)) / 2, y + (h - 9) / 2, WHITE, false, 1f, false);
            Mark(x, y, w, h);
        }

        public const float VOL_BAR_X = 26, VOL_RIGHT = 34;

        public void VolumeRow(float x, float y, float w, int vol)
        {
            FillRound(x, y, w, 14, unchecked((int)0xC0000000), 2.5f);
            Text("Vol", x + 6, y + 2.5f, unchecked((int)0xFFC8C8C8), false, 1f, false);
            Bar(x + VOL_BAR_X, y + 5, w - VOL_BAR_X - VOL_RIGHT, BAR_H, vol / 100.0, WHITE, true);
            string pct = vol + "%";
            Text(pct, x + w - 6 - Width(pct, 1f, false), y + 2.5f, unchecked((int)0xFFC8C8C8), false, 1f, false);
            Mark(x, y, w, 14);
        }

        public void Handle(float cx, float cy)
        {
            FillRect(cx - 3, cy - 3, 6, 6, unchecked((int)0xFF000000));
            FillRect(cx - 2, cy - 2, 4, 4, WHITE);
            Mark(cx - 3, cy - 3, 6, 6);
        }

        /// <summary>Hint pill centred at the bottom of a monitor (monitor rectangle in units).</summary>
        public void Hint(string text, RectangleF mon)
        {
            float w = Width(text, 1f, false);
            float x = mon.X + (mon.Width - w) / 2, y = mon.Bottom - 16;
            FillRound(x - 6, y - 3, w + 12, 15, unchecked((int)0xA0000000), 3);
            Text(text, x, y, unchecked((int)0xFFDDDDDD), false, 1f, false);
            Mark(x - 6, y - 3, w + 12, 15);
        }

        // ------------------------------------------------------------ primitives

        void Bar(float x, float y, float w, float h, double frac, int color, bool knob)
        {
            frac = Math.Max(0, Math.Min(1, frac));
            float fw = (float)(w * frac);
            FillRect(x, y, w, h, 0x55FFFFFF);
            if (fw > 0) FillRect(x, y, fw, h, color);
            if (knob)
            {
                float kx = Math.Max(x + 2, Math.Min(x + w - 2, x + fw));
                FillRound(kx - 2, y - 2, 4, h + 4, WHITE, 1);
            }
        }

        Font FontFor(float scale, bool bold)
        {
            string key = (int)Math.Round(scale * 100) + (bold ? "b" : "");
            Font f;
            if (!fonts.TryGetValue(key, out f))
            {
                f = new Font(family, FONT_EM * scale, bold ? FontStyle.Bold : FontStyle.Regular, GraphicsUnit.Pixel);
                fonts[key] = f;
            }
            return f;
        }

        public float Width(string s, float scale, bool bold)
        {
            if (string.IsNullOrEmpty(s)) return 0;
            return G.MeasureString(s, FontFor(scale, bold), PointF.Empty, typo).Width;
        }

        /// <summary>Text with an optional soft drop shadow (like Minecraft's text shadow).</summary>
        public void Text(string s, float x, float y, int argb, bool shadow, float scale, bool bold)
        {
            if (string.IsNullOrEmpty(s)) return;
            Font f = FontFor(scale, bold);
            if (shadow)
            {
                int sa = (int)(((argb >> 24) & 0xFF) * 0.55);
                using (var sb = new SolidBrush(Color.FromArgb(sa, 0, 0, 0))) G.DrawString(s, f, sb, x + 0.7f, y + 0.7f, typo);
            }
            using (var b = new SolidBrush(Color.FromArgb(argb))) G.DrawString(s, f, b, x, y, typo);
        }

        /// <summary>One line in a column. Too-wide text shrinks (auto-fit) or slowly scrolls back and forth.</summary>
        void Aligned(OverlayConfig cfg, string s, float colX, float colW, float y, int color, bool shadow, float scale,
                     int align, bool bold)
        {
            if (string.IsNullOrEmpty(s)) return;
            float raw = Width(s, 1f, bold);
            float sc = scale;
            if (cfg.autoFit && raw * sc > colW) sc = Math.Max(0.6f, Math.Min(scale, colW / raw));
            float yy = y + (scale - sc) * 9 / 2f;
            float tw = raw * sc;
            if (tw <= colW + 0.5f)
            {
                float px = align == 1 ? colX + (colW - tw) / 2 : align == 2 ? colX + colW - tw : colX;
                Text(s, px, yy, color, shadow, sc, bold);
                return;
            }
            // marquee: pause, slide to the end, pause, slide back
            float h = 9 * sc + 3;
            GraphicsState st = G.Save();
            G.SetClip(new RectangleF(colX, yy - 1, colW, h + 2), CombineMode.Intersect);
            double over = tw - colW, speed = 18.0, pause = 1.2, move = over / speed;
            double period = 2 * pause + 2 * move;
            double ph = Seconds % period, off;
            if (ph < pause) off = 0;
            else if (ph < pause + move) off = (ph - pause) * speed;
            else if (ph < 2 * pause + move) off = over;
            else off = over - (ph - 2 * pause - move) * speed;
            Text(s, (float)(colX - off), yy, color, shadow, sc, bold);
            G.Restore(st);
        }

        public string Fit(string s, float maxWidth, float scale, bool bold)
        {
            if (s == null) return "";
            if (Width(s, scale, bold) <= maxWidth) return s;
            int end = s.Length;
            while (end > 0 && Width(s.Substring(0, end) + "...", scale, bold) > maxWidth) end--;
            return s.Substring(0, end) + "...";
        }

        static int Accent(AlbumArt art) { return art != null && art.Loaded ? art.Accent : WHITE; }

        public static string Fmt(long ms)
        {
            long total = Math.Max(0, ms) / 1000;
            return (total / 60) + ":" + (total % 60).ToString("00");
        }

        // ------------------------------------------------------------ hit testing (unit space)

        public bool InBar(OverlayConfig cfg, float mx, float my)
        {
            Layout L = GetLayout(cfg);
            RectangleF R = CardRect(cfg);
            float sc = ScaleOf(cfg);
            return mx >= R.X + L.Tx * sc - 3 && mx <= R.X + (L.Tx + L.Tw) * sc + 3
                   && my >= R.Y + L.BarY * sc - 5 && my <= R.Y + (L.BarY + BAR_H) * sc + 5;
        }

        public double BarFrac(OverlayConfig cfg, float mx)
        {
            Layout L = GetLayout(cfg);
            RectangleF R = CardRect(cfg);
            float sc = ScaleOf(cfg);
            return Math.Max(0, Math.Min(1, (mx - (R.X + L.Tx * sc)) / (L.Tw * sc)));
        }
    }
}
