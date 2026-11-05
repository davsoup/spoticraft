using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

// Spotify HUD beat helper: captures the audio of one app (process loopback) or of the whole PC,
// runs a small FFT and prints "S bass mid treble rms" lines. It never records or stores any audio.
public static class SpotifyHudBeat
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

    [DllImport("kernel32.dll")]
    static extern IntPtr CreateEvent(IntPtr attrs, bool manualReset, bool initialState, string name);

    [DllImport("kernel32.dll")]
    static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);

    [DllImport("kernel32.dll")]
    static extern bool CloseHandle(IntPtr handle);

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

    static TextWriter outw;
    static int mode;
    static int parentPid;

    public static void Run(int modeArg, int parentPidArg)
    {
        mode = modeArg;
        parentPid = parentPidArg;
        outw = new StreamWriter(Console.OpenStandardOutput());
        ((StreamWriter)outw).AutoFlush = true;
        Thread t = new Thread(Work);
        t.SetApartmentState(ApartmentState.MTA);
        t.Start();
        t.Join();
    }

    static void Say(string s)
    {
        outw.WriteLine("# " + s);
    }

    static bool ParentAlive()
    {
        if (parentPid <= 0) return true;
        try
        {
            Process p = Process.GetProcessById(parentPid);
            return !p.HasExited;
        }
        catch (Exception)
        {
            return false;
        }
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

    static void Work()
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

            while (ParentAlive())
            {
                uint pid;
                int loopMode;
                if (mode == 0)
                {
                    int sp = FindSpotify();
                    if (sp == 0)
                    {
                        Say("Spotify desktop app not running");
                        Thread.Sleep(2000);
                        continue;
                    }
                    pid = (uint)sp;
                    loopMode = 0;
                }
                else
                {
                    pid = (uint)Process.GetCurrentProcess().Id; // exclude only this silent helper = everything else
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
                                             FLAG_LOOPBACK | FLAG_EVENT | FLAG_NOPERSIST | FLAG_AUTOCONVERT | FLAG_SRC,
                                             FLAG_LOOPBACK | FLAG_NOPERSIST | FLAG_AUTOCONVERT | FLAG_SRC };
                bool classic = false;
                for (int fi = 0; fi < flagSets.Length && client == null; fi++)
                {
                    try
                    {
                        Handler h;
                        IAudioClient c = Activate(pid, loopMode, out h);
                        if (c == null) { why = "activate=" + h.Result.ToString("X"); continue; }
                        int hr = c.Initialize(0, flagSets[fi], 2000000, 0, fmt, IntPtr.Zero);
                        if (hr < 0) { why = "init=" + hr.ToString("X"); continue; }
                        if ((flagSets[fi] & FLAG_EVENT) != 0) c.SetEventHandle(evt);
                        client = c;
                    }
                    catch (Exception ex)
                    {
                        why = ex.GetType().Name + " " + ex.Message.Replace('\n', ' ');
                    }
                }
                if (client == null)
                {
                    // Per-app capture isn't available (older Windows, or it was refused): fall back to everything the PC plays.
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
                    Say("Could not start audio capture (" + why + ")");
                    Marshal.FreeHGlobal(fmt);
                    CloseHandle(evt);
                    Thread.Sleep(4000);
                    continue;
                }

                object svc;
                Guid capIid = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
                client.GetService(ref capIid, out svc);
                IAudioCaptureClient cap = (IAudioCaptureClient)svc;
                client.Start();
                Say(classic ? (mode == 0 ? "Listening to all PC audio (Spotify-only unavailable: " + why + ")" : "Listening to all PC audio") : (mode == 0 ? "Listening to Spotify" : "Listening to all PC audio"));

                Stopwatch since = Stopwatch.StartNew();
                Stopwatch emit = Stopwatch.StartNew();
                int checkPid = 0;
                long lastCheck = Environment.TickCount;
                bool restart = false;
                while (!restart && ParentAlive())
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
                        int bpf = chans * (bits / 8);
                        for (int i = 0; i < count; i++)
                        {
                            double m = 0;
                            if (!silent)
                            {
                                for (int ch = 0; ch < chans; ch++)
                                {
                                    int off = i * bpf + ch * (bits / 8);
                                    if (isFloat) m += BitConverter.ToSingle(BitConverter.GetBytes(Marshal.ReadInt32(data, off)), 0) * 32768.0;
                                    else if (bits == 16) m += Marshal.ReadInt16(data, off);
                                    else if (bits == 32) m += Marshal.ReadInt32(data, off) / 65536.0;
                                    else if (bits == 24) m += (Marshal.ReadInt32(data, off - 1) >> 8) / 256.0;
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
                        outw.WriteLine("S " + bass.ToString("0.000000", CultureInfo.InvariantCulture) + " "
                            + mid.ToString("0.000000", CultureInfo.InvariantCulture) + " "
                            + treble.ToString("0.000000", CultureInfo.InvariantCulture) + " "
                            + rms.ToString("0.000000", CultureInfo.InvariantCulture));
                    }

                    if (mode == 0 && !classic && Environment.TickCount - lastCheck > 2000)
                    {
                        lastCheck = Environment.TickCount;
                        checkPid = FindSpotify();
                        if (checkPid != (int)pid) restart = true;   // Spotify was restarted or closed
                    }
                }
                try { client.Stop(); } catch (Exception) { }
                Marshal.FreeHGlobal(fmt);
                CloseHandle(evt);
            }
        }
        catch (Exception e)
        {
            Say("Helper error: " + e.GetType().Name + " " + e.Message.Replace('\n', ' '));
        }
    }

    static double Band(double[] re, double[] im, int rate, int n, double lo, double hi)
    {
        int a = Math.Max(1, (int)(lo * n / rate));
        int b = Math.Min(n / 2 - 1, (int)(hi * n / rate));
        if (b < a) b = a;
        double sum = 0;
        for (int i = a; i <= b; i++) sum += Math.Sqrt(re[i] * re[i] + im[i] * im[i]);
        return sum / (b - a + 1);
    }

    static void Fft(double[] re, double[] im)
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
