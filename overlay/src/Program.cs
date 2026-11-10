using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Threading;
using System.Windows.Forms;

namespace SpotiCraftOverlay
{
    static class Program
    {
        public const string Version = "0.1.1";

        [STAThread]
        static void Main()
        {
            bool created;
            using (var mutex = new Mutex(true, "SpotiCraftOverlay.SingleInstance", out created))
            {
                if (!created)
                {
                    MessageBox.Show("SpotiCraft Overlay is already running (see the tray icon).", "SpotiCraft Overlay");
                    return;
                }
                Native.MakeDpiAware();
                try { Native.timeBeginPeriod(1); } catch (Exception) { }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new TrayApp());
                BeatAudio.Shutdown();
            }
        }
    }

    /// <summary>Owns the overlay and the tray icon.</summary>
    sealed class TrayApp : ApplicationContext
    {
        readonly Controller c;
        readonly OverlayForm overlay;
        readonly NotifyIcon tray;
        readonly ToolStripMenuItem controlsItem, hotkeyItem, loginItem;

        public TrayApp()
        {
            c = new Controller();
            overlay = new OverlayForm(c);

            var menu = new ContextMenuStrip();
            controlsItem = new ToolStripMenuItem("Controls", null, (s, e) => Later(() => overlay.SetInteractive(!overlay.Interactive)));
            menu.Items.Add(controlsItem);
            loginItem = new ToolStripMenuItem("Log in to Spotify", null, (s, e) =>
            {
                if (c.NeedsClientId) Later(() => { overlay.SetInteractive(true); overlay.AskClientId(); });
                else c.StartLogin();
            });
            menu.Items.Add(loginItem);
            menu.Items.Add(new ToolStripMenuItem("Set Spotify clientId...", null, (s, e) => Later(() =>
            {
                overlay.SetInteractive(true);
                overlay.AskClientId();
            })));
            hotkeyItem = new ToolStripMenuItem("Hotkey", null, (s, e) =>
            {
                c.Config.hotkeyEnabled = !c.Config.hotkeyEnabled;
                c.Save();
                overlay.RegisterHotkey();
            });
            menu.Items.Add(hotkeyItem);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("Open settings file", null, (s, e) => OpenSettings()));
            menu.Items.Add(new ToolStripMenuItem("Reload settings", null, (s, e) =>
            {
                string note;
                c.Reload(out note);
                overlay.RegisterHotkey();
                c.SetStatus(note.Length > 0 ? note : "Settings reloaded", 3000);
            }));
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("Quit", null, (s, e) => Quit()));
            menu.Opening += (s, e) =>
            {
                controlsItem.Text = (overlay.Interactive ? "Close controls" : "Controls") + "  (" + c.Config.hotkey + ")";
                hotkeyItem.Text = "Hotkey " + c.Config.hotkey + " enabled";
                hotkeyItem.Checked = c.Config.hotkeyEnabled;
                loginItem.Visible = !c.HasLogin;
            };

            tray = new NotifyIcon
            {
                Icon = MakeIcon(),
                Text = "SpotiCraft Overlay " + Program.Version,
                ContextMenuStrip = menu,
                Visible = true
            };
            tray.MouseClick += (s, e) =>
            {
                if (e.Button == MouseButtons.Left) Later(() => overlay.SetInteractive(!overlay.Interactive));
            };
            overlay.FormClosed += (s, e) => ExitThread();
            overlay.Show();

            if (c.NeedsClientId || !c.HasLogin)
            {
                tray.ShowBalloonTip(6000, "SpotiCraft Overlay",
                    "Press " + c.Config.hotkey + " (or click this icon) to connect Spotify.", ToolTipIcon.Info);
            }
        }

        /// <summary>
        /// Runs an action shortly after the tray click/menu has finished, so the focus change from the closing
        /// menu doesn't immediately knock the overlay out of interactive mode.
        /// </summary>
        static void Later(Action a)
        {
            var t = new System.Windows.Forms.Timer { Interval = 150 };
            t.Tick += (s, e) => { t.Stop(); t.Dispose(); a(); };
            t.Start();
        }

        void OpenSettings()
        {
            try
            {
                c.Save();
                Process.Start(new ProcessStartInfo("notepad.exe", "\"" + OverlayConfig.FilePath + "\"") { UseShellExecute = true });
            }
            catch (Exception)
            {
                try { Process.Start(new ProcessStartInfo(OverlayConfig.Dir) { UseShellExecute = true }); } catch (Exception) { }
            }
        }

        void Quit()
        {
            tray.Visible = false;
            c.Save();
            BeatAudio.Shutdown();
            overlay.Close();
        }

        /// <summary>Tiny vinyl icon drawn at runtime, so the exe needs no resources.</summary>
        static Icon MakeIcon()
        {
            using (var bmp = new Bitmap(32, 32))
            {
                using (var g = Graphics.FromImage(bmp))
                {
                    g.SmoothingMode = SmoothingMode.AntiAlias;
                    g.Clear(Color.Transparent);
                    using (var b = new SolidBrush(Color.FromArgb(255, 14, 14, 14))) g.FillEllipse(b, 1, 1, 30, 30);
                    using (var p = new Pen(Color.FromArgb(90, 255, 255, 255), 1f))
                    {
                        g.DrawEllipse(p, 5, 5, 22, 22);
                        g.DrawEllipse(p, 8, 8, 16, 16);
                    }
                    using (var b = new SolidBrush(Color.FromArgb(255, 29, 185, 84))) g.FillEllipse(b, 11, 11, 10, 10);
                    using (var b = new SolidBrush(Color.Black)) g.FillEllipse(b, 14.5f, 14.5f, 3, 3);
                }
                return Icon.FromHandle(bmp.GetHicon());
            }
        }
    }

    /// <summary>Asks for the Spotify app's clientId and shows the redirect URI to register.</summary>
    sealed class ClientIdDialog : Form
    {
        readonly TextBox box;
        public string ClientId { get { return box.Text.Trim(); } }

        public ClientIdDialog(string current, string redirectUri)
        {
            Text = "Connect Spotify";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            TopMost = true;
            ShowInTaskbar = false;
            BackColor = Color.FromArgb(18, 18, 18);
            ForeColor = Color.FromArgb(235, 235, 235);
            Font = new Font("Segoe UI", 9.5f);
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(470, 230);

            var info = new Label
            {
                Left = 16, Top = 14, Width = 438, Height = 92,
                Text = "1. Create an app at developer.spotify.com/dashboard (Web API).\n"
                       + "2. Add this Redirect URI to it:  " + redirectUri + "\n"
                       + "3. Paste the app's Client ID below.\n"
                       + "(Already set up for the Minecraft mod? Use the same Client ID.)"
            };
            var link = new LinkLabel
            {
                Left = 16, Top = 108, Width = 300, Height = 20, Text = "Open the Spotify developer dashboard",
                LinkColor = Color.FromArgb(29, 185, 84), ActiveLinkColor = Color.FromArgb(60, 220, 120)
            };
            link.LinkClicked += (s, e) =>
            {
                try { Process.Start(new ProcessStartInfo("https://developer.spotify.com/dashboard") { UseShellExecute = true }); }
                catch (Exception) { }
            };
            var copy = new Button
            {
                Left = 330, Top = 104, Width = 124, Height = 26, Text = "Copy redirect URI",
                FlatStyle = FlatStyle.Flat, BackColor = Color.FromArgb(40, 40, 40)
            };
            copy.Click += (s, e) => { try { Clipboard.SetText(redirectUri); } catch (Exception) { } };
            box = new TextBox
            {
                Left = 16, Top = 142, Width = 438, Text = current ?? "",
                BackColor = Color.FromArgb(32, 32, 32), ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle
            };
            var ok = new Button
            {
                Left = 270, Top = 184, Width = 90, Height = 30, Text = "Connect", DialogResult = DialogResult.OK,
                FlatStyle = FlatStyle.Flat, BackColor = Color.FromArgb(29, 185, 84), ForeColor = Color.Black
            };
            var cancel = new Button
            {
                Left = 364, Top = 184, Width = 90, Height = 30, Text = "Cancel", DialogResult = DialogResult.Cancel,
                FlatStyle = FlatStyle.Flat, BackColor = Color.FromArgb(40, 40, 40)
            };
            Controls.AddRange(new Control[] { info, link, copy, box, ok, cancel });
            AcceptButton = ok;
            CancelButton = cancel;
        }
    }
}
