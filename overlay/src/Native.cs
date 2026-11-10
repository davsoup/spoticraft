using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace SpotiCraftOverlay
{
    static class Native
    {
        public const int WS_EX_TRANSPARENT = 0x20, WS_EX_TOOLWINDOW = 0x80, WS_EX_TOPMOST = 0x8,
            WS_EX_LAYERED = 0x80000, WS_EX_NOACTIVATE = 0x8000000;
        public const int WS_POPUP = unchecked((int)0x80000000);
        public const int GWL_EXSTYLE = -20;
        public const int WM_HOTKEY = 0x0312, WM_MOUSEACTIVATE = 0x21, MA_NOACTIVATE = 3;
        public const int ULW_ALPHA = 2;
        public const uint SWP_NOSIZE = 1, SWP_NOMOVE = 2, SWP_NOACTIVATE = 0x10, SWP_SHOWWINDOW = 0x40;
        public static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);
        public const uint MOD_ALT = 1, MOD_CONTROL = 2, MOD_SHIFT = 4, MOD_WIN = 8, MOD_NOREPEAT = 0x4000;

        [StructLayout(LayoutKind.Sequential)]
        public struct POINT { public int X, Y; }

        [StructLayout(LayoutKind.Sequential)]
        public struct SIZE { public int CX, CY; }

        [StructLayout(LayoutKind.Sequential)]
        public struct RECT { public int Left, Top, Right, Bottom; }

        [StructLayout(LayoutKind.Sequential, Pack = 1)]
        public struct BLENDFUNCTION { public byte BlendOp, BlendFlags, SourceConstantAlpha, AlphaFormat; }

        [StructLayout(LayoutKind.Sequential)]
        public struct UPDATELAYEREDWINDOWINFO
        {
            public int cbSize;
            public IntPtr hdcDst;
            public IntPtr pptDst;
            public IntPtr psize;
            public IntPtr hdcSrc;
            public IntPtr pptSrc;
            public int crKey;
            public IntPtr pblend;
            public int dwFlags;
            public IntPtr prcDirty;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct BITMAPINFOHEADER
        {
            public int biSize, biWidth, biHeight;
            public short biPlanes, biBitCount;
            public int biCompression, biSizeImage, biXPelsPerMeter, biYPelsPerMeter, biClrUsed, biClrImportant;
        }

        [DllImport("user32.dll", SetLastError = true)] public static extern bool UpdateLayeredWindowIndirect(IntPtr hwnd, ref UPDATELAYEREDWINDOWINFO info);
        [DllImport("user32.dll")] public static extern IntPtr GetDC(IntPtr hwnd);
        [DllImport("user32.dll")] public static extern int ReleaseDC(IntPtr hwnd, IntPtr hdc);
        [DllImport("user32.dll", EntryPoint = "GetWindowLong")] static extern int GetWindowLong32(IntPtr h, int i);
        [DllImport("user32.dll", EntryPoint = "GetWindowLongPtr")] static extern IntPtr GetWindowLongPtr64(IntPtr h, int i);
        [DllImport("user32.dll", EntryPoint = "SetWindowLong")] static extern int SetWindowLong32(IntPtr h, int i, int v);
        [DllImport("user32.dll", EntryPoint = "SetWindowLongPtr")] static extern IntPtr SetWindowLongPtr64(IntPtr h, int i, IntPtr v);
        [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
        [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
        [DllImport("user32.dll")] public static extern bool RegisterHotKey(IntPtr h, int id, uint mods, uint vk);
        [DllImport("user32.dll")] public static extern bool UnregisterHotKey(IntPtr h, int id);
        [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
        [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr ctx);
        [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h);
        [DllImport("gdi32.dll")] public static extern IntPtr CreateCompatibleDC(IntPtr hdc);
        [DllImport("gdi32.dll")] public static extern bool DeleteDC(IntPtr hdc);
        [DllImport("gdi32.dll")] public static extern IntPtr SelectObject(IntPtr hdc, IntPtr obj);
        [DllImport("gdi32.dll")] public static extern bool DeleteObject(IntPtr obj);
        [DllImport("gdi32.dll")] public static extern IntPtr CreateDIBSection(IntPtr hdc, ref BITMAPINFOHEADER bmi, uint usage, out IntPtr bits, IntPtr section, uint offset);
        [DllImport("winmm.dll")] public static extern uint timeBeginPeriod(uint ms);

        public static int GetExStyle(IntPtr h)
        {
            return IntPtr.Size == 8 ? (int)GetWindowLongPtr64(h, GWL_EXSTYLE).ToInt64() : GetWindowLong32(h, GWL_EXSTYLE);
        }

        public static void SetExStyle(IntPtr h, int v)
        {
            if (IntPtr.Size == 8) SetWindowLongPtr64(h, GWL_EXSTYLE, new IntPtr(v));
            else SetWindowLong32(h, GWL_EXSTYLE, v);
        }

        public static void MakeDpiAware()
        {
            try { if (SetProcessDpiAwarenessContext(new IntPtr(-4))) return; } catch (Exception) { } // per-monitor v2
            try { SetProcessDPIAware(); } catch (Exception) { }
        }
    }

    /// <summary>
    /// A 32-bit premultiplied DIB the size of the overlay, wrapped as a GDI+ bitmap. Only the dirty part of it is
    /// cleared, redrawn and pushed to the layered window each frame, so a full-screen overlay stays cheap.
    /// </summary>
    sealed class Surface : IDisposable
    {
        public readonly int W, H;
        readonly IntPtr memDc, hBmp, oldBmp;
        readonly IntPtr bits;
        public readonly Bitmap Bitmap;
        public readonly Graphics G;

        public Surface(int w, int h)
        {
            W = Math.Max(1, w);
            H = Math.Max(1, h);
            IntPtr screen = Native.GetDC(IntPtr.Zero);
            memDc = Native.CreateCompatibleDC(screen);
            Native.ReleaseDC(IntPtr.Zero, screen);
            var bi = new Native.BITMAPINFOHEADER();
            bi.biSize = Marshal.SizeOf(typeof(Native.BITMAPINFOHEADER));
            bi.biWidth = W;
            bi.biHeight = -H; // top-down
            bi.biPlanes = 1;
            bi.biBitCount = 32;
            hBmp = Native.CreateDIBSection(memDc, ref bi, 0, out bits, IntPtr.Zero, 0);
            if (hBmp == IntPtr.Zero) throw new InvalidOperationException("CreateDIBSection failed");
            oldBmp = Native.SelectObject(memDc, hBmp);
            Bitmap = new Bitmap(W, H, W * 4, PixelFormat.Format32bppPArgb, bits);
            G = Graphics.FromImage(Bitmap);
        }

        /// <summary>Fills a rectangle with one premultiplied ARGB value (fast clear).</summary>
        public unsafe void Clear(Rectangle r, uint pargb)
        {
            r.Intersect(new Rectangle(0, 0, W, H));
            if (r.Width <= 0 || r.Height <= 0) return;
            uint* p0 = (uint*)bits.ToPointer();
            for (int y = r.Top; y < r.Bottom; y++)
            {
                uint* p = p0 + (long)y * W + r.Left;
                for (int x = 0; x < r.Width; x++) p[x] = pargb;
            }
        }

        public bool Present(IntPtr hwnd, Point screenPos, Rectangle dirty)
        {
            dirty.Intersect(new Rectangle(0, 0, W, H));
            if (dirty.Width <= 0 || dirty.Height <= 0) return true;
            IntPtr pDst = Marshal.AllocHGlobal(8), pSrc = Marshal.AllocHGlobal(8), pSize = Marshal.AllocHGlobal(8),
                pBlend = Marshal.AllocHGlobal(4), pDirty = Marshal.AllocHGlobal(16);
            try
            {
                Marshal.WriteInt32(pDst, 0, screenPos.X); Marshal.WriteInt32(pDst, 4, screenPos.Y);
                Marshal.WriteInt32(pSrc, 0, 0); Marshal.WriteInt32(pSrc, 4, 0);
                Marshal.WriteInt32(pSize, 0, W); Marshal.WriteInt32(pSize, 4, H);
                Marshal.WriteInt32(pBlend, 0, 0x01FF0000); // AC_SRC_OVER, 0, 255, AC_SRC_ALPHA
                Marshal.WriteInt32(pDirty, 0, dirty.Left); Marshal.WriteInt32(pDirty, 4, dirty.Top);
                Marshal.WriteInt32(pDirty, 8, dirty.Right); Marshal.WriteInt32(pDirty, 12, dirty.Bottom);
                var info = new Native.UPDATELAYEREDWINDOWINFO();
                info.cbSize = Marshal.SizeOf(typeof(Native.UPDATELAYEREDWINDOWINFO));
                info.pptDst = pDst;
                info.psize = pSize;
                info.hdcSrc = memDc;
                info.pptSrc = pSrc;
                info.pblend = pBlend;
                info.dwFlags = Native.ULW_ALPHA;
                info.prcDirty = pDirty;
                return Native.UpdateLayeredWindowIndirect(hwnd, ref info);
            }
            finally
            {
                Marshal.FreeHGlobal(pDst); Marshal.FreeHGlobal(pSrc); Marshal.FreeHGlobal(pSize);
                Marshal.FreeHGlobal(pBlend); Marshal.FreeHGlobal(pDirty);
            }
        }

        public void Dispose()
        {
            G.Dispose();
            Bitmap.Dispose();
            Native.SelectObject(memDc, oldBmp);
            Native.DeleteObject(hBmp);
            Native.DeleteDC(memDc);
        }
    }
}
