using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;

namespace SpotiCraftOverlay
{
    /// <summary>
    /// Port of the mod's beat_helper.cs, running in-process on its own MTA thread instead of a PowerShell child.
    /// Captures the audio of the Spotify app (process loopback) or of the whole PC, runs a small FFT and reports
    /// bass / mid / treble / rms. It never records or stores any audio.
    /// </summary>
    public sealed class BeatCapture
    {
        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90")]
        public interface IAgileObject { }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D")]
        public interface IActivateAudioInterfaceAsyncOperation
        {
            void GetActivateResult(out int activateResult, [MarshalAs(UnmanagedType.IUnknown)] out object activatedInterface);
        }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("41D949AB-9862-444A-80F6-C261334DA5EB")]
        public interface IActivateAudioInterfaceCompletionHandler
        {
            void ActivateCompleted(IActivateAudioInterfaceAsyncOperation activateOperation);
        }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2")]
        public interface IAudioClient
        {
            [PreserveSig] int Initialize(int shareMode, int streamFlags, long hnsBufferDuration, long hnsPeriodicity, IntPtr pFormat, IntPtr audioSessionGuid);
            [PreserveSig] int GetBufferSize(out uint bufferFrames);
            [PreserveSig] int GetStreamLatency(out long latency);
            [PreserveSig] int GetCurrentPadding(out uint padding);
            [PreserveSig] int IsFormatSupported(int shareMode, IntPtr pFormat, out IntPtr closest);
            [PreserveSig] int GetMixFormat(out IntPtr format);
            [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minPeriod);
            [PreserveSig] int Start();
            [PreserveSig] int Stop();
            [PreserveSig] int Reset();
            [PreserveSig] int SetEventHandle(IntPtr eventHandle);
            [PreserveSig] int GetService(ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
        }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317")]
        public interface IAudioCaptureClient
        {
            [PreserveSig] int GetBuffer(out IntPtr data, out uint numFrames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
            [PreserveSig] int ReleaseBuffer(uint numFramesRead);
            [PreserveSig] int GetNextPacketSize(out uint numFrames);
        }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]
        public interface IMMDeviceEnumerator
        {
            [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, [MarshalAs(UnmanagedType.IUnknown)] out object devices);
            [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
        }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("D666063F-1587-4E43-81F1-B948E807363F")]
        public interface IMMDevice
        {
            [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        }

        [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
        public class MMDeviceEnumeratorComObject { }

        [DllImport("Mmdevapi.dll", ExactSpelling = true, PreserveSig = false)]
        static extern void ActivateAudioInterfaceAsync(
            [In, MarshalAs(UnmanagedType.LPWStr)] string deviceInterfacePath,
            [In, MarshalAs(UnmanagedType.LPStruct)] Guid riid,
            [In] IntPtr activationParams,
            [In] IActivateAudioInterfaceCompletionHandler completionHandler,
            out IActivateAudioInterfaceAsyncOperation activationOperation);

        [DllImport("kernel32.dll")] static extern IntPtr CreateEvent(IntPtr attrs, bool manualReset, bool initialState, string name);
        [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
        [DllImport("ole32.dll")] static extern void CoTaskMemFree(IntPtr p);

        public class Handler : IActivateAudioInterfaceCompletionHandler, IAgileObject
        {
            public ManualResetEvent Done = new ManualResetEvent(false);
            public IAudioClient Client;
            public int Result = -1;

            public void ActivateCompleted(IActivateAudioInterfaceAsyncOperation op)
            {
                try
                {
                    int hr;
                    object o;
                    op.GetActivateResult(out hr, out o);
                    Result = hr;
                    if (hr >= 0 && o != null) Client = (IAudioClient)o;
                }
                catch (Exception)
                {
                    Result = -2;
                }
                Done.Set();
            }
        }

        readonly int mode;
        readonly Action<string> say;
        readonly Action<double, double, double, double> sample;
        volatile bool stop;
        Thread thread;

        /// <param name="mode">0 = only the Spotify desktop app, 1 = all PC audio.</param>
        public BeatCapture(int mode, Action<string> say, Action<double, double, double, double> sample)
        {
            this.mode = mode;
            this.say = say;
            this.sample = sample;
        }

        public int Mode { get { return mode; } }
        public bool Alive { get { return thread != null && thread.IsAlive; } }

        public void Start()
        {
            thread = new Thread(Work);
            thread.IsBackground = true;
            thread.Name = "SpotiCraft-Beat";
            thread.SetApartmentState(ApartmentState.MTA);
            thread.Start();
        }

        public void Stop()
        {
            stop = true;
        }

        void Say(string s)
        {
            try { say(s); } catch (Exception) { }
        }

        void Nap(int ms)
        {
            for (int t = 0; t < ms && !stop; t += 100) Thread.Sleep(100);
        }

        static void AddWhy(ref string why, string code)
        {
            if (why.Length < 90) why += (why.Length > 0 ? " " : "") + code;
        }

        static int FindSpotify()
        {
            Process best = null;
            foreach (Process p in Process.GetProcessesByName("Spotify"))
            {
                try
                {
                    if (best == null || p.StartTime < best.StartTime) best = p;
                }
                catch (Exception) { }
            }
            return best == null ? 0 : best.Id;
        }

        static IAudioClient Activate(uint pid, int loopMode, out Handler hOut)
        {
            IntPtr pParams = Marshal.AllocHGlobal(12);
            IntPtr pVar = Marshal.AllocHGlobal(24);
            try
            {
                Marshal.WriteInt32(pParams, 0, 1);                 // AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
                Marshal.WriteInt32(pParams, 4, unchecked((int)pid));
                Marshal.WriteInt32(pParams, 8, loopMode);          // 0 = include process tree, 1 = exclude
                for (int i = 0; i < 24; i += 4) Marshal.WriteInt32(pVar, i, 0);
                Marshal.WriteInt16(pVar, 0, 65);                   // VT_BLOB
                Marshal.WriteInt32(pVar, 8, 12);                   // cbSize
                Marshal.WriteIntPtr(pVar, IntPtr.Size == 8 ? 16 : 12, pParams);
                Handler h = new Handler();
                hOut = h;
                IActivateAudioInterfaceAsyncOperation op;
                Guid iid = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
                ActivateAudioInterfaceAsync("VAD\\Process_Loopback", iid, pVar, h, out op);
                if (!h.Done.WaitOne(5000)) { h.Result = -3; return null; }
                return h.Client;
            }
            finally
            {
                Marshal.FreeHGlobal(pParams);
                Marshal.FreeHGlobal(pVar);
            }
        }

        const int FLAG_LOOPBACK = 0x00020000, FLAG_EVENT = 0x00040000, FLAG_NOPERSIST = 0x00080000;
        const int FLAG_AUTOCONVERT = unchecked((int)0x80000000), FLAG_SRC = 0x08000000;

        void Work()
        {
            try
            {
                int rate = 44100, chans = 2, bits = 16;
                bool isFloat = false;
                const int N = 2048;
                double[] re = new double[N], im = new double[N], win = new double[N];
                for (int i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.Cos(2 * Math.PI * i / (N - 1));
                short[] ring = new short[N];
                int ringPos = 0;

                while (!stop)
                {
                    uint pid;
                    int loopMode;
                    if (mode == 0)
                    {
                        int sp = FindSpotify();
                        if (sp == 0)
                        {
                            Say("Spotify desktop app not running");
                            Nap(2000);
                            continue;
                        }
                        pid = (uint)sp;
                        loopMode = 0;
                    }
                    else
                    {
                        pid = (uint)Process.GetCurrentProcess().Id; // exclude only this silent overlay = everything else
                        loopMode = 1;
                    }

                    IAudioClient client = null;
                    IntPtr fmt = Marshal.AllocHGlobal(18);
                    Marshal.WriteInt16(fmt, 0, 1);                 // PCM
                    Marshal.WriteInt16(fmt, 2, 2);                 // channels
                    Marshal.WriteInt32(fmt, 4, 44100);
                    Marshal.WriteInt32(fmt, 8, 44100 * 4);
                    Marshal.WriteInt16(fmt, 12, 4);                // block align
                    Marshal.WriteInt16(fmt, 14, 16);               // bits
                    Marshal.WriteInt16(fmt, 16, 0);
                    IntPtr evt = CreateEvent(IntPtr.Zero, false, false, null);
                    rate = 44100; chans = 2; isFloat = false; bits = 16;
                    string why = "";
                    int[] flagSets = new int[] { FLAG_LOOPBACK | FLAG_EVENT | FLAG_NOPERSIST,
                                                 FLAG_LOOPBACK | FLAG_EVENT,
                                                 FLAG_LOOPBACK | FLAG_EVENT | FLAG_NOPERSIST | FLAG_AUTOCONVERT | FLAG_SRC,
                                                 FLAG_LOOPBACK | FLAG_NOPERSIST,
                                                 FLAG_LOOPBACK };
                    long[] durations = new long[] { 200000, 0, 2000000 };
                    bool classic = false;
                    for (int fi = 0; fi < flagSets.Length && client == null && !stop; fi++)
                    {
                        for (int di = 0; di < durations.Length && client == null; di++)
                        {
                            try
                            {
                                Handler h;
                                IAudioClient c = Activate(pid, loopMode, out h);
                                if (c == null) { AddWhy(ref why, "act" + h.Result.ToString("X")); continue; }
                                int hr = c.Initialize(0, flagSets[fi], durations[di], 0, fmt, IntPtr.Zero);
                                if (hr < 0) { AddWhy(ref why, "f" + fi + "d" + di + "=" + hr.ToString("X")); continue; }
                                if ((flagSets[fi] & FLAG_EVENT) != 0) c.SetEventHandle(evt);
                                client = c;
                            }
                            catch (Exception ex)
                            {
                                AddWhy(ref why, ex.GetType().Name);
                            }
                        }
                    }
                    if (client == null && !stop)
                    {
                        // Per-app capture isn't available (older Windows, or refused): fall back to everything the PC plays.
                        try
                        {
                            IMMDeviceEnumerator en = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
                            IMMDevice dev;
                            int hr0 = en.GetDefaultAudioEndpoint(0, 1, out dev);
                            if (hr0 < 0) throw new Exception("no output device " + hr0.ToString("X"));
                            Guid acIid = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
                            object o;
                            int hr1 = dev.Activate(ref acIid, 23, IntPtr.Zero, out o);
                            if (hr1 < 0) throw new Exception("device activate " + hr1.ToString("X"));
                            IAudioClient c = (IAudioClient)o;
                            IntPtr mix;
                            int hr2 = c.GetMixFormat(out mix);
                            if (hr2 < 0) throw new Exception("mix format " + hr2.ToString("X"));
                            int tag = (ushort)Marshal.ReadInt16(mix, 0);
                            chans = Marshal.ReadInt16(mix, 2);
                            rate = Marshal.ReadInt32(mix, 4);
                            bits = Marshal.ReadInt16(mix, 14);
                            isFloat = tag == 3;
                            if (tag == 0xFFFE && Marshal.ReadInt32(mix, 24) == 3) isFloat = true;
                            int hr3 = c.Initialize(0, FLAG_LOOPBACK, 2000000, 0, mix, IntPtr.Zero);
                            CoTaskMemFree(mix);
                            if (hr3 < 0) throw new Exception("loopback init " + hr3.ToString("X"));
                            client = c;
                            classic = true;
                        }
                        catch (Exception ex)
                        {
                            why += " | fallback: " + ex.Message.Replace('\n', ' ');
                        }
                    }
                    if (client == null)
                    {
                        if (!stop) Say("Could not start audio capture (" + why + ")");
                        Marshal.FreeHGlobal(fmt);
                        CloseHandle(evt);
                        Nap(4000);
                        continue;
                    }

                    object svc;
                    Guid capIid = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
                    client.GetService(ref capIid, out svc);
                    IAudioCaptureClient cap = (IAudioCaptureClient)svc;
                    client.Start();
                    Say(classic ? (mode == 0 ? "Listening to all PC audio (Spotify-only unavailable: " + why + ")" : "Listening to all PC audio")
                                : (mode == 0 ? "Listening to Spotify" : "Listening to all PC audio"));

                    Stopwatch since = Stopwatch.StartNew();
                    Stopwatch emit = Stopwatch.StartNew();
                    long lastCheck = Environment.TickCount;
                    bool restart = false;
                    int bps = bits / 8;
                    while (!restart && !stop)
                    {
                        WaitForSingleObject(evt, 20);
                        uint frames;
                        bool got = false;
                        while (cap.GetNextPacketSize(out frames) >= 0 && frames > 0)
                        {
                            IntPtr data;
                            uint nf, flags;
                            ulong dp, qp;
                            if (cap.GetBuffer(out data, out nf, out flags, out dp, out qp) < 0) break;
                            int count = (int)nf;
                            bool silent = (flags & 2) != 0;
                            int bpf = chans * bps;
                            for (int i = 0; i < count; i++)
                            {
                                double m = 0;
                                if (!silent)
                                {
                                    for (int ch = 0; ch < chans; ch++)
                                    {
                                        int off = i * bpf + ch * bps;
                                        if (isFloat) m += BitConverter.ToSingle(BitConverter.GetBytes(Marshal.ReadInt32(data, off)), 0) * 32768.0;
                                        else if (bits == 16) m += Marshal.ReadInt16(data, off);
                                        else if (bits == 32) m += Marshal.ReadInt32(data, off) / 65536.0;
                                        else if (bits == 24)
                                        {
                                            int v = Marshal.ReadByte(data, off) | (Marshal.ReadByte(data, off + 1) << 8) | ((sbyte)Marshal.ReadByte(data, off + 2) << 16);
                                            m += v / 256.0;
                                        }
                                    }
                                    m /= chans;
                                }
                                ring[ringPos] = (short)Math.Max(-32768, Math.Min(32767, m));
                                ringPos = (ringPos + 1) % N;
                            }
                            cap.ReleaseBuffer(nf);
                            got = true;
                        }
                        if (got) since.Restart();
                        else if (since.ElapsedMilliseconds > 80)
                        {
                            // nothing playing: feed silence so the signal falls to zero
                            for (int i = 0; i < rate / 50; i++) { ring[ringPos] = 0; ringPos = (ringPos + 1) % N; }
                            since.Restart();
                        }

                        if (emit.ElapsedMilliseconds >= 18)
                        {
                            emit.Restart();
                            double sum = 0;
                            for (int i = 0; i < N; i++)
                            {
                                double s = ring[(ringPos + i) % N] / 32768.0;
                                sum += s * s;
                                re[i] = s * win[i];
                                im[i] = 0;
                            }
                            Fft(re, im);
                            double bass = Band(re, im, rate, N, 20, 150);
                            double mid = Band(re, im, rate, N, 150, 2000);
                            double treble = Band(re, im, rate, N, 2000, 16000);
                            double rms = Math.Sqrt(sum / N);
                            try { sample(bass, mid, treble, rms); } catch (Exception) { }
                        }

                        if (mode == 0 && !classic && Environment.TickCount - lastCheck > 2000)
                        {
                            lastCheck = Environment.TickCount;
                            if (FindSpotify() != (int)pid) restart = true;   // Spotify was restarted or closed
                        }
                    }
                    try { client.Stop(); } catch (Exception) { }
                    try { Marshal.ReleaseComObject(cap); Marshal.ReleaseComObject(client); } catch (Exception) { }
                    Marshal.FreeHGlobal(fmt);
                    CloseHandle(evt);
                }
            }
            catch (Exception e)
            {
                Say("Helper error: " + e.GetType().Name + " " + e.Message.Replace('\n', ' '));
            }
        }

        public static double Band(double[] re, double[] im, int rate, int n, double lo, double hi)
        {
            int a = Math.Max(1, (int)(lo * n / rate));
            int b = Math.Min(n / 2 - 1, (int)(hi * n / rate));
            if (b < a) b = a;
            double sum = 0;
            for (int i = a; i <= b; i++) sum += Math.Sqrt(re[i] * re[i] + im[i] * im[i]);
            return sum / (b - a + 1);
        }

        public static void Fft(double[] re, double[] im)
        {
            int n = re.Length;
            int j = 0;
            for (int i = 1; i < n; i++)
            {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) j ^= bit;
                j ^= bit;
                if (i < j)
                {
                    double tr = re[i]; re[i] = re[j]; re[j] = tr;
                    double ti = im[i]; im[i] = im[j]; im[j] = ti;
                }
            }
            for (int len = 2; len <= n; len <<= 1)
            {
                double ang = -2 * Math.PI / len;
                double wr = Math.Cos(ang), wi = Math.Sin(ang);
                for (int i = 0; i < n; i += len)
                {
                    double cr = 1, ci = 0;
                    for (int k = 0; k < len / 2; k++)
                    {
                        int u = i + k, v = i + k + len / 2;
                        double xr = re[v] * cr - im[v] * ci;
                        double xi = re[v] * ci + im[v] * cr;
                        re[v] = re[u] - xr; im[v] = im[u] - xi;
                        re[u] += xr; im[u] += xi;
                        double ncr = cr * wr - ci * wi;
                        ci = cr * wi + ci * wr;
                        cr = ncr;
                    }
                }
            }
        }
    }

    /// <summary>
    /// Turns the capture's raw band levels into normalised 0..1 signals plus beat detection (port of BeatAudio.java).
    /// </summary>
    public static class BeatAudio
    {
        static volatile float nBass, nMid, nTreble, nLevel;
        static long beatTicks;
        static volatile bool alt;
        public static volatile string Status = "Off";
        static volatile int sensitivity = 50;

        static BeatCapture cap;
        static long retryAt;

        // reader state (capture thread only)
        static double pb = 3, pm = 1, pt = 0.3, pl = 0.003;
        static double ab, am, at, al, beatAvg;
        static long lastBeat;

        public static double Signal(int src)
        {
            switch (src)
            {
                case 1: return nBass;
                case 2: return nMid;
                case 3: return nTreble;
                case 4: return nLevel;
                case 5:
                {
                    long b = Interlocked.Read(ref beatTicks);
                    if (b == 0) return 0;
                    double ms = (Stopwatch.GetTimestamp() - b) * 1000.0 / Stopwatch.Frequency;
                    return Math.Exp(-ms / 170.0);
                }
                case 6: return alt ? 1.0 : 0.0;
                default: return 0.0;
            }
        }

        /// <summary>Called every frame: starts, restarts or stops capture to match the settings.</summary>
        public static void Update(OverlayConfig cfg)
        {
            sensitivity = cfg.beatSensitivity;
            if (!cfg.beatEnabled)
            {
                StopCapture();
                Status = "Off";
                return;
            }
            bool alive = cap != null && cap.Alive;
            if (alive && cap.Mode == cfg.beatSource) return;
            if (alive) StopCapture();
            long now = Environment.TickCount;
            if (retryAt != 0 && now - retryAt < 0) return;
            retryAt = now + 3000;
            Status = "Starting audio capture...";
            BeatCapture c = null;
            c = new BeatCapture(cfg.beatSource, s => { if (cap == c) Status = s; }, (b, m, t, l) => { if (cap == c) Feed(b, m, t, l); });
            cap = c;
            c.Start();
        }

        public static void Shutdown() { StopCapture(); }

        static void StopCapture()
        {
            if (cap != null) cap.Stop();
            cap = null;
            nBass = nMid = nTreble = nLevel = 0;
        }

        static void Feed(double b, double m, double t, double l)
        {
            double gain = 0.6 + sensitivity / 100.0 * 1.4;
            pb = Math.Max(Math.Max(b, 3.0), pb * 0.9985);
            pm = Math.Max(Math.Max(m, 1.0), pm * 0.9985);
            pt = Math.Max(Math.Max(t, 0.3), pt * 0.9985);
            pl = Math.Max(Math.Max(l, 0.003), pl * 0.9985);
            ab += (b - ab) * 0.02;
            am += (m - am) * 0.02;
            at += (t - at) * 0.02;
            al += (l - al) * 0.02;
            nBass = (float)Norm(b, ab, pb, gain);
            nMid = (float)Norm(m, am, pm, gain);
            nTreble = (float)Norm(t, at, pt, gain);
            nLevel = (float)Norm(l, al, pl, gain);

            // beat: a bass onset well above its recent average
            double thr = 1.6 - 0.45 * sensitivity / 100.0;
            long now = Stopwatch.GetTimestamp();
            if (b > beatAvg * thr && b > 6.0 && (now - lastBeat) > Stopwatch.Frequency * 26 / 100)
            {
                lastBeat = now;
                Interlocked.Exchange(ref beatTicks, now);
                alt = !alt;
            }
            beatAvg += (b - beatAvg) * 0.06;
        }

        static double Norm(double v, double avg, double peak, double gain)
        {
            double lo = avg * 0.8;
            double span = Math.Max(peak - lo, 1e-9);
            double n = (v - lo) / span * gain;
            return Math.Max(0, Math.Min(1, n));
        }

        /// <summary>For tests: feed a synthetic frame.</summary>
        public static void FeedForTest(double b, double m, double t, double l) { Feed(b, m, t, l); }
    }
}
