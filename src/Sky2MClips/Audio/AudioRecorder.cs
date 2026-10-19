using Sky2MClips.Native;
using Vortice.MediaFoundation;

namespace Sky2MClips.Audio;

/// <summary>
/// One sound source (the game, Discord, everything you hear, or the microphone): a WASAPI capture thread filling a
/// small queue of 48 kHz stereo floats, which the mixer takes from.
/// </summary>
internal sealed class AudioSource : IDisposable
{
    private readonly Wasapi.IAudioClient _client;
    private readonly Wasapi.IAudioCaptureClient _capture;
    private readonly AutoResetEvent _wake = new(false);
    private readonly Thread _thread;
    private readonly object _lock = new();
    private float[] _ring = new float[Wasapi.SampleRate * Wasapi.Channels]; // 1 s
    private int _read, _count; // in floats
    private volatile bool _stopping;

    public string Name { get; }
    public int Pid { get; }
    public float Volume { get; set; } = 1f;

    private AudioSource(string name, int pid, Func<IntPtr, (Wasapi.IAudioClient, Wasapi.IAudioCaptureClient)> open)
    {
        Name = name;
        Pid = pid;
        (_client, _capture) = open(_wake.SafeWaitHandle.DangerousGetHandle());
        _thread = new Thread(Loop) { IsBackground = true, Name = "audio " + name, Priority = ThreadPriority.Highest };
        _thread.Start();
    }

    public static AudioSource Desktop() => new("Desktop", 0, Wasapi.DesktopLoopback);
    public static AudioSource Microphone() => new("Microphone", 0, Wasapi.Microphone);
    public static AudioSource Process(string name, int pid) => new(name, pid, h => Wasapi.ProcessLoopback(pid, h));

    private void Loop()
    {
        try
        {
            while (!_stopping)
            {
                _wake.WaitOne(100);
                while (!_stopping && _capture.GetNextPacketSize(out uint packet) >= 0 && packet > 0)
                {
                    if (_capture.GetBuffer(out IntPtr data, out uint frames, out uint flags, out _, out _) < 0) break;
                    Append(data, (int)frames, (flags & 0x2) != 0);
                    _capture.ReleaseBuffer(frames);
                }
            }
        }
        catch (Exception e)
        {
            if (!_stopping) Log.Error("audio " + Name, e);
        }
    }

    private unsafe void Append(IntPtr data, int frames, bool silent)
    {
        int floats = frames * Wasapi.Channels;
        lock (_lock)
        {
            // More than 300 ms waiting means the mixer is behind this source: drop the oldest so they stay in step.
            int max = Wasapi.SampleRate * Wasapi.Channels * 3 / 10;
            if (_count + floats > max)
            {
                int drop = _count + floats - max;
                _read = (_read + drop) % _ring.Length;
                _count -= Math.Min(drop, _count);
            }
            float* src = (float*)data;
            int write = (_read + _count) % _ring.Length;
            for (int i = 0; i < floats; i++)
            {
                _ring[write] = silent ? 0f : src[i];
                write = (write + 1) % _ring.Length;
            }
            _count = Math.Min(_count + floats, _ring.Length);
        }
    }

    /// <summary>Adds up to <paramref name="mix"/>.Length floats of this source into it (at its volume).</summary>
    public void MixInto(Span<float> mix)
    {
        float v = Volume;
        lock (_lock)
        {
            int n = Math.Min(mix.Length, _count);
            for (int i = 0; i < n; i++)
            {
                mix[i] += _ring[_read] * v;
                _read = (_read + 1) % _ring.Length;
            }
            _count -= n;
        }
    }

    public bool Alive => !_stopping && _thread.IsAlive && (Pid == 0 || Win32.ProcessAlive(Pid));

    public void Dispose()
    {
        _stopping = true;
        _wake.Set();
        _thread.Join(500);
        try { _client.Stop(); } catch { }
        _wake.Dispose();
    }
}

/// <summary>
/// The clip's sound: the sources the player chose (everything they hear, or only the game, or the game and Discord,
/// plus the microphone if they want), mixed every 10 ms and encoded to AAC into the replay buffer. The mixer runs
/// 60 ms behind real time so each source's audio has arrived before it is mixed.
/// </summary>
internal sealed class AudioRecorder : IDisposable
{
    private const int ChunkFrames = 480; // 10 ms
    private const long Lag = 600_000; // 60 ms

    private readonly Config _config;
    private readonly ReplayBuffer _buffer;
    private readonly List<AudioSource> _sources = new();
    private readonly object _lock = new();
    private readonly IMFTransform? _aac;
    private readonly Thread? _thread;
    private readonly long _start;
    private long _frames;
    private volatile bool _stopping;
    private long _lastDiscordTry;

    public IMFMediaType? OutputType { get; private set; }

    public AudioRecorder(Config config, ReplayBuffer buffer, long start)
    {
        _config = config;
        _buffer = buffer;
        _start = Clock.Now;
        if (config.Audio == "none" && !config.Mic) return;
        _aac = CreateAac();
        OutputType = _aac.GetOutputCurrentType(0);
        lock (_lock) OpenSources();
        _thread = new Thread(Loop) { IsBackground = true, Name = "audio mixer", Priority = ThreadPriority.AboveNormal };
        _thread.Start();
    }

    private void OpenSources()
    {
        void Try(Func<AudioSource> open, string what)
        {
            try
            {
                var s = open();
                _sources.Add(s);
                Log.Info("recording sound: " + s.Name);
            }
            catch (Exception e)
            {
                Log.Info($"couldn't record {what}: {e.Message}");
            }
        }
        switch (_config.Audio)
        {
            case "desktop":
                Try(AudioSource.Desktop, "desktop sound");
                break;
            case "game":
            case "game_discord":
                if (_config.Pid > 0) Try(() => AudioSource.Process("Game", _config.Pid), "the game's sound");
                if (_config.Audio == "game_discord") TryDiscord();
                break;
        }
        if (_config.Mic) Try(AudioSource.Microphone, "the microphone");
        SetVolumes(_config);
    }

    private void TryDiscord()
    {
        _lastDiscordTry = Environment.TickCount64;
        int discord = Win32.DiscordRoot();
        if (discord <= 0 || _sources.Any(s => s.Name == "Discord")) return;
        try
        {
            var s = AudioSource.Process("Discord", discord);
            _sources.Add(s);
            Log.Info("recording sound: Discord");
        }
        catch (Exception e)
        {
            Log.Info("couldn't record Discord: " + e.Message);
        }
    }

    public void SetVolumes(Config c)
    {
        lock (_lock)
        {
            foreach (var s in _sources)
            {
                s.Volume = s.Name switch
                {
                    "Game" => c.GameVolume,
                    "Discord" => c.DiscordVolume,
                    "Desktop" => c.DesktopVolume,
                    "Microphone" => c.MicVolume,
                    _ => 100
                } / 100f;
            }
        }
    }

    /// <summary>Every 2 s: drops sources whose program closed, and picks up Discord when it starts.</summary>
    public void Refresh()
    {
        if (_aac == null) return;
        lock (_lock)
        {
            foreach (var dead in _sources.Where(s => !s.Alive).ToList())
            {
                dead.Dispose();
                _sources.Remove(dead);
                Log.Info("stopped recording " + dead.Name + " (it closed)");
            }
            if (_config.Audio == "game_discord" && Environment.TickCount64 - _lastDiscordTry > 5000) TryDiscord();
            SetVolumes(_config);
        }
    }

    private static IMFTransform CreateAac()
    {
        var output = new RegisterTypeInfo { GuidMajorType = MF.MediaTypeAudio, GuidSubtype = MF.FormatAac };
        using IMFActivateCollection found = MediaFactory.MFTEnumEx(MF.CategoryAudioEncoder, MF.EnumSync | MF.EnumSortAndFilter, null, output);
        foreach (IMFActivate a in found)
        {
            IMFTransform t = a.ActivateObject<IMFTransform>();
            using IMFMediaType input = MediaFactory.MFCreateMediaType();
            input.Set(MF.MajorType, MF.MediaTypeAudio);
            input.Set(MF.Subtype, MF.FormatPcm);
            input.Set(MF.AudioNumChannels, (uint)Wasapi.Channels);
            input.Set(MF.AudioSamplesPerSecond, (uint)Wasapi.SampleRate);
            input.Set(MF.AudioBitsPerSample, 16u);
            input.Set(MF.AudioBlockAlignment, (uint)(Wasapi.Channels * 2));
            input.Set(MF.AudioAvgBytesPerSecond, (uint)(Wasapi.SampleRate * Wasapi.Channels * 2));
            using IMFMediaType outType = MediaFactory.MFCreateMediaType();
            outType.Set(MF.MajorType, MF.MediaTypeAudio);
            outType.Set(MF.Subtype, MF.FormatAac);
            outType.Set(MF.AudioNumChannels, (uint)Wasapi.Channels);
            outType.Set(MF.AudioSamplesPerSecond, (uint)Wasapi.SampleRate);
            outType.Set(MF.AudioBitsPerSample, 16u);
            outType.Set(MF.AudioAvgBytesPerSecond, 24000u); // 192 kbit/s
            outType.Set(MF.AacPayloadType, 0u);
            try
            {
                t.SetInputType(0, input, 0);
                t.SetOutputType(0, outType, 0);
            }
            catch
            {
                t.SetOutputType(0, outType, 0);
                t.SetInputType(0, input, 0);
            }
            t.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
            t.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);
            return t;
        }
        throw new InvalidOperationException("No AAC encoder on this computer.");
    }

    private void Loop()
    {
        var mix = new float[ChunkFrames * Wasapi.Channels];
        var pcm = new byte[ChunkFrames * Wasapi.Channels * 2];
        try
        {
            while (!_stopping)
            {
                long due = (Clock.Now - Lag - _start) * Wasapi.SampleRate / 10_000_000L;
                while (_frames + ChunkFrames <= due && !_stopping)
                {
                    Array.Clear(mix);
                    lock (_lock)
                    {
                        foreach (var s in _sources) s.MixInto(mix);
                    }
                    for (int i = 0; i < mix.Length; i++)
                    {
                        float v = Math.Clamp(mix[i], -1f, 1f);
                        short s16 = (short)(v * 32767f);
                        pcm[i * 2] = (byte)s16;
                        pcm[i * 2 + 1] = (byte)(s16 >> 8);
                    }
                    long time = _start + _frames * 10_000_000L / Wasapi.SampleRate;
                    Encode(pcm, time, ChunkFrames * 10_000_000L / Wasapi.SampleRate);
                    _frames += ChunkFrames;
                }
                Thread.Sleep(5);
            }
        }
        catch (Exception e)
        {
            if (!_stopping) Log.Error("audio mixer", e);
        }
    }

    private void Encode(byte[] pcm, long time, long duration)
    {
        using (IMFSample input = MF.Sample(pcm, time, duration, false))
        {
            _aac!.ProcessInput(0, input, 0);
        }
        int size = Math.Max(4096, _aac.GetOutputStreamInfo(0).Size);
        while (true)
        {
            IMFSample sample = MediaFactory.MFCreateSample();
            IMFMediaBuffer b = MediaFactory.MFCreateMemoryBuffer(size);
            sample.AddBuffer(b);
            b.Dispose();
            var output = new OutputDataBuffer { StreamID = 0, Sample = sample };
            var hr = _aac.ProcessOutput(ProcessOutputFlags.None, 1, ref output, out _);
            if (hr.Code == MF.ENeedMoreInput)
            {
                sample.Dispose();
                return;
            }
            hr.CheckError();
            byte[] data = MF.Bytes(sample);
            if (data.Length > 0) _buffer.AddAudio(new Encoded(data, sample.SampleTime, sample.SampleDuration, true));
            sample.Dispose();
            output.Events?.Dispose();
        }
    }

    public void Dispose()
    {
        _stopping = true;
        _thread?.Join(500);
        lock (_lock)
        {
            foreach (var s in _sources) s.Dispose();
            _sources.Clear();
        }
        _aac?.Dispose();
    }
}
