using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Threading.Tasks;

namespace SpotiCraftOverlay
{
    /// <summary>
    /// Downloads cover art once and prepares everything the HUD needs from it (port of AlbumArt.java):
    /// a rounded art bitmap, CD and vinyl discs, a blurred colour-spill bitmap, accent colours and a border palette.
    /// </summary>
    public sealed class AlbumArt : IDisposable
    {
        public const int TEX = 256;
        public const int SPILL = 256;

        readonly string url;
        volatile bool loaded;
        public Bitmap Art, Disc, Spill, Vinyl, VinylTint;
        int[] palette = { unchecked((int)0xFFFFFFFF) };
        int average = unchecked((int)0xFF202020);
        int accent = unchecked((int)0xFFFFFFFF);

        public AlbumArt(string url) { this.url = url ?? ""; }

        public bool Loaded { get { return loaded; } }
        /// <summary>Bright colour picked from the cover (0xFFRRGGBB).</summary>
        public int Accent { get { return accent; } }
        public int[] Palette { get { return palette; } }

        /// <summary>Dark version of the cover's average colour, with the given alpha (ARGB).</summary>
        public int DarkAverage(int alpha)
        {
            int c = average;
            int r = (int)(((c >> 16) & 0xFF) * 0.38);
            int g = (int)(((c >> 8) & 0xFF) * 0.38);
            int b = (int)((c & 0xFF) * 0.38);
            return (alpha << 24) | (r << 16) | (g << 8) | b;
        }

        public void Load(HttpClient http)
        {
            if (url.Length == 0) return;
            Task.Run(() =>
            {
                try
                {
                    byte[] data = http.GetByteArrayAsync(url).GetAwaiter().GetResult();
                    using (var ms = new MemoryStream(data))
                    using (var src = new Bitmap(ms))
                    {
                        Build(src);
                    }
                }
                catch (Exception) { } // no art is fine; the HUD shows a placeholder
            });
        }

        /// <summary>Public for tests.</summary>
        public void Build(Bitmap src)
        {
            int[] a = Resample(src, TEX);
            const int G = 6;
            int cell = TEX / G;
            var cells = new float[G, G, 3];
            double sr = 0, sg = 0, sb = 0, bestScore = -1;
            float[] bestHsb = { 0, 0.5f, 1f };
            for (int cj = 0; cj < G; cj++)
            {
                for (int ci = 0; ci < G; ci++)
                {
                    long r = 0, g = 0, b = 0;
                    for (int y = 0; y < cell; y++)
                        for (int x = 0; x < cell; x++)
                        {
                            int p = a[(cj * cell + y) * TEX + ci * cell + x];
                            r += (p >> 16) & 0xFF;
                            g += (p >> 8) & 0xFF;
                            b += p & 0xFF;
                        }
                    int n = cell * cell;
                    float rr = r / (float)n, gg = g / (float)n, bb = b / (float)n;
                    float[] hsb = Colors.RgbToHsb((int)rr, (int)gg, (int)bb);
                    hsb[1] = Math.Min(1f, hsb[1] * 1.35f);
                    int boosted = Colors.HsbToRgb(hsb[0], hsb[1], hsb[2]);
                    cells[cj, ci, 0] = (boosted >> 16) & 0xFF;
                    cells[cj, ci, 1] = (boosted >> 8) & 0xFF;
                    cells[cj, ci, 2] = boosted & 0xFF;
                    sr += rr; sg += gg; sb += bb;
                    double score = hsb[1] * (0.4 + hsb[2]);
                    if (score > bestScore) { bestScore = score; bestHsb = hsb; }
                }
            }
            int tot = G * G;
            average = unchecked((int)0xFF000000) | ((int)(sr / tot) << 16) | ((int)(sg / tot) << 8) | (int)(sb / tot);
            accent = Colors.HsbToRgb(bestHsb[0], Math.Min(bestHsb[1], 0.65f), 1f);

            var pal = new int[4 * (G - 1)];
            int pi = 0;
            for (int i = 0; i < G - 1; i++) pal[pi++] = CellRgb(cells, 0, i);
            for (int j = 0; j < G - 1; j++) pal[pi++] = CellRgb(cells, j, G - 1);
            for (int i = G - 1; i > 0; i--) pal[pi++] = CellRgb(cells, G - 1, i);
            for (int j = G - 1; j > 0; j--) pal[pi++] = CellRgb(cells, j, 0);
            palette = pal;

            Art = ToBitmap(RoundedArt(a), TEX);
            Disc = ToBitmap(DiscPx(a), TEX);
            Spill = ToBitmap(SpillPx(cells, G), SPILL);
            Vinyl = ToBitmap(VinylPx(a, unchecked((int)0xFF0B0B0B)), TEX);
            VinylTint = ToBitmap(VinylPx(a, Mix(average, unchecked((int)0xFF000000), 0.72)), TEX);
            loaded = true;
        }

        public void Dispose()
        {
            loaded = false;
            foreach (Bitmap b in new[] { Art, Disc, Spill, Vinyl, VinylTint }) if (b != null) b.Dispose();
            Art = Disc = Spill = Vinyl = VinylTint = null;
        }

        static int CellRgb(float[,,] c, int j, int i)
        {
            return unchecked((int)0xFF000000) | ((int)c[j, i, 0] << 16) | ((int)c[j, i, 1] << 8) | (int)c[j, i, 2];
        }

        /// <summary>Straight ARGB pixels to a premultiplied bitmap (fast to draw onto the PArgb surface).</summary>
        public static Bitmap ToBitmap(int[] argb, int size)
        {
            var bmp = new Bitmap(size, size, PixelFormat.Format32bppPArgb);
            var pm = new int[argb.Length];
            for (int i = 0; i < argb.Length; i++)
            {
                int p = argb[i];
                int al = (p >> 24) & 0xFF;
                if (al == 255) { pm[i] = p; continue; }
                int r = ((p >> 16) & 0xFF) * al / 255, g = ((p >> 8) & 0xFF) * al / 255, b = (p & 0xFF) * al / 255;
                pm[i] = (al << 24) | (r << 16) | (g << 8) | b;
            }
            BitmapData d = bmp.LockBits(new Rectangle(0, 0, size, size), ImageLockMode.WriteOnly, PixelFormat.Format32bppPArgb);
            for (int y = 0; y < size; y++) Marshal.Copy(pm, y * size, d.Scan0 + y * d.Stride, size);
            bmp.UnlockBits(d);
            return bmp;
        }

        /// <summary>Centre-crops to a square and box-filters down to size x size (opaque ARGB).</summary>
        public static int[] Resample(Bitmap src, int size)
        {
            int w = src.Width, h = src.Height;
            int[] px = new int[w * h];
            using (var tmp = new Bitmap(w, h, PixelFormat.Format32bppArgb))
            {
                using (var g = Graphics.FromImage(tmp)) g.DrawImage(src, 0, 0, w, h);
                BitmapData d = tmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
                for (int y = 0; y < h; y++) Marshal.Copy(d.Scan0 + y * d.Stride, px, y * w, w);
                tmp.UnlockBits(d);
            }
            int side = Math.Min(w, h), ox = (w - side) / 2, oy = (h - side) / 2;
            int[] o = new int[size * size];
            for (int j = 0; j < size; j++)
            {
                int y0 = oy + (int)((long)j * side / size);
                int y1 = Math.Max(y0 + 1, oy + (int)((long)(j + 1) * side / size));
                for (int i = 0; i < size; i++)
                {
                    int x0 = ox + (int)((long)i * side / size);
                    int x1 = Math.Max(x0 + 1, ox + (int)((long)(i + 1) * side / size));
                    long r = 0, g = 0, b = 0;
                    int n = 0;
                    for (int yy = y0; yy < y1; yy++)
                        for (int xx = x0; xx < x1; xx++)
                        {
                            int p = px[yy * w + xx];
                            r += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; b += p & 0xFF; n++;
                        }
                    o[j * size + i] = unchecked((int)0xFF000000) | ((int)(r / n) << 16) | ((int)(g / n) << 8) | (int)(b / n);
                }
            }
            return o;
        }

        static int[] RoundedArt(int[] a)
        {
            int[] o = (int[])a.Clone();
            double rad = TEX * 0.09;
            for (int y = 0; y < TEX; y++)
                for (int x = 0; x < TEX; x++)
                {
                    double dx = Math.Max(0, Math.Max(rad - (x + 0.5), (x + 0.5) - (TEX - rad)));
                    double dy = Math.Max(0, Math.Max(rad - (y + 0.5), (y + 0.5) - (TEX - rad)));
                    double cover = Clamp01(rad + 0.5 - Math.Sqrt(dx * dx + dy * dy));
                    if (cover < 1) o[y * TEX + x] = WithAlpha(o[y * TEX + x], cover);
                }
            return o;
        }

        static int[] DiscPx(int[] a)
        {
            int[] o = new int[TEX * TEX];
            double c = TEX / 2.0, R = TEX / 2.0, hole = TEX * 0.07, ring = TEX * 0.15;
            for (int y = 0; y < TEX; y++)
                for (int x = 0; x < TEX; x++)
                {
                    double dx = x + 0.5 - c, dy = y + 0.5 - c;
                    double r = Math.Sqrt(dx * dx + dy * dy);
                    double cover = Clamp01(R - r) * Clamp01(r - hole + 0.5);
                    if (cover <= 0) continue;
                    int p = a[y * TEX + x];
                    if (r < ring) p = Mix(p, unchecked((int)0xFF101010), 0.55 * Clamp01((ring - r) / 3.0));
                    if (r > R - 4) p = Mix(p, unchecked((int)0xFF000000), 0.3 * Clamp01((r - (R - 4)) / 4.0));
                    o[y * TEX + x] = WithAlpha(p, cover);
                }
            return o;
        }

        static int[] VinylPx(int[] a, int body)
        {
            int[] o = new int[TEX * TEX];
            double c = TEX / 2.0, R = TEX / 2.0, label = R * 0.36;
            for (int y = 0; y < TEX; y++)
                for (int x = 0; x < TEX; x++)
                {
                    double dx = x + 0.5 - c, dy = y + 0.5 - c;
                    double r = Math.Sqrt(dx * dx + dy * dy);
                    double cover = Clamp01(R - r);
                    if (cover <= 0) continue;
                    int p;
                    if (r < label)
                    {
                        int sx = (int)Math.Max(0, Math.Min(TEX - 1, (dx / label * 0.5 + 0.5) * TEX));
                        int sy = (int)Math.Max(0, Math.Min(TEX - 1, (dy / label * 0.5 + 0.5) * TEX));
                        p = a[sy * TEX + sx];
                        if (r < R * 0.035) p = unchecked((int)0xFF080808);
                        else if (r > label - 1.5) p = Mix(p, unchecked((int)0xFF000000), 0.5);
                    }
                    else
                    {
                        double groove = 0.5 + 0.5 * Math.Sin(r * 2.1);
                        double band = r > R * 0.9 ? 0.0 : 1.0;
                        p = Mix(body, unchecked((int)0xFF3A3A3A), 0.10 * groove * band + 0.05 * Math.Sin(r * 0.37));
                        if (r < label + 3) p = Mix(p, unchecked((int)0xFF000000), 0.5);
                    }
                    o[y * TEX + x] = WithAlpha(p, cover);
                }
            return o;
        }

        static int[] SpillPx(float[,,] cells, int G)
        {
            int[] o = new int[SPILL * SPILL];
            double tau = Math.PI * 2;
            for (int py = 0; py < SPILL; py++)
            {
                double v = py / (SPILL - 1.0);
                for (int px = 0; px < SPILL; px++)
                {
                    double u = px / (SPILL - 1.0);
                    double wu = u + 0.11 * Math.Sin(tau * (v * 1.1) + 0.7) + 0.06 * Math.Sin(tau * (v * 2.3) + 2.1);
                    double wv = v + 0.11 * Math.Sin(tau * (u * 1.2) + 1.9) + 0.06 * Math.Sin(tau * (u * 2.1) + 0.3);
                    double gx = Clamp01(wu) * (G - 1), gy = Clamp01(wv) * (G - 1);
                    int ix = Math.Min(G - 2, (int)gx), iy = Math.Min(G - 2, (int)gy);
                    double fx = Smooth(gx - ix), fy = Smooth(gy - iy);
                    double shade = 1.0 + 0.10 * Math.Sin(tau * (u * 1.7 + v * 1.3) + 1.0);
                    int[] ch = new int[3];
                    for (int k = 0; k < 3; k++)
                    {
                        double top = cells[iy, ix, k] + (cells[iy, ix + 1, k] - cells[iy, ix, k]) * fx;
                        double bot = cells[iy + 1, ix, k] + (cells[iy + 1, ix + 1, k] - cells[iy + 1, ix, k]) * fx;
                        ch[k] = (int)Math.Max(0, Math.Min(255, (top + (bot - top) * fy) * shade));
                    }
                    o[py * SPILL + px] = unchecked((int)0xFF000000) | (ch[0] << 16) | (ch[1] << 8) | ch[2];
                }
            }
            return o;
        }

        static double Smooth(double t) { return t * t * (3 - 2 * t); }
        static double Clamp01(double v) { return Math.Max(0, Math.Min(1, v)); }
        static int WithAlpha(int argb, double cover) { return ((int)Math.Round(255 * cover) << 24) | (argb & 0xFFFFFF); }

        static int Mix(int a, int b, double t)
        {
            return unchecked((int)0xFF000000) | Colors.Mix(a, b, t);
        }
    }
}
