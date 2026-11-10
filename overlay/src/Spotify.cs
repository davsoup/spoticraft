using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

namespace SpotiCraftOverlay
{
    /// <summary>Error from Spotify (or the login flow) with a message that is safe to show on the HUD.</summary>
    public class SpotifyException : Exception
    {
        public readonly int Status;
        public readonly long RetryAfterSeconds;

        public SpotifyException(int status, string message, long retryAfterSeconds) : base(message)
        {
            Status = status;
            RetryAfterSeconds = retryAfterSeconds;
        }
    }

    /// <summary>Immutable snapshot of what Spotify is doing.</summary>
    public sealed class PlaybackState
    {
        public readonly bool Active, Playing, Shuffle, VolumeSupported;
        public readonly string Title, Artist, Album, ArtUrl;
        public readonly long ProgressMs, DurationMs, FetchedAtMs;
        public readonly int VolumePercent, Repeat;

        public static readonly PlaybackState Inactive =
            new PlaybackState(false, false, "", "", "", "", 0, 0, 0, false, false, 0, 0);

        public PlaybackState(bool active, bool playing, string title, string artist, string album, string artUrl,
            long progressMs, long durationMs, int volumePercent, bool volumeSupported, bool shuffle, int repeat,
            long fetchedAtMs)
        {
            Active = active; Playing = playing; Title = title; Artist = artist; Album = album; ArtUrl = artUrl;
            ProgressMs = progressMs; DurationMs = durationMs; VolumePercent = volumePercent;
            VolumeSupported = volumeSupported; Shuffle = shuffle; Repeat = repeat; FetchedAtMs = fetchedAtMs;
        }

        public static long Now() { return DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(); }

        public string TrackKey() { return Title + "\u0001" + Artist + "\u0001" + DurationMs; }

        /// <summary>Progress extrapolated to "now" so the bar moves smoothly between polls.</summary>
        public long LiveProgressMs()
        {
            if (!Active) return 0;
            long p = ProgressMs;
            if (Playing) p += Now() - FetchedAtMs;
            p = Math.Max(0, p);
            return DurationMs > 0 ? Math.Min(p, DurationMs) : p;
        }

        PlaybackState With(bool playing, long progress, long fetched, int vol, bool shuffle, int repeat)
        {
            return new PlaybackState(Active, playing, Title, Artist, Album, ArtUrl, progress, DurationMs, vol,
                VolumeSupported, shuffle, repeat, fetched);
        }

        public PlaybackState WithShuffle(bool on) { return With(Playing, ProgressMs, FetchedAtMs, VolumePercent, on, Repeat); }
        public PlaybackState WithRepeat(int mode) { return With(Playing, ProgressMs, FetchedAtMs, VolumePercent, Shuffle, mode); }
        public PlaybackState WithVolume(int pct) { return With(Playing, ProgressMs, FetchedAtMs, pct, Shuffle, Repeat); }
        public PlaybackState WithProgress(long ms) { return With(Playing, ms, Now(), VolumePercent, Shuffle, Repeat); }
        public PlaybackState WithPlaying(bool p) { return With(p, LiveProgressMs(), Now(), VolumePercent, Shuffle, Repeat); }
    }

    /// <summary>
    /// Spotify Authorization Code flow with PKCE (no client secret). A throwaway listener on 127.0.0.1 catches the
    /// redirect, the same way the mod does, so the redirect URI you registered for the mod works here too.
    /// The refresh token is stored encrypted for the current Windows user (DPAPI).
    /// </summary>
    public sealed class SpotifyAuth
    {
        const string AuthUrl = "https://accounts.spotify.com/authorize";
        const string TokenUrl = "https://accounts.spotify.com/api/token";
        const string Scopes = "user-read-playback-state user-modify-playback-state"
            + " playlist-read-private playlist-read-collaborative user-library-read";

        readonly OverlayConfig config;
        readonly string tokenFile;
        readonly HttpClient http;
        int loginInProgress;
        readonly object gate = new object();

        volatile string accessToken;
        volatile string refreshToken;
        long expiresAtMs;

        public SpotifyAuth(OverlayConfig config, string tokenFile, HttpClient http)
        {
            this.config = config;
            this.tokenFile = tokenFile;
            this.http = http;
            LoadRefreshToken();
        }

        public bool HasLogin { get { return !string.IsNullOrWhiteSpace(refreshToken); } }
        public bool LoginInProgress { get { return Volatile.Read(ref loginInProgress) != 0; } }
        public string RedirectUri { get { return "http://127.0.0.1:" + config.redirectPort + "/callback"; } }

        /// <summary>Returns a valid access token, refreshing when it is about to expire. Blocking.</summary>
        public string AccessToken(bool forceRefresh)
        {
            lock (gate)
            {
                if (!HasLogin) throw new SpotifyException(401, "Not logged in to Spotify", 0);
                if (forceRefresh || accessToken == null || PlaybackState.Now() > expiresAtMs - 30000)
                {
                    var res = TokenRequest(new[] {
                        "grant_type", "refresh_token",
                        "refresh_token", refreshToken,
                        "client_id", config.clientId });
                    ApplyTokens(res);
                }
                return accessToken;
            }
        }

        public void Logout()
        {
            lock (gate)
            {
                accessToken = null;
                refreshToken = null;
                expiresAtMs = 0;
                try { if (File.Exists(tokenFile)) File.Delete(tokenFile); } catch (Exception) { }
            }
        }

        /// <summary>Opens the browser and finishes login in the background. say() is called from a worker thread.</summary>
        public void StartLogin(Action<string> say)
        {
            if (string.IsNullOrWhiteSpace(config.clientId))
            {
                say("Set your Spotify clientId first");
                return;
            }
            if (Interlocked.CompareExchange(ref loginInProgress, 1, 0) != 0)
            {
                say("Login already in progress - check your browser");
                return;
            }
            var t = new Thread(() =>
            {
                try { RunLogin(say); }
                catch (Exception e) { say("Login failed: " + e.Message); }
                finally { Volatile.Write(ref loginInProgress, 0); }
            });
            t.IsBackground = true;
            t.Name = "SpotiCraft-Login";
            t.Start();
        }

        void RunLogin(Action<string> say)
        {
            string verifier = RandomUrlSafe(64);
            string challenge;
            using (var sha = SHA256.Create()) challenge = Base64Url(sha.ComputeHash(Encoding.ASCII.GetBytes(verifier)));
            string state = RandomUrlSafe(16);
            string url = AuthUrl + "?" + Form(new[] {
                "response_type", "code",
                "client_id", config.clientId,
                "scope", Scopes,
                "redirect_uri", RedirectUri,
                "code_challenge_method", "S256",
                "code_challenge", challenge,
                "state", state }).Replace("+", "%20");

            var server = new TcpListener(IPAddress.Loopback, config.redirectPort);
            server.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            server.Start();
            try
            {
                say("Opening Spotify login in your browser...");
                try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
                catch (Exception) { say("Couldn't open a browser"); }
                string code = WaitForCode(server, state);
                var res = TokenRequest(new[] {
                    "grant_type", "authorization_code",
                    "code", code,
                    "redirect_uri", RedirectUri,
                    "client_id", config.clientId,
                    "code_verifier", verifier });
                ApplyTokens(res);
                say("Logged in to Spotify");
            }
            finally
            {
                server.Stop();
            }
        }

        static string WaitForCode(TcpListener server, string expectedState)
        {
            DateTime deadline = DateTime.UtcNow.AddMinutes(3);
            while (DateTime.UtcNow < deadline)
            {
                if (!server.Pending()) { Thread.Sleep(100); continue; }
                using (TcpClient s = server.AcceptTcpClient())
                {
                    s.ReceiveTimeout = 5000;
                    NetworkStream ns = s.GetStream();
                    var reader = new StreamReader(ns, Encoding.UTF8);
                    string line = reader.ReadLine(); // "GET /callback?code=...&state=... HTTP/1.1"
                    if (line == null) continue;
                    string[] parts = line.Split(' ');
                    if (parts.Length < 2 || !parts[1].StartsWith("/callback"))
                    {
                        Respond(ns, "404 Not Found", "Not found");
                        continue;
                    }
                    var q = ParseQuery(parts[1]);
                    string v;
                    if (!q.TryGetValue("state", out v) || v != expectedState)
                    {
                        Respond(ns, "400 Bad Request", "State mismatch. Try logging in again from the overlay.");
                        continue;
                    }
                    if (q.ContainsKey("error"))
                    {
                        Respond(ns, "200 OK", "Login cancelled. You can close this tab.");
                        throw new IOException("Spotify said: " + q["error"]);
                    }
                    string code;
                    if (!q.TryGetValue("code", out code) || string.IsNullOrEmpty(code))
                    {
                        Respond(ns, "400 Bad Request", "Missing code");
                        continue;
                    }
                    Respond(ns, "200 OK", "SpotiCraft Overlay is connected. You can close this tab.");
                    return code;
                }
            }
            throw new IOException("Timed out waiting for the browser login");
        }

        static void Respond(Stream s, string status, string message)
        {
            byte[] body = Encoding.UTF8.GetBytes("<!doctype html><meta charset=utf-8><title>SpotiCraft</title>"
                + "<body style=\"font-family:sans-serif;background:#0b0b0b;color:#eee;padding:3em\"><h2>"
                + WebUtility.HtmlEncode(message) + "</h2></body>");
            byte[] head = Encoding.ASCII.GetBytes("HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.Length + "\r\nConnection: close\r\n\r\n");
            s.Write(head, 0, head.Length);
            s.Write(body, 0, body.Length);
            s.Flush();
        }

        // ---------------------------------------------------------------- tokens

        object TokenRequest(string[] kv)
        {
            var req = new HttpRequestMessage(HttpMethod.Post, TokenUrl);
            req.Content = new StringContent(Form(kv), Encoding.ASCII, "application/x-www-form-urlencoded");
            HttpResponseMessage res = http.SendAsync(req).GetAwaiter().GetResult();
            string body = res.Content.ReadAsStringAsync().GetAwaiter().GetResult() ?? "";
            if ((int)res.StatusCode != 200)
            {
                if (body.Contains("invalid_grant"))
                {
                    Logout(); // refresh token revoked or expired
                    throw new SpotifyException(400, "Spotify login expired - log in again", 0);
                }
                if (body.Contains("invalid_client"))
                    throw new SpotifyException(400, "Spotify rejected your clientId", 0);
                throw new SpotifyException((int)res.StatusCode, "Spotify token error (" + (int)res.StatusCode + ")", 0);
            }
            return Json.Parse(body);
        }

        void ApplyTokens(object o)
        {
            accessToken = Json.Str(o, "access_token");
            long expiresIn = Json.Has(o, "expires_in") ? Json.Lng(o, "expires_in") : 3600L;
            expiresAtMs = PlaybackState.Now() + expiresIn * 1000L;
            string rt = Json.Str(o, "refresh_token");
            if (!string.IsNullOrEmpty(rt))
            {
                refreshToken = rt;
                SaveRefreshToken();
            }
        }

        void LoadRefreshToken()
        {
            try
            {
                if (!File.Exists(tokenFile)) return;
                byte[] data = ProtectedData.Unprotect(File.ReadAllBytes(tokenFile), null, DataProtectionScope.CurrentUser);
                refreshToken = Encoding.UTF8.GetString(data);
            }
            catch (Exception) { }
        }

        void SaveRefreshToken()
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(tokenFile));
                byte[] data = ProtectedData.Protect(Encoding.UTF8.GetBytes(refreshToken), null, DataProtectionScope.CurrentUser);
                File.WriteAllBytes(tokenFile, data);
            }
            catch (Exception) { }
        }

        // ---------------------------------------------------------------- helpers

        static string Form(string[] kv)
        {
            var sb = new StringBuilder();
            for (int i = 0; i + 1 < kv.Length; i += 2)
            {
                if (sb.Length > 0) sb.Append('&');
                sb.Append(Uri.EscapeDataString(kv[i])).Append('=').Append(Uri.EscapeDataString(kv[i + 1]));
            }
            return sb.ToString();
        }

        static Dictionary<string, string> ParseQuery(string pathAndQuery)
        {
            var m = new Dictionary<string, string>();
            int q = pathAndQuery.IndexOf('?');
            if (q < 0) return m;
            foreach (string pair in pathAndQuery.Substring(q + 1).Split('&'))
            {
                int eq = pair.IndexOf('=');
                if (eq <= 0) continue;
                m[WebUtility.UrlDecode(pair.Substring(0, eq))] = WebUtility.UrlDecode(pair.Substring(eq + 1));
            }
            return m;
        }

        static string RandomUrlSafe(int bytes)
        {
            byte[] b = new byte[bytes];
            using (var rng = RandomNumberGenerator.Create()) rng.GetBytes(b);
            return Base64Url(b);
        }

        static string Base64Url(byte[] data)
        {
            return Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        }
    }

    /// <summary>Thin blocking wrapper over the Spotify Web API player endpoints. Worker threads only.</summary>
    public sealed class SpotifyApi
    {
        const string Base = "https://api.spotify.com/v1";
        readonly SpotifyAuth auth;
        readonly HttpClient http;

        public SpotifyApi(SpotifyAuth auth, HttpClient http)
        {
            this.auth = auth;
            this.http = http;
        }

        public PlaybackState GetPlayback()
        {
            var res = Call("GET", "/me/player?additional_types=episode", null);
            int sc = res.Code;
            if (sc == 204) return PlaybackState.Inactive;
            if (sc != 200) throw Error(res);
            if (string.IsNullOrWhiteSpace(res.Body)) return PlaybackState.Inactive;
            return ParsePlayback(Json.Parse(res.Body), PlaybackState.Now());
        }

        /// <summary>Public for tests.</summary>
        public static PlaybackState ParsePlayback(object o, long now)
        {
            bool playing = Json.Bool(o, "is_playing");
            long progress = Json.Lng(o, "progress_ms");
            int volume = 0;
            bool volumeSupported = false;
            var device = Json.Obj(o, "device");
            if (device != null && Json.Has(device, "volume_percent"))
            {
                volume = (int)Json.Lng(device, "volume_percent");
                volumeSupported = true;
            }
            string title = "", artist = "", album = "", artUrl = "";
            long duration = 0;
            var item = Json.Obj(o, "item");
            if (item != null)
            {
                title = Json.Str(item, "name");
                duration = Json.Lng(item, "duration_ms");
                var artists = Json.Arr(item, "artists");
                if (artists != null && artists.Count > 0)
                {
                    var sb = new StringBuilder();
                    foreach (object a in artists)
                    {
                        string n = Json.Str(a, "name");
                        if (n.Length == 0) continue;
                        if (sb.Length > 0) sb.Append(", ");
                        sb.Append(n);
                    }
                    artist = sb.ToString();
                }
                else
                {
                    var show = Json.Obj(item, "show"); // podcast episode
                    if (show != null) artist = Json.Str(show, "name");
                }
                var alb = Json.Obj(item, "album");
                if (alb != null)
                {
                    album = Json.Str(alb, "name");
                    artUrl = PickImage(Json.Arr(alb, "images"));
                }
                else
                {
                    artUrl = PickImage(Json.Arr(item, "images"));
                    if (artUrl.Length == 0)
                    {
                        var show = Json.Obj(item, "show");
                        if (show != null) artUrl = PickImage(Json.Arr(show, "images"));
                    }
                }
            }
            else if (Json.Str(o, "currently_playing_type") == "ad")
            {
                title = "Advertisement";
            }
            string rep = Json.Str(o, "repeat_state");
            int repeat = rep == "context" ? 1 : rep == "track" ? 2 : 0;
            return new PlaybackState(true, playing, title, artist, album, artUrl, progress, duration, volume,
                volumeSupported, Json.Bool(o, "shuffle_state"), repeat, now);
        }

        public void Play() { Command("PUT", "/me/player/play"); }
        public void Pause() { Command("PUT", "/me/player/pause"); }
        public void Next() { Command("POST", "/me/player/next"); }
        public void Previous() { Command("POST", "/me/player/previous"); }
        public void Seek(long ms) { Command("PUT", "/me/player/seek?position_ms=" + Math.Max(0L, ms)); }
        public void SetShuffle(bool on) { Command("PUT", "/me/player/shuffle?state=" + (on ? "true" : "false")); }
        public void SetRepeat(int mode) { Command("PUT", "/me/player/repeat?state=" + (mode == 1 ? "context" : mode == 2 ? "track" : "off")); }
        public void SetVolume(int pct) { Command("PUT", "/me/player/volume?volume_percent=" + Math.Max(0, Math.Min(100, pct))); }

        // ---------------------------------------------------------------- plumbing

        sealed class Res
        {
            public int Code;
            public string Body;
            public long RetryAfter;
        }

        void Command(string method, string path)
        {
            var res = Call(method, path, null);
            if (res.Code >= 200 && res.Code < 300) return;
            throw Error(res);
        }

        Res Call(string method, string path, string json)
        {
            Res r = null;
            for (int attempt = 0; attempt < 2; attempt++)
            {
                string token = auth.AccessToken(attempt > 0);
                var req = new HttpRequestMessage(new HttpMethod(method), Base + path);
                req.Headers.TryAddWithoutValidation("Authorization", "Bearer " + token);
                if (json != null) req.Content = new StringContent(json, Encoding.UTF8, "application/json");
                else if (method != "GET") req.Content = new ByteArrayContent(new byte[0]);
                HttpResponseMessage res = http.SendAsync(req).GetAwaiter().GetResult();
                r = new Res
                {
                    Code = (int)res.StatusCode,
                    Body = res.Content == null ? "" : res.Content.ReadAsStringAsync().GetAwaiter().GetResult(),
                    RetryAfter = res.Headers.RetryAfter != null && res.Headers.RetryAfter.Delta.HasValue
                        ? (long)res.Headers.RetryAfter.Delta.Value.TotalSeconds : 5L
                };
                if (r.Code != 401) break; // on 401, retry once with a freshly refreshed token
            }
            return r;
        }

        static SpotifyException Error(Res res)
        {
            int sc = res.Code;
            string reason = "", message = "";
            try
            {
                var err = Json.Obj(Json.Parse(res.Body), "error");
                if (err != null)
                {
                    reason = Json.Str(err, "reason");
                    message = Json.Str(err, "message");
                }
            }
            catch (Exception) { }
            if (sc == 429) return new SpotifyException(sc, "Spotify rate limit - retrying in " + res.RetryAfter + "s", res.RetryAfter);
            if (sc == 404 || reason == "NO_ACTIVE_DEVICE")
                return new SpotifyException(sc, "No active Spotify device - press play in Spotify first", 0);
            if (sc == 401) return new SpotifyException(sc, "Spotify login rejected - log in again", 0);
            if (sc == 403 && message.ToLowerInvariant().Contains("scope"))
                return new SpotifyException(sc, "Missing permission - log out and in again", 0);
            if (sc == 403)
            {
                string m = reason == "PREMIUM_REQUIRED" ? "Spotify Premium is required for this control"
                    : message.Length == 0 ? "Spotify refused that action" : message;
                return new SpotifyException(sc, m, 0);
            }
            return new SpotifyException(sc, message.Length == 0 ? "Spotify error " + sc : message, 0);
        }

        /// <summary>Smallest image that is at least 200px wide, else the largest available.</summary>
        static string PickImage(List<object> images)
        {
            if (images == null) return "";
            string best = "", largest = "";
            long bestW = long.MaxValue, largestW = -1;
            foreach (object i in images)
            {
                string u = Json.Str(i, "url");
                if (u.Length == 0) continue;
                long w = Json.Lng(i, "width");
                if (w >= 200 && w < bestW) { bestW = w; best = u; }
                if (w > largestW) { largestW = w; largest = u; }
            }
            return best.Length == 0 ? largest : best;
        }
    }
}
