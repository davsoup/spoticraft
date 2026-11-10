using System;
using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;

namespace SpotiCraftOverlay
{
    /// <summary>
    /// The overlay: a borderless, always-on-top, per-pixel-alpha window over the whole primary screen. Normally
    /// click-through; the hotkey switches to interactive mode, where the screen dims and the card can be dragged,
    /// resized and controlled (port of SpotifyScreen.java).
    /// </summary>
    sealed class OverlayForm : Form
    {
        const int HOTKEY_ID = 0x5C01;
        const uint DIM = 0x50000000; // premultiplied black at ~31 %

        readonly Controller c;
        readonly Hud hud = new Hud();
        Surface surf;
        Rectangle prevBounds = Rectangle.Empty;
        bool fullDirty = true;
        bool interactive;
        bool dialogOpen;
        bool hotkeyRegistered;
        readonly Timer timer = new Timer();
        readonly Stopwatch topmostClock = Stopwatch.StartNew();

        // panel geometry from the last frame (units)
        bool panelFull;
        float panelBottom, panelW;
        RectangleF panelRect;

        // interaction
        const int NONE = 0, MOVE = 1, SEEK = 2, VOL = 3, RESIZE = 4;
        const float BTN_W = 26, BTN_H = 14, BTN_GAP = 6, PB_GAP = 4;
        const int PB = 5;
        int drag = NONE;
        float offX, offY, anchorX, anchorY;
        int corner;
        double seekFrac;
        int volPct = -1;
        long lastVolSend;
        PointF mouse;

        public event Action InteractiveChanged;

        public OverlayForm(Controller controller)
        {
            c = controller;
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.Manual;
            Text = "SpotiCraft Overlay";
            Bounds = Screen.PrimaryScreen.Bounds;
            KeyPreview = true;
            timer.Tick += (s, e) => Frame();
            Microsoft.Win32.SystemEvents.DisplaySettingsChanged += (s, e) => BeginInvoke((Action)Resized);
        }

        protected override CreateParams CreateParams
        {
            get
            {
                CreateParams cp = base.CreateParams;
                cp.Style = Native.WS_POPUP;
                cp.ExStyle |= Native.WS_EX_LAYERED | Native.WS_EX_TRANSPARENT | Native.WS_EX_TOOLWINDOW
                              | Native.WS_EX_TOPMOST | Native.WS_EX_NOACTIVATE;
                return cp;
            }
        }

        protected override bool ShowWithoutActivation { get { return true; } }
        protected override void OnPaintBackground(PaintEventArgs e) { }
        protected override void OnPaint(PaintEventArgs e) { }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            RegisterHotkey();
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            Resized();
            timer.Interval = Math.Max(1, 1000 / c.Config.fps);
            timer.Start();
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            timer.Stop();
            if (hotkeyRegistered) Native.UnregisterHotKey(Handle, HOTKEY_ID);
            if (surf != null) surf.Dispose();
            base.OnFormClosed(e);
        }

        void Resized()
        {
            Bounds = Screen.PrimaryScreen.Bounds;
            if (surf != null) surf.Dispose();
            surf = new Surface(Width, Height);
            fullDirty = true;
        }

        float UnitScale()
        {
            float dpi = 96;
            try { dpi = Native.GetDpiForWindow(Handle); } catch (Exception) { }
            if (dpi <= 0) dpi = 96;
            return c.Config.uiScale * dpi / 96f;
        }

        // ------------------------------------------------------------ hotkey

        public string HotkeyLabel { get { return c.Config.hotkey; } }

        public void RegisterHotkey()
        {
            if (hotkeyRegistered) { Native.UnregisterHotKey(Handle, HOTKEY_ID); hotkeyRegistered = false; }
            if (!c.Config.hotkeyEnabled) return;
            uint mods, vk;
            if (!ParseHotkey(c.Config.hotkey, out mods, out vk))
            {
                c.SetStatus("Unknown hotkey \"" + c.Config.hotkey + "\" in settings", 6000);
                return;
            }
            hotkeyRegistered = Native.RegisterHotKey(Handle, HOTKEY_ID, mods | Native.MOD_NOREPEAT, vk);
            if (!hotkeyRegistered) c.SetStatus("Hotkey " + c.Config.hotkey + " is taken by another app", 6000);
        }

        public static bool ParseHotkey(string s, out uint mods, out uint vk)
        {
            mods = 0;
            vk = 0;
            if (string.IsNullOrWhiteSpace(s)) return false;
            foreach (string raw in s.Split('+'))
            {
                string p = raw.Trim();
                string l = p.ToLowerInvariant();
                if (l == "ctrl" || l == "control") mods |= Native.MOD_CONTROL;
                else if (l == "alt") mods |= Native.MOD_ALT;
                else if (l == "shift") mods |= Native.MOD_SHIFT;
                else if (l == "win") mods |= Native.MOD_WIN;
                else
                {
                    Keys k;
                    if (p.Length == 1 && char.IsDigit(p[0])) p = "D" + p;
                    if (!Enum.TryParse(p, true, out k)) return false;
                    vk = (uint)(k & Keys.KeyCode);
                }
            }
            return vk != 0;
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == Native.WM_HOTKEY && m.WParam.ToInt32() == HOTKEY_ID)
            {
                SetInteractive(!interactive);
                return;
            }
            if (m.Msg == Native.WM_MOUSEACTIVATE && !interactive)
            {
                m.Result = new IntPtr(Native.MA_NOACTIVATE);
                return;
            }
            base.WndProc(ref m);
        }

        // ------------------------------------------------------------ interactive mode

        public bool Interactive { get { return interactive; } }

        public void SetInteractive(bool on)
        {
            if (interactive == on) return;
            interactive = on;
            drag = NONE;
            int ex = Native.GetExStyle(Handle);
            if (on) ex &= ~(Native.WS_EX_TRANSPARENT | Native.WS_EX_NOACTIVATE);
            else ex |= Native.WS_EX_TRANSPARENT | Native.WS_EX_NOACTIVATE;
            Native.SetExStyle(Handle, ex);
            fullDirty = true;
            if (on)
            {
                Activate();
                Native.SetForegroundWindow(Handle);
                Focus();
            }
            else
            {
                Cursor = Cursors.Default;
                c.Save();
            }
            Frame();
            var h = InteractiveChanged;
            if (h != null) h();
        }

        protected override void OnDeactivate(EventArgs e)
        {
            base.OnDeactivate(e);
            if (interactive && !dialogOpen) SetInteractive(false); // clicked into another app
        }

        // ------------------------------------------------------------ frame

        void Frame()
        {
            if (surf == null) return;
            OverlayConfig baseCfg = c.Config;
            BeatAudio.Update(baseCfg);
            c.Poll();
            AlbumArt dead;
            while (c.ToRelease.TryDequeue(out dead)) dead.Dispose();

            if (topmostClock.ElapsedMilliseconds > 2000)
            {
                topmostClock.Restart();
                Native.SetWindowPos(Handle, Native.HWND_TOPMOST, 0, 0, 0, 0,
                    Native.SWP_NOMOVE | Native.SWP_NOSIZE | Native.SWP_NOACTIVATE);
            }

            uint clear = interactive ? DIM : 0u;
            Graphics g = surf.G;
            if (fullDirty) surf.Clear(new Rectangle(0, 0, surf.W, surf.H), clear);
            else if (!prevBounds.IsEmpty) surf.Clear(prevBounds, clear);

            float u = UnitScale();
            hud.Begin(g, u);
            g.ResetClip();
            try
            {
                Draw(baseCfg);
            }
            catch (Exception ex)
            {
                c.SetStatus("Draw error: " + ex.Message, 3000);
            }

            Rectangle nb = hud.Bounds;
            if (!nb.IsEmpty) nb.Inflate(4, 4);
            Rectangle dirty;
            if (fullDirty) dirty = new Rectangle(0, 0, surf.W, surf.H);
            else if (prevBounds.IsEmpty) dirty = nb;
            else if (nb.IsEmpty) dirty = prevBounds;
            else dirty = Rectangle.Union(prevBounds, nb);
            if (!dirty.IsEmpty) surf.Present(Handle, Location, dirty);
            prevBounds = nb;
            fullDirty = false;
        }

        void Draw(OverlayConfig baseCfg)
        {
            bool show = baseCfg.showHud || interactive;
            panelFull = false;
            if (!show) return;

            AlbumArt art = c.Art;
            // beat links are applied to a copy; interactive mode draws the real settings so dragging stays exact
            OverlayConfig cfg = interactive ? baseCfg : Mod.Apply(baseCfg, art);
            PlaybackState s = c.State;
            float x = cfg.hudX / hud.U, y = cfg.hudY / hud.U;
            float bottom = y;
            float w = Hud.BLOCK_W;
            bool full = false;

            if (c.NeedsClientId)
            {
                bottom = hud.Message(x, y, "SpotiCraft Overlay", "No Spotify clientId yet",
                    interactive ? "Click \"Set Spotify clientId\" below" : "Press " + HotkeyLabel + " to set it up");
            }
            else if (!c.HasLogin)
            {
                bottom = c.LoginInProgress
                    ? hud.Message(x, y, "SpotiCraft Overlay", "Waiting for browser login...")
                    : hud.Message(x, y, "SpotiCraft Overlay",
                        interactive ? "Click \"Log in to Spotify\" below" : "Press " + HotkeyLabel + ", then log in to Spotify");
            }
            else if (!s.Active)
            {
                if (interactive || !cfg.hideWhenIdle)
                    bottom = hud.Message(x, y, "Nothing playing", "Start Spotify on any device");
            }
            else
            {
                PlaybackState oldS = c.PrevState;
                AlbumArt oldArt = c.PrevArt;
                double p = 1.0;
                if (oldS != null)
                {
                    p = (Stopwatch.GetTimestamp() - c.TransStart) / (double)Stopwatch.Frequency / Controller.TransitionSec;
                    if (p >= 1.0)
                    {
                        if (oldArt != null) c.ToRelease.Enqueue(oldArt);
                        c.PrevState = null;
                        c.PrevArt = null;
                        oldS = null;
                        oldArt = null;
                    }
                }
                long progOv = drag == SEEK ? (long)(seekFrac * s.DurationMs) : -1;
                hud.Render(cfg, s, art, interactive, progOv, oldS, oldArt, p, c.TransDir);
                c.LastFull = s;
                RectangleF R = hud.CardRect(cfg);
                panelRect = R;
                bottom = R.Bottom;
                w = R.Width;
                full = true;
            }
            if (!full) panelRect = new RectangleF(x, y, Hud.BLOCK_W, bottom - y);
            panelFull = full;
            panelBottom = bottom;
            panelW = w;

            float below = bottom + (interactive ? RowsHeight() + 4 : 3);
            string st = c.Status;
            if (st.Length > 0 && (bottom > y || interactive))
            {
                hud.Status(st, x, below);
                below += 11;
            }
            if (interactive) DrawControls(cfg, s, x, below);
        }

        // ------------------------------------------------------------ controls (unit space)

        float RowY(int i) { return panelBottom + 4 + 18 * i; }
        float CtlW { get { return Math.Max(panelW, 170); } }
        float HalfW { get { return (CtlW - BTN_GAP) / 2; } }
        int RowCount { get { return 5; } }
        float RowsHeight() { return 18 * RowCount; }

        float ButtonX(float px, int i)
        {
            float total = PB * BTN_W + (PB - 1) * PB_GAP;
            return px + (CtlW - total) / 2 + i * (BTN_W + PB_GAP);
        }

        static bool In(PointF m, float x, float y, float w, float h)
        {
            return m.X >= x && m.X < x + w && m.Y >= y && m.Y < y + h;
        }

        static readonly string[] BgNames = { "Spill", "Solid", "None" };

        void DrawControls(OverlayConfig cfg, PlaybackState s, float x, float below)
        {
            PointF m = mouse;
            if (panelFull)
            {
                RectangleF r = panelRect;
                hud.Handle(r.Left, r.Top);
                hud.Handle(r.Right, r.Top);
                hud.Handle(r.Left, r.Bottom);
                hud.Handle(r.Right, r.Bottom);
            }
            float hw = HalfW, w = CtlW;
            // row 0: volume / login / clientId
            float r0 = RowY(0);
            if (c.NeedsClientId) hud.Button("Set Spotify clientId", x, r0, w, BTN_H, In(m, x, r0, w, BTN_H));
            else if (!c.HasLogin) hud.Button(c.LoginInProgress ? "Waiting for browser..." : "Log in to Spotify", x, r0, w, BTN_H, In(m, x, r0, w, BTN_H));
            else if (panelFull && s.VolumeSupported) hud.VolumeRow(x, r0, w, drag == VOL ? volPct : s.VolumePercent);
            // row 1: playback
            if (panelFull)
            {
                float by = RowY(1);
                string[] labels = { "Shf", "|<", s.Playing ? "||" : ">", ">|", s.Repeat == 2 ? "Rp1" : "Rpt" };
                for (int i = 0; i < PB; i++)
                {
                    float bx = ButtonX(x, i);
                    bool hover = In(m, bx, by, BTN_W, BTN_H);
                    bool on = (i == 0 && s.Shuffle) || (i == 4 && s.Repeat != 0);
                    if (i == 0 || i == 4) hud.Toggle(labels[i], bx, by, BTN_W, BTN_H, hover, on);
                    else hud.Button(labels[i], bx, by, BTN_W, BTN_H, hover);
                }
            }
            float r2 = RowY(2), r3 = RowY(3), r4 = RowY(4), x2 = x + hw + BTN_GAP;
            hud.Toggle("HUD: " + (cfg.showHud ? "On" : "Off"), x, r2, hw, BTN_H, In(m, x, r2, hw, BTN_H), cfg.showHud);
            hud.Toggle("Beat glow: " + (cfg.beatEnabled ? "On" : "Off"), x2, r2, hw, BTN_H, In(m, x2, r2, hw, BTN_H), cfg.beatEnabled);
            hud.Toggle("Glow: " + (cfg.glow ? "On" : "Off"), x, r3, hw, BTN_H, In(m, x, r3, hw, BTN_H), cfg.glow);
            hud.Button("Listen: " + (cfg.beatSource == 0 ? "Spotify" : "All audio"), x2, r3, hw, BTN_H, In(m, x2, r3, hw, BTN_H));
            hud.Button("Background: " + BgNames[cfg.bgMode], x, r4, hw, BTN_H, In(m, x, r4, hw, BTN_H));
            hud.Button(c.HasLogin ? "Log out" : "Close", x2, r4, hw, BTN_H, In(m, x2, r4, hw, BTN_H));

            if (cfg.beatEnabled)
            {
                string b = "Beat: " + BeatAudio.Status;
                b = hud.Fit(b, w + 60, 1f, false);
                hud.Text(b, x, below, unchecked((int)0xFFB8F5C8), true, 1f, false);
                hud.Mark(x, below, hud.Width(b, 1f, false) + 2, 11);
            }
            string hint = "Drag to move  ·  corners resize  ·  click bars to seek / set volume  ·  Space play/pause  ·  "
                          + "←→ seek  ·  ↑↓ volume  ·  Esc or " + HotkeyLabel + " to close";
            hud.Hint(hint, surf.W / hud.U, surf.H / hud.U);
        }

        PointF ToUnits(Point p) { return new PointF(p.X / hud.U, p.Y / hud.U); }

        int CornerAt(PointF m)
        {
            if (!panelFull) return -1;
            RectangleF r = panelRect;
            float[] xs = { r.Left, r.Right, r.Left, r.Right }, ys = { r.Top, r.Top, r.Bottom, r.Bottom };
            for (int i = 0; i < 4; i++) if (Math.Abs(m.X - xs[i]) <= 5 && Math.Abs(m.Y - ys[i]) <= 5) return i;
            return -1;
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            if (!interactive || e.Button != MouseButtons.Left) return;
            OverlayConfig cfg = c.Config;
            PlaybackState s = c.State;
            PointF m = ToUnits(e.Location);
            mouse = m;
            float x = cfg.hudX / hud.U, w = CtlW, hw = HalfW, x2 = x + hw + BTN_GAP;

            int k = CornerAt(m);
            if (k >= 0)
            {
                drag = RESIZE;
                corner = k;
                RectangleF r = panelRect;
                anchorX = k % 2 == 1 ? r.Left : r.Right;
                anchorY = k >= 2 ? r.Top : r.Bottom;
                return;
            }
            float r0 = RowY(0);
            if (In(m, x, r0, w, BTN_H))
            {
                if (c.NeedsClientId) { AskClientId(); return; }
                if (!c.HasLogin) { c.StartLogin(); return; }
                if (panelFull && s.VolumeSupported && m.X >= x + Hud.VOL_BAR_X - 3 && m.X <= x + w - Hud.VOL_RIGHT + 3)
                {
                    drag = VOL;
                    volPct = VolAt(m.X, x, w);
                    return;
                }
            }
            if (panelFull)
            {
                float by = RowY(1);
                for (int i = 0; i < PB; i++)
                {
                    if (!In(m, ButtonX(x, i), by, BTN_W, BTN_H)) continue;
                    if (i == 0) c.ToggleShuffle();
                    else if (i == 1) c.Skip(false);
                    else if (i == 2) c.TogglePlay();
                    else if (i == 3) c.Skip(true);
                    else c.CycleRepeat();
                    return;
                }
                if (hud.InBar(cfg, m.X, m.Y) && s.DurationMs > 0)
                {
                    drag = SEEK;
                    seekFrac = hud.BarFrac(cfg, m.X);
                    return;
                }
            }
            float r2 = RowY(2), r3 = RowY(3), r4 = RowY(4);
            if (In(m, x, r2, hw, BTN_H)) { cfg.showHud = !cfg.showHud; c.Save(); return; }
            if (In(m, x2, r2, hw, BTN_H)) { c.ToggleBeatGlow(); return; }
            if (In(m, x, r3, hw, BTN_H)) { cfg.glow = !cfg.glow; c.Save(); return; }
            if (In(m, x2, r3, hw, BTN_H)) { cfg.beatSource = 1 - cfg.beatSource; c.Save(); return; }
            if (In(m, x, r4, hw, BTN_H)) { cfg.bgMode = (cfg.bgMode + 1) % 3; c.Save(); return; }
            if (In(m, x2, r4, hw, BTN_H))
            {
                if (c.HasLogin) c.Logout();
                else SetInteractive(false);
                return;
            }
            if (panelRect.Contains(m))
            {
                drag = MOVE;
                offX = m.X - x;
                offY = m.Y - cfg.hudY / hud.U;
            }
        }

        int VolAt(float mx, float x, float w)
        {
            double f = (mx - (x + Hud.VOL_BAR_X)) / (w - Hud.VOL_BAR_X - Hud.VOL_RIGHT);
            return (int)Math.Round(Math.Max(0, Math.Min(1, f)) * 100);
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            base.OnMouseMove(e);
            if (!interactive) return;
            OverlayConfig cfg = c.Config;
            PointF m = ToUnits(e.Location);
            mouse = m;
            switch (drag)
            {
                case MOVE:
                {
                    int nx = (int)Math.Round((m.X - offX) * hud.U), ny = (int)Math.Round((m.Y - offY) * hud.U);
                    cfg.hudX = Math.Max(-(int)(panelRect.Width * hud.U) + 40, Math.Min(Width - 40, nx));
                    cfg.hudY = Math.Max(0, Math.Min(Height - 40, ny));
                    break;
                }
                case RESIZE:
                {
                    Hud.Layout L = Hud.GetLayout(cfg);
                    float wu = Math.Abs(m.X - anchorX);
                    float sc = Math.Max(0.5f, Math.Min(3f, wu / L.W));
                    cfg.hudScale = (float)Math.Round(sc, 3);
                    float nw = L.W * cfg.hudScale, nh = L.H * cfg.hudScale;
                    float left = corner % 2 == 1 ? anchorX : anchorX - nw;
                    float top = corner >= 2 ? anchorY : anchorY - nh;
                    cfg.hudX = (int)Math.Round(left * hud.U);
                    cfg.hudY = (int)Math.Round(top * hud.U);
                    break;
                }
                case SEEK:
                    seekFrac = hud.BarFrac(cfg, m.X);
                    break;
                case VOL:
                {
                    float x = cfg.hudX / hud.U;
                    volPct = VolAt(m.X, x, CtlW);
                    long now = Controller.Now();
                    if (now - lastVolSend > 150)
                    {
                        lastVolSend = now;
                        c.SetVolume(volPct);
                    }
                    break;
                }
                default:
                    UpdateCursor(m);
                    break;
            }
        }

        void UpdateCursor(PointF m)
        {
            int k = CornerAt(m);
            Cursor want = Cursors.Default;
            if (k == 0 || k == 3) want = Cursors.SizeNWSE;
            else if (k == 1 || k == 2) want = Cursors.SizeNESW;
            else if (panelFull && hud.InBar(c.Config, m.X, m.Y)) want = Cursors.Hand;
            else if (panelRect.Contains(m)) want = Cursors.SizeAll;
            if (Cursor != want) Cursor = want;
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            base.OnMouseUp(e);
            if (!interactive) return;
            PlaybackState s = c.State;
            if (drag == SEEK) c.SeekTo((long)(seekFrac * s.DurationMs));
            else if (drag == VOL) c.SetVolume(volPct);
            else if (drag == MOVE || drag == RESIZE) c.Save();
            drag = NONE;
        }

        protected override void OnMouseWheel(MouseEventArgs e)
        {
            base.OnMouseWheel(e);
            if (!interactive || !panelFull) return;
            if (panelRect.Contains(ToUnits(e.Location))) c.ChangeVolume(Math.Sign(e.Delta) * c.Config.volumeStep);
        }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            base.OnKeyDown(e);
            if (!interactive) return;
            OverlayConfig cfg = c.Config;
            switch (e.KeyCode)
            {
                case Keys.Escape: SetInteractive(false); break;
                case Keys.Space: c.TogglePlay(); break;
                case Keys.Left: c.SeekBy(-cfg.seekStepSeconds * 1000L); break;
                case Keys.Right: c.SeekBy(cfg.seekStepSeconds * 1000L); break;
                case Keys.Up: c.ChangeVolume(cfg.volumeStep); break;
                case Keys.Down: c.ChangeVolume(-cfg.volumeStep); break;
                default: return;
            }
            e.Handled = true;
        }

        // ------------------------------------------------------------ dialogs

        public void AskClientId()
        {
            dialogOpen = true;
            try
            {
                using (var d = new ClientIdDialog(c.Config.clientId, c.RedirectUri))
                {
                    if (d.ShowDialog(this) == DialogResult.OK)
                    {
                        c.Config.clientId = d.ClientId;
                        c.Save();
                        string note;
                        c.Reload(out note);
                        if (!string.IsNullOrWhiteSpace(c.Config.clientId)) c.StartLogin();
                    }
                }
            }
            finally
            {
                dialogOpen = false;
                if (interactive)
                {
                    Activate();
                    Native.SetForegroundWindow(Handle);
                }
            }
        }
    }
}
