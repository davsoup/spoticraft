using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;

namespace SpotiCraftOverlay
{
    /// <summary>One beat link: a music signal driving one setting (same format as the Minecraft mod).</summary>
    public sealed class Link
    {
        public string param = "";
        /// <summary>0 none, 1 bass, 2 mid, 3 treble, 4 level, 5 beat, 6 alternate.</summary>
        public int src = 1;
        /// <summary>-100 .. 100 (% of the setting's range).</summary>
        public int amount = 50;
        /// <summary>0 = snappy .. 100 = slow fall-off.</summary>
        public int smooth = 40;
        /// <summary>Ignore signal below this level (0 .. 90 %).</summary>
        public int gate = 0;
        /// <summary>0 linear, 1 punchy, 2 soft.</summary>
        public int curve = 0;
        /// <summary>Colour settings only: see Mod.ColorModes.</summary>
        public int mode = 0;

        [NonSerialized] internal double val;
        [NonSerialized] internal long lastTicks;
        [NonSerialized] internal int steps;
        [NonSerialized] internal bool armed = true;
    }

    /// <summary>
    /// %APPDATA%\SpotiCraftOverlay\config.json. Field names match config/spotifyhud.json from the mod, so mod settings
    /// and beat links carry over. Positions (hudX/hudY, queuePeekX/Y) are screen pixels here.
    /// </summary>
    public sealed class OverlayConfig
    {
        // ---- overlay-only
        /// <summary>Global hotkey for interactive mode, e.g. "P", "Alt+P", "Ctrl+Shift+F9".</summary>
        public string hotkey = "P";
        public bool hotkeyEnabled = true;
        /// <summary>Screen pixels per HUD unit (the mod's GUI pixel). Scaled by Windows DPI.</summary>
        public float uiScale = 1.75f;
        /// <summary>Hide the overlay while nothing is playing (it still shows in interactive mode).</summary>
        public bool hideWhenIdle = true;
        public int fps = 60;

        // ---- same as the mod
        public string clientId = "";
        public int redirectPort = 8888;
        public int volumeStep = 5;
        public int seekStepSeconds = 10;
        public int pollIntervalMs = 2000;
        public bool showHud = true;
        public bool showLyrics = true;
        public int style = 0;
        public int alignBlock = 0;
        public int alignCd = 1;
        public float titleScale = 1.5f;
        public int bgMode = 0;
        public int bgDarkness = 35;
        public bool motion = true;
        public int bgColor = -1;
        public bool glow = true;
        public bool autoFit = false;
        public bool cdText = true;
        public bool rounded = true;
        public int cdBgMode = 1;
        public bool modeIndicators = true;
        public int artOffX = 0;
        public int artOffY = 0;
        public int lyricsScale = 100;
        public int lyricsBgMode = 0;
        public int lyricsBgColor = -1;
        public float hudScale = 1.0f;
        public int cdSize = 110;
        public int alignLyrics = 0;
        public int lyricsOffset = 0;
        public bool lyricsFlash = true;
        public int glowSpread = 14;
        public int glowSoftness = 60;
        public int glowStrength = 60;
        public int glowColor = -1;
        public int textColor = 0xFFFFFF;
        public int barColor = 0xFFFFFF;
        public int hudX = 24;
        public int hudY = 80;
        public int cdDarkness = 48;
        public int vinylDarkness = 48;
        public bool vinylText = true;
        public int alignVinyl = 1;
        public int vinylBgMode = 1;
        public int vinylSize = 110;
        public bool vinylTint = false;
        public bool vinylArm = true;
        public int vinylArmColor = 0xC8C8C8;
        public bool lyricsKaraoke = true;
        public int lyricsFillColor = -1;
        public bool queuePeek = true;
        public bool queuePeekHud = false;
        public int queuePeekCount = 3;
        public int queuePeekSide = 0;
        public bool queuePeekCovers = true;
        public int queuePeekBg = 0;
        public int queuePeekDarkness = 35;
        public int queuePeekColor = 0xFFFFFF;
        public int queuePeekText = 100;
        public int queuePeekAlign = 0;
        public float queuePeekScale = 1.0f;
        public bool queuePeekCustom = false;
        public int queuePeekX = 0;
        public int queuePeekY = 0;
        public bool ambient = true;
        public int ambientSize = 90;
        public int ambientIntensity = 30;
        public int ambientColor = -1;
        public bool ambientMograph = true;
        public int ambientEdges = 0;
        public int ambientSpeed = 4;
        public bool beatEnabled = false;
        public int beatSource = 0;
        public int beatSensitivity = 50;
        public List<Link> links = new List<Link>();

        /// <summary>Shallow copy; the HUD draws from a modulated copy so saved settings are never touched.</summary>
        public OverlayConfig Copy()
        {
            return (OverlayConfig)MemberwiseClone();
        }

        public int TextAlign()
        {
            return style == 2 ? alignVinyl : style == 1 ? alignCd : alignBlock;
        }

        // ------------------------------------------------------------ load / save

        static readonly FieldInfo[] Fields = typeof(OverlayConfig).GetFields(BindingFlags.Public | BindingFlags.Instance);

        /// <summary>%APPDATA% (overridable with SPOTICRAFT_APPDATA, used by the tests).</summary>
        static string AppData
        {
            get
            {
                string o = Environment.GetEnvironmentVariable("SPOTICRAFT_APPDATA");
                return string.IsNullOrEmpty(o) ? Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData) : o;
            }
        }

        public static string Dir { get { return Path.Combine(AppData, "SpotiCraftOverlay"); } }

        public static string FilePath { get { return Path.Combine(Dir, "config.json"); } }

        /// <summary>The Minecraft mod's config in the default launcher folder (imported on first run).</summary>
        public static string ModConfigPath
        {
            get
            {
                return Path.Combine(AppData, ".minecraft", "config", "spotifyhud.json");
            }
        }

        public static OverlayConfig Load(out string note)
        {
            note = "";
            var cfg = new OverlayConfig();
            try
            {
                if (File.Exists(FilePath))
                {
                    Apply(cfg, Json.Parse(File.ReadAllText(FilePath)) as Dictionary<string, object>);
                }
                else if (File.Exists(ModConfigPath))
                {
                    // first run: take over the mod's look, links and clientId; positions are GUI pixels there, so not those
                    Apply(cfg, Json.Parse(File.ReadAllText(ModConfigPath)) as Dictionary<string, object>);
                    var d = new OverlayConfig();
                    cfg.hudX = d.hudX;
                    cfg.hudY = d.hudY;
                    cfg.queuePeekCustom = false;
                    cfg.queuePeekX = 0;
                    cfg.queuePeekY = 0;
                    note = "Imported settings from the Minecraft mod";
                }
            }
            catch (Exception e)
            {
                note = "Config unreadable, using defaults (" + e.Message + ")";
            }
            cfg.Clamp();
            try { cfg.Save(); } catch (Exception) { }
            return cfg;
        }

        static void Apply(OverlayConfig cfg, Dictionary<string, object> o)
        {
            if (o == null) return;
            foreach (FieldInfo f in Fields)
            {
                object v;
                if (!o.TryGetValue(f.Name, out v) || v == null) continue;
                try
                {
                    if (f.FieldType == typeof(int) && v is double) f.SetValue(cfg, (int)Math.Round((double)v));
                    else if (f.FieldType == typeof(float) && v is double) f.SetValue(cfg, (float)(double)v);
                    else if (f.FieldType == typeof(bool) && v is bool) f.SetValue(cfg, v);
                    else if (f.FieldType == typeof(string) && v is string) f.SetValue(cfg, v);
                    else if (f.FieldType == typeof(List<Link>) && v is List<object>)
                    {
                        var list = new List<Link>();
                        foreach (object e in (List<object>)v)
                        {
                            var lo = e as Dictionary<string, object>;
                            if (lo == null || !Json.Has(lo, "param")) continue;
                            var l = new Link();
                            l.param = Json.Str(lo, "param");
                            if (Json.Has(lo, "src")) l.src = (int)Json.Lng(lo, "src");
                            if (Json.Has(lo, "amount")) l.amount = (int)Json.Lng(lo, "amount");
                            if (Json.Has(lo, "smooth")) l.smooth = (int)Json.Lng(lo, "smooth");
                            if (Json.Has(lo, "gate")) l.gate = (int)Json.Lng(lo, "gate");
                            if (Json.Has(lo, "curve")) l.curve = (int)Json.Lng(lo, "curve");
                            if (Json.Has(lo, "mode")) l.mode = (int)Json.Lng(lo, "mode");
                            list.Add(l);
                        }
                        cfg.links = list;
                    }
                }
                catch (Exception) { }
            }
        }

        static int Cl(int v, int lo, int hi) { return Math.Max(lo, Math.Min(hi, v)); }
        static float Cl(float v, float lo, float hi) { return Math.Max(lo, Math.Min(hi, v)); }

        public void Clamp()
        {
            if (clientId == null) clientId = "";
            if (hotkey == null) hotkey = "P";
            if (links == null) links = new List<Link>();
            links.RemoveAll(l => l == null || string.IsNullOrEmpty(l.param));
            uiScale = Cl(uiScale, 0.5f, 6f);
            fps = Cl(fps, 15, 144);
            beatSource = Cl(beatSource, 0, 1);
            beatSensitivity = Cl(beatSensitivity, 0, 100);
            volumeStep = Math.Max(1, volumeStep);
            seekStepSeconds = Math.Max(1, seekStepSeconds);
            pollIntervalMs = Math.Max(1000, pollIntervalMs);
            style = Cl(style, 0, 2);
            alignBlock = Cl(alignBlock, 0, 2);
            alignCd = Cl(alignCd, 0, 2);
            titleScale = Cl(titleScale, 1f, 2f);
            bgMode = Cl(bgMode, 0, 2);
            bgDarkness = Cl(bgDarkness, 0, 80);
            glowStrength = Cl(glowStrength, 0, 100);
            hudScale = Cl(hudScale, 0.5f, 3f);
            cdSize = Cl(cdSize, 40, 220);
            alignLyrics = Cl(alignLyrics, 0, 2);
            lyricsOffset = Cl(lyricsOffset, -30, 120);
            glowSpread = Cl(glowSpread, 4, 40);
            glowSoftness = Cl(glowSoftness, 0, 100);
            cdBgMode = Cl(cdBgMode, 0, 2);
            artOffX = Cl(artOffX, -20, 20);
            artOffY = Cl(artOffY, -20, 20);
            lyricsScale = Cl(lyricsScale, 70, 200);
            lyricsBgMode = Cl(lyricsBgMode, 0, 3);
            cdDarkness = Cl(cdDarkness, 0, 80);
            vinylDarkness = Cl(vinylDarkness, 0, 80);
            alignVinyl = Cl(alignVinyl, 0, 2);
            vinylBgMode = Cl(vinylBgMode, 0, 2);
            vinylSize = Cl(vinylSize, 40, 220);
            queuePeekCount = Cl(queuePeekCount, 1, 5);
            queuePeekSide = Cl(queuePeekSide, 0, 2);
            queuePeekBg = Cl(queuePeekBg, 0, 2);
            queuePeekDarkness = Cl(queuePeekDarkness, 0, 80);
            queuePeekText = Cl(queuePeekText, 70, 200);
            queuePeekAlign = Cl(queuePeekAlign, 0, 2);
            queuePeekScale = Cl(queuePeekScale, 0.5f, 3f);
            ambientSize = Cl(ambientSize, 20, 200);
            ambientIntensity = Cl(ambientIntensity, 0, 100);
            ambientEdges = Cl(ambientEdges, 0, 2);
            ambientSpeed = Cl(ambientSpeed, 1, 10);
        }

        public void Save()
        {
            var o = new Dictionary<string, object>();
            foreach (FieldInfo f in Fields)
            {
                if (f.FieldType == typeof(List<Link>))
                {
                    var arr = new List<object>();
                    foreach (Link l in links)
                    {
                        var lo = new Dictionary<string, object>();
                        lo["param"] = l.param;
                        lo["src"] = l.src;
                        lo["amount"] = l.amount;
                        lo["smooth"] = l.smooth;
                        lo["gate"] = l.gate;
                        lo["curve"] = l.curve;
                        lo["mode"] = l.mode;
                        arr.Add(lo);
                    }
                    o[f.Name] = arr;
                }
                else
                {
                    o[f.Name] = f.GetValue(this);
                }
            }
            Directory.CreateDirectory(Dir);
            string tmp = FilePath + ".tmp";
            File.WriteAllText(tmp, Json.Write(o));
            if (File.Exists(FilePath)) File.Delete(FilePath);
            File.Move(tmp, FilePath);
        }
    }
}
