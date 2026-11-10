using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Threading;

namespace SpotiCraftOverlay
{
    /// <summary>
    /// Spotify state, polling and actions (port of the non-Minecraft half of SpotifyHudClient.java).
    /// Network work runs on one worker thread so commands and polls never overlap.
    /// </summary>
    sealed class Controller
    {
        public static readonly HttpClient Http;

        static Controller()
        {
            try { ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12; } catch (Exception) { }
            Http = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
            Http.DefaultRequestHeaders.UserAgent.ParseAdd("SpotiCraftOverlay/1.0");
        }

        readonly BlockingCollection<Action> work = new BlockingCollection<Action>();
        public readonly ConcurrentQueue<AlbumArt> ToRelease = new ConcurrentQueue<AlbumArt>();
        public readonly string TokenFile = Path.Combine(OverlayConfig.Dir, "token.bin");

        public volatile OverlayConfig Config;
        volatile SpotifyAuth auth;
        volatile SpotifyApi api;

        public volatile PlaybackState State = PlaybackState.Inactive;
        public volatile AlbumArt Art;
        // track-change slide
        public volatile PlaybackState PrevState;
        public volatile AlbumArt PrevArt;
        public long TransStart;
        public volatile int TransDir = 1;
        public volatile PlaybackState LastFull;
        public const double TransitionSec = 0.45;
        volatile int skipDir;
        long skipDirAt;
        string beforeLastKey = "", currentTrack = "";
        readonly object trackLock = new object();

        int polling;
        long nextPollAt, lastCommandAt;
        volatile string status = "";
        long statusUntil;

        public Controller()
        {
            var t = new Thread(() =>
            {
                foreach (Action a in work.GetConsumingEnumerable())
                {
                    try { a(); } catch (Exception) { }
                }
            });
            t.IsBackground = true;
            t.Name = "SpotiCraft-IO";
            t.Start();
            string note;
            Reload(out note);
            if (note.Length > 0) SetStatus(note, 6000);
        }

        public static long Now() { return PlaybackState.Now(); }

        public void Reload(out string note)
        {
            OverlayConfig cfg = OverlayConfig.Load(out note);
            var a = new SpotifyAuth(cfg, TokenFile, Http);
            Config = cfg;
            auth = a;
            api = new SpotifyApi(a, Http);
            State = PlaybackState.Inactive;
            Interlocked.Exchange(ref nextPollAt, 0);
        }

        public void Save()
        {
            try { Config.Save(); }
            catch (Exception e) { SetStatus("Couldn't save settings: " + e.Message, 4000); }
        }

        public bool HasLogin { get { return auth.HasLogin; } }
        public bool LoginInProgress { get { return auth.LoginInProgress; } }
        public bool NeedsClientId { get { return string.IsNullOrWhiteSpace(Config.clientId); } }
        public string RedirectUri { get { return auth.RedirectUri; } }

        // ------------------------------------------------------------ status line

        public void SetStatus(string message, long durationMs)
        {
            status = message ?? "";
            Interlocked.Exchange(ref statusUntil, Now() + durationMs);
        }

        public string Status
        {
            get { return Now() < Interlocked.Read(ref statusUntil) ? status : ""; }
        }

        // ------------------------------------------------------------ polling

        public void Poll()
        {
            if (!auth.HasLogin) return;
            long now = Now();
            if (now < Interlocked.Read(ref nextPollAt)) return;
            if (Interlocked.CompareExchange(ref polling, 1, 0) != 0) return;
            Interlocked.Exchange(ref nextPollAt, now + Config.pollIntervalMs);
            SpotifyApi a = api;
            work.Add(() =>
            {
                try
                {
                    PlaybackState s = a.GetPlayback();
                    // right after a volume/seek command Spotify may still report the old value; skip that update
                    if (Now() - Interlocked.Read(ref lastCommandAt) > 1500) State = s;
                    TrackCheck(s);
                }
                catch (SpotifyException e)
                {
                    if (e.RetryAfterSeconds > 0) Interlocked.Exchange(ref nextPollAt, Now() + e.RetryAfterSeconds * 1000L);
                    SetStatus(e.Message, 4000);
                }
                catch (Exception e)
                {
                    SetStatus("Spotify: " + Describe(e), 4000);
                }
                finally
                {
                    Interlocked.Exchange(ref polling, 0);
                }
            });
        }

        void TrackCheck(PlaybackState s)
        {
            lock (trackLock)
            {
                string key = s.Active ? s.TrackKey() : "";
                if (key == currentTrack) return;
                bool hadTrack = currentTrack.Length > 0;
                string prevKey = currentTrack;
                currentTrack = key;
                AlbumArt a = s.Active && s.ArtUrl.Length > 0 ? new AlbumArt(s.ArtUrl) : null;
                if (a != null) a.Load(Http);
                AlbumArt old = Art;
                AlbumArt stale = PrevArt;
                if (stale != null) ToRelease.Enqueue(stale);
                PlaybackState was = LastFull;
                if (hadTrack && s.Active && was != null)
                {
                    PrevState = was;
                    PrevArt = old;
                    bool recentSkip = Now() - Interlocked.Read(ref skipDirAt) < 6000;
                    // going back: the user pressed previous, or this is the track that played just before
                    TransDir = key == beforeLastKey || (recentSkip && skipDir < 0) ? -1 : 1;
                    Interlocked.Exchange(ref TransStart, Stopwatch.GetTimestamp());
                }
                else
                {
                    PrevState = null;
                    PrevArt = null;
                    if (old != null) ToRelease.Enqueue(old);
                }
                beforeLastKey = prevKey;
                Art = a;
            }
        }

        // ------------------------------------------------------------ actions

        public void StartLogin()
        {
            auth.StartLogin(msg => SetStatus(msg, 8000));
        }

        public void Logout()
        {
            auth.Logout();
            State = PlaybackState.Inactive;
            SetStatus("Logged out", 3000);
        }

        bool RequireActive()
        {
            if (!auth.HasLogin) { SetStatus("Not logged in - log in to Spotify first", 3000); return false; }
            if (!State.Active) { SetStatus("Nothing playing - start Spotify on a device", 3000); return false; }
            return true;
        }

        void ProtectFromPoll()
        {
            long now = Now();
            Interlocked.Exchange(ref lastCommandAt, now);
            Interlocked.Exchange(ref nextPollAt, now + 1600);
        }

        public void TogglePlay()
        {
            if (!RequireActive()) return;
            bool willPlay = !State.Playing;
            State = State.WithPlaying(willPlay);
            ProtectFromPoll();
            SpotifyApi a = api;
            Run(() => { if (willPlay) a.Play(); else a.Pause(); });
        }

        public void Skip(bool forward)
        {
            if (!RequireActive()) return;
            skipDir = forward ? 1 : -1;
            Interlocked.Exchange(ref skipDirAt, Now());
            SpotifyApi a = api;
            Run(() =>
            {
                if (forward) a.Next(); else a.Previous();
                Interlocked.Exchange(ref nextPollAt, Now() + 500); // pick up the new track quickly
            });
        }

        public void SetVolume(int percent)
        {
            if (!RequireActive()) return;
            int target = Math.Max(0, Math.Min(100, percent));
            State = State.WithVolume(target);
            ProtectFromPoll();
            SpotifyApi a = api;
            Run(() => a.SetVolume(target));
        }

        public void ChangeVolume(int delta)
        {
            if (!RequireActive()) return;
            SetVolume(State.VolumePercent + delta);
        }

        public void SeekBy(long deltaMs)
        {
            if (!RequireActive()) return;
            SeekTo(State.LiveProgressMs() + deltaMs);
        }

        public void SeekTo(long targetMs)
        {
            if (!RequireActive()) return;
            PlaybackState s = State;
            long max = s.DurationMs > 0 ? s.DurationMs - 500 : long.MaxValue;
            long target = Math.Max(0, Math.Min(targetMs, max));
            State = s.WithProgress(target);
            ProtectFromPoll();
            SpotifyApi a = api;
            Run(() => a.Seek(target));
        }

        public void ToggleShuffle()
        {
            if (!RequireActive()) return;
            bool on = !State.Shuffle;
            State = State.WithShuffle(on);
            ProtectFromPoll();
            SpotifyApi a = api;
            Run(() => a.SetShuffle(on));
        }

        public void CycleRepeat()
        {
            if (!RequireActive()) return;
            int mode = (State.Repeat + 1) % 3;
            State = State.WithRepeat(mode);
            ProtectFromPoll();
            SpotifyApi a = api;
            Run(() => a.SetRepeat(mode));
        }

        void Run(Action action)
        {
            work.Add(() =>
            {
                try
                {
                    action();
                }
                catch (SpotifyException e)
                {
                    Interlocked.Exchange(ref lastCommandAt, 0); // undo optimistic UI on the next poll
                    long next = Now() + (e.RetryAfterSeconds > 0 ? e.RetryAfterSeconds * 1000L : 300);
                    Interlocked.Exchange(ref nextPollAt, next);
                    SetStatus(e.Message, 4000);
                }
                catch (Exception e)
                {
                    Interlocked.Exchange(ref lastCommandAt, 0);
                    Interlocked.Exchange(ref nextPollAt, Now() + 300);
                    SetStatus("Spotify: " + Describe(e), 4000);
                }
            });
        }

        static string Describe(Exception e)
        {
            Exception inner = e;
            while (inner.InnerException != null) inner = inner.InnerException;
            return string.IsNullOrEmpty(inner.Message) ? inner.GetType().Name : inner.Message;
        }

        // ------------------------------------------------------------ beat glow preset

        /// <summary>
        /// Turns audio-reactive glow on. If no links exist yet, wires a starter set: glow intensity pumps on
        /// each beat and the glow spreads with the bass. Any setting can be linked in the config (Customize UI later).
        /// </summary>
        public void ToggleBeatGlow()
        {
            OverlayConfig cfg = Config;
            cfg.beatEnabled = !cfg.beatEnabled;
            if (cfg.beatEnabled && cfg.links.Count == 0)
            {
                cfg.links.Add(new Link { param = "glowStrength", src = 5, amount = 40, smooth = 30 });
                cfg.links.Add(new Link { param = "glowSpread", src = 1, amount = 35, smooth = 45 });
                if (!cfg.glow) cfg.glow = true;
            }
            Save();
        }
    }
}
