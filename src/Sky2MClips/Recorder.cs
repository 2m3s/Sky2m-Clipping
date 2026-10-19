using Sky2MClips.Audio;
using Sky2MClips.Native;
using Sky2MClips.Video;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.MediaFoundation;

namespace Sky2MClips;

/// <summary>What the mod asks for (the "config" message).</summary>
internal sealed record Config
{
    public int Pid { get; init; }
    public int Seconds { get; init; } = 30;
    public int Fps { get; init; } = 60;
    /// <summary>Clip height in pixels; 0 = the window's own size.</summary>
    public int Height { get; init; } = 1080;
    public int BitrateKbps { get; init; } = 0;
    /// <summary>"desktop", "game", "game_discord" or "none".</summary>
    public string Audio { get; init; } = "game";
    public bool Mic { get; init; }
    public int GameVolume { get; init; } = 100;
    public int DiscordVolume { get; init; } = 100;
    public int DesktopVolume { get; init; } = 100;
    public int MicVolume { get; init; } = 100;
    public string Folder { get; init; } = "";
    /// <summary>Test mode: a different process's sound as "the game" (0 = the game window's process).</summary>
    public int AudioPid { get; init; }
    /// <summary>Test mode: a window found by its title instead of the game's.</summary>
    public string? WindowTitle { get; init; }

    /// <summary>A bitrate for the size and frame rate when none is set (about 12 Mbit/s for 1080p60).</summary>
    public int Bitrate(int width, int height) =>
        BitrateKbps > 0 ? BitrateKbps : (int)Math.Clamp(width * (long)height * Fps * 0.1 / 1000, 2_000, 50_000);
}

/// <summary>
/// The whole recorder: finds the game window, captures and encodes it into the replay buffer with the chosen sounds, and
/// saves the last N seconds as an MP4 on request. Recording starts by itself when the window appears and stops when it
/// closes; the mod only sends settings and "save".
/// </summary>
internal sealed class Recorder : IDisposable
{
    private readonly object _lock = new();
    private ID3D11Device? _device;
    private Config _config = new();
    private ReplayBuffer _buffer = new(30);
    private WindowCapture? _capture;
    private VideoEncoder? _encoder;
    private AudioRecorder? _audio;
    private readonly Timer _watch;
    private long _started;
    private int _pid;
    private bool _disposed;

    public event Action<string, object>? Status;

    public Recorder()
    {
        MediaFactory.MFStartup(false).CheckError();
        _watch = new Timer(_ => Watch(), null, 0, 2000);
    }

    public string EncoderName => _encoder?.EncoderName ?? "";

    public void Configure(Config config)
    {
        lock (_lock)
        {
            bool restartVideo = _config.Pid != config.Pid || _config.Fps != config.Fps || _config.Height != config.Height
                                || _config.BitrateKbps != config.BitrateKbps || _config.WindowTitle != config.WindowTitle;
            bool restartAudio = restartVideo || _config.Audio != config.Audio || _config.Mic != config.Mic;
            _config = config;
            _buffer.SetLength(config.Seconds);
            if (restartVideo) StopVideo();
            if (restartAudio)
            {
                _audio?.Dispose();
                _audio = null;
                _buffer.Clear();
            }
            _audio?.SetVolumes(config);
        }
        Watch();
    }

    /// <summary>Every 2 s: start capturing when the window appears, stop when it's gone, (re)attach sound sources.</summary>
    private void Watch()
    {
        lock (_lock)
        {
            if (_disposed) return;
            try
            {
                if (_capture != null && (_capture.Closed || !Win32.IsWindow(_capture.Window)))
                {
                    StopVideo();
                    Status?.Invoke("status", new { recording = false, reason = "the game window closed" });
                }
                if (_capture == null)
                {
                    IntPtr hwnd;
                    int pid = _config.Pid;
                    if (!string.IsNullOrEmpty(_config.WindowTitle)) (hwnd, pid) = Win32.WindowByTitle(_config.WindowTitle);
                    else hwnd = pid > 0 ? Win32.MainWindowOf(pid) : IntPtr.Zero;
                    if (hwnd != IntPtr.Zero) StartVideo(hwnd, pid);
                }
                if (_capture != null && _audio == null) _audio = new AudioRecorder(_config with { Pid = _config.AudioPid > 0 ? _config.AudioPid : _pid }, _buffer, _started);
                _audio?.Refresh();
            }
            catch (Exception e)
            {
                Log.Error("watch", e);
            }
        }
    }

    private void StartVideo(IntPtr hwnd, int pid)
    {
        _device ??= CreateDevice();
        _buffer.Clear();
        _started = Clock.Now;
        _pid = pid;
        _capture = new WindowCapture(_device, hwnd, OnFrame);
        Log.Info($"capturing window {hwnd} of process {pid}");
        Status?.Invoke("status", new { recording = true });
    }

    private void StopVideo()
    {
        _capture?.Dispose();
        _capture = null;
        _encoder?.Dispose();
        _encoder = null;
        _audio?.Dispose();
        _audio = null;
    }

    private static ID3D11Device CreateDevice()
    {
        D3D11.D3D11CreateDevice(null, DriverType.Hardware, DeviceCreationFlags.BgraSupport | DeviceCreationFlags.VideoSupport,
            new[] { FeatureLevel.Level_11_1, FeatureLevel.Level_11_0 }, out ID3D11Device? device).CheckError();
        using (ID3D11Multithread mt = device!.QueryInterface<ID3D11Multithread>()) mt.SetMultithreadProtected(true);
        return device;
    }

    private void OnFrame(ID3D11Texture2D texture, int width, int height, long time)
    {
        VideoEncoder? encoder = _encoder;
        if (encoder == null)
        {
            lock (_lock)
            {
                if (_capture == null) return;
                if (_encoder == null)
                {
                    // The clip's size is fixed from the first frame; later window resizes are letterboxed into it.
                    int h = _config.Height <= 0 ? height : Math.Min(_config.Height, height);
                    h = Math.Max(144, h) & ~1;
                    int w = Math.Max(256, (int)Math.Round(h * (double)width / height)) & ~1;
                    _encoder = new VideoEncoder(_device!, _buffer, w, h, _config.Fps, _config.Bitrate(w, h));
                    Status?.Invoke("status", new { recording = true, encoder = _encoder.EncoderName, width = w, height = h });
                }
                encoder = _encoder;
            }
        }
        encoder.Submit(texture, width, height, time);
    }

    /// <summary>Saves the last N seconds. Returns the file and its length in seconds.</summary>
    public (string path, double seconds) Save(string name)
    {
        VideoEncoder? encoder;
        Config config;
        IMFMediaType? audioType;
        lock (_lock)
        {
            encoder = _encoder;
            config = _config;
            audioType = _audio?.OutputType;
        }
        if (encoder?.OutputType == null) throw new InvalidOperationException("Not recording yet (is the game window open?).");
        var (video, audio) = _buffer.Snapshot(config.Seconds);
        string folder = string.IsNullOrWhiteSpace(config.Folder)
            ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyVideos), "Sky2M Clips")
            : config.Folder;
        Directory.CreateDirectory(folder);
        string safe = string.Concat((name ?? "").Where(c => !Path.GetInvalidFileNameChars().Contains(c))).Trim();
        string file = Path.Combine(folder, $"Sky2M {DateTime.Now:yyyy-MM-dd HH-mm-ss}{(safe.Length > 0 ? " " + safe : "")}.mp4");
        double seconds = ClipWriter.Write(file, video, audio, encoder.OutputType, encoder.SequenceHeader, audioType);
        Log.Info($"saved {file} ({seconds:0.0} s, {video.Count} frames, {audio.Count} audio packets)");
        return (file, seconds);
    }

    public void Dispose()
    {
        lock (_lock)
        {
            _disposed = true;
            _watch.Dispose();
            StopVideo();
            _device?.Dispose();
        }
        try { MediaFactory.MFShutdown(); } catch { }
    }
}
