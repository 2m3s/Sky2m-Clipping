using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using Sky2MClips.Native;
using Vortice.Direct3D11;
using Vortice.DXGI;
using Vortice.MediaFoundation;
using Vortice.Mathematics;

namespace Sky2MClips.Video;

/// <summary>
/// Turns captured frames into H.264: the GPU's video processor scales each frame into a fixed-size NV12 texture
/// (letterboxed when the window's shape changes), and a Media Foundation encoder compresses it - the graphics card's own
/// encoder (NVIDIA NVENC, AMD AMF, Intel Quick Sync) when there is one, Microsoft's software encoder otherwise.
/// Encoded frames go to <see cref="ReplayBuffer"/>. A keyframe every second keeps clips starting close to the asked time.
/// </summary>
internal sealed class VideoEncoder : IDisposable
{
    private const int PoolSize = 8;

    private readonly ID3D11Device _device;
    private readonly ID3D11DeviceContext _context;
    private readonly ID3D11VideoDevice _videoDevice;
    private readonly ID3D11VideoContext _videoContext;
    private readonly ReplayBuffer _buffer;
    private readonly int _width, _height, _fps;
    private readonly long _frameTime;
    private readonly object _gpuLock = new();

    private ID3D11VideoProcessorEnumerator? _enumerator;
    private ID3D11VideoProcessor? _processor;
    private ID3D11Texture2D? _input;
    private ID3D11VideoProcessorInputView? _inputView;
    private int _inputW, _inputH;
    private readonly ID3D11Texture2D[] _nv12 = new ID3D11Texture2D[PoolSize];
    private readonly ID3D11VideoProcessorOutputView[] _outputViews = new ID3D11VideoProcessorOutputView[PoolSize];
    private int _next;

    private readonly IMFTransform _encoder;
    private readonly bool _async;
    private readonly bool _providesSamples;
    private readonly IMFDXGIDeviceManager? _manager;
    private readonly ID3D11Texture2D? _staging;
    private readonly BlockingCollection<(int slot, long time)> _queue = new(new ConcurrentQueue<(int, long)>(), PoolSize - 2);
    private readonly Thread _thread;
    private volatile bool _stopping;
    private long _lastTime = long.MinValue;
    private long _framesIn;
    private long _lastKeyRequest = long.MinValue;
    private int _lastSlot = -1;
    private readonly Timer _repeat;

    public string EncoderName { get; }
    public IMFMediaType? OutputType { get; private set; }
    public byte[]? SequenceHeader { get; private set; }

    public VideoEncoder(ID3D11Device device, ReplayBuffer buffer, int width, int height, int fps, int bitrateKbps)
    {
        _device = device;
        _context = device.ImmediateContext;
        _videoDevice = device.QueryInterface<ID3D11VideoDevice>();
        _videoContext = _context.QueryInterface<ID3D11VideoContext>();
        _buffer = buffer;
        _width = width;
        _height = height;
        _fps = fps;
        _frameTime = 10_000_000L / fps;

        for (int i = 0; i < PoolSize; i++)
        {
            _nv12[i] = device.CreateTexture2D(new Texture2DDescription
            {
                Width = (uint)width, Height = (uint)height, MipLevels = 1, ArraySize = 1, Format = Format.NV12,
                SampleDescription = new SampleDescription(1, 0), Usage = ResourceUsage.Default,
                BindFlags = BindFlags.RenderTarget | BindFlags.ShaderResource
            });
        }

        (_encoder, _async, EncoderName, _manager) = CreateEncoder(device, width, height, fps, bitrateKbps);
        _providesSamples = (_encoder.GetOutputStreamInfo(0).Flags & MF.OutputStreamProvidesSamples) != 0;
        if (!_async)
        {
            _staging = device.CreateTexture2D(new Texture2DDescription
            {
                Width = (uint)width, Height = (uint)height, MipLevels = 1, ArraySize = 1, Format = Format.NV12,
                SampleDescription = new SampleDescription(1, 0), Usage = ResourceUsage.Staging,
                CPUAccessFlags = CpuAccessFlags.Read
            });
        }
        _encoder.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
        _encoder.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);

        _thread = new Thread(_async ? AsyncLoop : SyncLoop) { IsBackground = true, Name = "video encoder", Priority = ThreadPriority.AboveNormal };
        _thread.Start();
        _repeat = new Timer(_ => RepeatLastFrame(), null, 100, 1000 / fps);
    }

    /// <summary>
    /// Windows only sends a frame when the window changes: when it hasn't (a paused or AFK game, a menu), the last
    /// picture is sent again so the clip keeps its full length.
    /// </summary>
    private void RepeatLastFrame()
    {
        if (_stopping || _lastSlot < 0) return;
        long now = Clock.Now;
        if (now - _lastTime < _frameTime * 3 / 2 || _queue.Count >= _queue.BoundedCapacity) return;
        _lastTime = now;
        int slot;
        lock (_gpuLock)
        {
            slot = _next;
            _next = (_next + 1) % PoolSize;
            _context.CopyResource(_nv12[slot], _nv12[_lastSlot]);
            _lastSlot = slot;
        }
        _queue.TryAdd((slot, now));
    }

    // ---------------------------------------------------------------- encoder setup

    private static (IMFTransform, bool, string, IMFDXGIDeviceManager?) CreateEncoder(ID3D11Device device, int w, int h, int fps, int kbps)
    {
        var output = new RegisterTypeInfo { GuidMajorType = MF.MediaTypeVideo, GuidSubtype = MF.FormatH264 };
        // Hardware first.
        try
        {
            using IMFActivateCollection hw = MediaFactory.MFTEnumEx(MF.CategoryVideoEncoder, MF.EnumHardware | MF.EnumSortAndFilter, null, output);
            foreach (IMFActivate activate in hw)
            {
                string name = SafeName(activate);
                IMFTransform? t = null;
                try
                {
                    t = activate.ActivateObject<IMFTransform>();
                    IMFAttributes attrs = t.Attributes;
                    attrs.Set(MF.TransformAsyncUnlock, 1u);
                    IMFDXGIDeviceManager manager = MediaFactory.MFCreateDXGIDeviceManager();
                    manager.ResetDevice(device).CheckError();
                    t.ProcessMessage(TMessageType.MessageSetD3DManager, (UIntPtr)(ulong)manager.NativePointer);
                    Configure(t, w, h, fps, kbps);
                    Log.Info($"video encoder: {name} (hardware)");
                    return (t, true, name, manager);
                }
                catch (Exception e)
                {
                    Log.Info($"hardware encoder {name} didn't start: {e.Message}");
                    t?.Dispose();
                }
            }
        }
        catch (Exception e)
        {
            Log.Info("no hardware encoders: " + e.Message);
        }
        // Microsoft's software encoder.
        using IMFActivateCollection sw = MediaFactory.MFTEnumEx(MF.CategoryVideoEncoder, MF.EnumSync | MF.EnumSortAndFilter, null, output);
        foreach (IMFActivate activate in sw)
        {
            string name = SafeName(activate);
            IMFTransform? t = null;
            try
            {
                t = activate.ActivateObject<IMFTransform>();
                Configure(t, w, h, fps, kbps);
                Log.Info($"video encoder: {name} (software)");
                return (t, false, name, null);
            }
            catch (Exception e)
            {
                Log.Info($"software encoder {name} didn't start: {e.Message}");
                t?.Dispose();
            }
        }
        throw new InvalidOperationException("No H.264 encoder on this computer.");
    }

    private static string SafeName(IMFActivate a)
    {
        try { return a.GetString(MF.FriendlyName); } catch { return "H.264 encoder"; }
    }

    private static void Configure(IMFTransform t, int w, int h, int fps, int kbps)
    {
        IntPtr ptr = t.NativePointer;
        MF.SetCodecValue(ptr, MF.CodecRateControlMode, 1); // peak-constrained VBR
        MF.SetCodecValue(ptr, MF.CodecMeanBitRate, (uint)kbps * 1000);
        MF.SetCodecValue(ptr, MF.CodecGopSize, (uint)fps);
        MF.SetCodecValue(ptr, MF.CodecBPictureCount, 0);
        MF.SetCodecValue(ptr, MF.LowLatency, 1);

        Exception? last = null;
        foreach (uint profile in new uint[] { 100, 77, 66 }) // High, Main, Baseline
        {
            try
            {
                using IMFMediaType outType = MF.VideoType(MF.FormatH264, w, h, fps);
                outType.Set(MF.AvgBitrate, (uint)kbps * 1000);
                outType.Set(MF.Mpeg2Profile, profile);
                t.SetOutputType(0, outType, 0);
                last = null;
                break;
            }
            catch (Exception e)
            {
                last = e;
            }
        }
        if (last != null) throw last;

        // The encoder's own NV12 input type, with our size and rate.
        for (int i = 0; ; i++)
        {
            IMFMediaType available = t.GetInputAvailableType(0, i);
            if (available.GetGUID(MF.Subtype) != MF.FormatNV12)
            {
                available.Dispose();
                continue;
            }
            available.Set(MF.FrameSize, MF.Pack((uint)w, (uint)h));
            available.Set(MF.FrameRate, MF.Pack((uint)fps, 1));
            t.SetInputType(0, available, 0);
            available.Dispose();
            break;
        }
    }

    // ---------------------------------------------------------------- frames in (capture thread)

    /// <summary>Scales a captured frame into the next free NV12 texture and queues it. Frames faster than the frame rate are skipped.</summary>
    public void Submit(ID3D11Texture2D frame, int width, int height, long time)
    {
        if (_stopping) return;
        if (_lastTime != long.MinValue && time - _lastTime < _frameTime - _frameTime / 4) return;
        if (_queue.Count >= _queue.BoundedCapacity) return; // the encoder is behind: drop this frame
        _lastTime = time;
        int slot;
        lock (_gpuLock)
        {
            EnsureProcessor(width, height);
            _context.CopySubresourceRegion(_input!, 0, 0, 0, 0, frame, 0, new Box(0, 0, 0, width, height, 1));
            slot = _next;
            _next = (_next + 1) % PoolSize;
            Blit(slot, width, height);
            _lastSlot = slot;
        }
        _queue.TryAdd((slot, time));
    }

    private void EnsureProcessor(int width, int height)
    {
        if (_processor != null && width <= _inputW && height <= _inputH && width == _contentW && height == _contentH) return;
        if (_processor == null || width > _inputW || height > _inputH)
        {
            _inputView?.Dispose();
            _input?.Dispose();
            _processor?.Dispose();
            _enumerator?.Dispose();
            foreach (var v in _outputViews) v?.Dispose();
            _inputW = Math.Max(width, _inputW);
            _inputH = Math.Max(height, _inputH);
            _input = _device.CreateTexture2D(new Texture2DDescription
            {
                Width = (uint)_inputW, Height = (uint)_inputH, MipLevels = 1, ArraySize = 1, Format = Format.B8G8R8A8_UNorm,
                SampleDescription = new SampleDescription(1, 0), Usage = ResourceUsage.Default,
                BindFlags = BindFlags.RenderTarget | BindFlags.ShaderResource
            });
            var content = new VideoProcessorContentDescription
            {
                InputFrameFormat = VideoFrameFormat.Progressive,
                InputWidth = (uint)_inputW, InputHeight = (uint)_inputH,
                OutputWidth = (uint)_width, OutputHeight = (uint)_height,
                InputFrameRate = new Rational((uint)_fps, 1), OutputFrameRate = new Rational((uint)_fps, 1),
                Usage = VideoUsage.PlaybackNormal
            };
            _enumerator = _videoDevice.CreateVideoProcessorEnumerator(content);
            _processor = _videoDevice.CreateVideoProcessor(_enumerator, 0);
            _inputView = _videoDevice.CreateVideoProcessorInputView(_input, _enumerator,
                new VideoProcessorInputViewDescription { FourCC = 0, ViewDimension = VideoProcessorInputViewDimension.Texture2D });
            for (int i = 0; i < PoolSize; i++)
            {
                _outputViews[i] = _videoDevice.CreateVideoProcessorOutputView(_nv12[i], _enumerator,
                    new VideoProcessorOutputViewDescription { ViewDimension = VideoProcessorOutputViewDimension.Texture2D });
            }
            _videoContext.VideoProcessorSetStreamFrameFormat(_processor, 0, VideoFrameFormat.Progressive);
            _videoContext.VideoProcessorSetOutputBackgroundColor(_processor, false, new VideoColor { Rgba = new VideoColorRgba { R = 0, G = 0, B = 0, A = 1 } });
        }
        _contentW = width;
        _contentH = height;
        // Source: the window's picture; destination: as big as fits in the clip, centred (black bars if shapes differ).
        _videoContext.VideoProcessorSetStreamSourceRect(_processor!, 0, true, new Vortice.RawRect(0, 0, width, height));
        float scale = Math.Min((float)_width / width, (float)_height / height);
        int dw = (int)(width * scale) & ~1, dh = (int)(height * scale) & ~1;
        int dx = (_width - dw) / 2 & ~1, dy = (_height - dh) / 2 & ~1;
        _videoContext.VideoProcessorSetStreamDestRect(_processor!, 0, true, new Vortice.RawRect(dx, dy, dx + dw, dy + dh));
        _videoContext.VideoProcessorSetOutputTargetRect(_processor!, true, new Vortice.RawRect(0, 0, _width, _height));
    }

    private int _contentW, _contentH;

    private void Blit(int slot, int width, int height)
    {
        var stream = new VideoProcessorStream { Enable = true, InputSurface = _inputView! };
        _videoContext.VideoProcessorBlt(_processor!, _outputViews[slot], 0, 1, new[] { stream });
    }

    // ---------------------------------------------------------------- encoding (encoder thread)

    private IMFSample InputSample(int slot, long time)
    {
        IMFSample sample = MediaFactory.MFCreateSample();
        if (_async)
        {
            IMFMediaBuffer buffer = MediaFactory.MFCreateDXGISurfaceBuffer(typeof(ID3D11Texture2D).GUID, _nv12[slot], 0, false);
            sample.AddBuffer(buffer);
            buffer.Dispose();
        }
        else
        {
            // Software: copy the NV12 picture to memory.
            int size = _width * _height * 3 / 2;
            IMFMediaBuffer buffer = MediaFactory.MFCreateMemoryBuffer(size);
            lock (_gpuLock)
            {
                _context.CopyResource(_staging!, _nv12[slot]);
                MappedSubresource map = _context.Map(_staging!, 0, MapMode.Read);
                try
                {
                    buffer.Lock(out IntPtr dst, out _, out _);
                    unsafe
                    {
                        byte* src = (byte*)map.DataPointer;
                        byte* d = (byte*)dst;
                        for (int y = 0; y < _height; y++)
                            Buffer.MemoryCopy(src + y * map.RowPitch, d + y * _width, _width, _width);
                        byte* uvSrc = src + map.RowPitch * _height;
                        byte* uvDst = d + _width * _height;
                        for (int y = 0; y < _height / 2; y++)
                            Buffer.MemoryCopy(uvSrc + y * map.RowPitch, uvDst + y * _width, _width, _width);
                    }
                    buffer.Unlock();
                    buffer.CurrentLength = size;
                }
                finally
                {
                    _context.Unmap(_staging!, 0);
                }
            }
            sample.AddBuffer(buffer);
            buffer.Dispose();
        }
        sample.SampleTime = time;
        sample.SampleDuration = _frameTime;
        // A keyframe every second of real time (not every N frames: Minecraft drops to 10 FPS when unfocused), so a
        // clip starts within a second of what was asked.
        if (_lastKeyRequest == long.MinValue || time - _lastKeyRequest >= 10_000_000L)
        {
            _lastKeyRequest = time;
            MF.SetCodecValue(_encoder.NativePointer, MF.CodecForceKeyFrame, 1);
        }
        return sample;
    }

    private void AsyncLoop()
    {
        try
        {
            using IMFMediaEventGenerator events = _encoder.QueryInterface<IMFMediaEventGenerator>();
            while (!_stopping)
            {
                using IMFMediaEvent e = events.GetEvent(0);
                switch (e.EventType)
                {
                    case MediaEventTypes.TransformNeedInput:
                        (int slot, long time) item;
                        try
                        {
                            item = _queue.Take();
                        }
                        catch (InvalidOperationException)
                        {
                            return;
                        }
                        using (IMFSample sample = InputSample(item.slot, item.time))
                        {
                            lock (_gpuLock) _encoder.ProcessInput(0, sample, 0);
                        }
                        _framesIn++;
                        break;
                    case MediaEventTypes.TransformHaveOutput:
                        DrainOutput();
                        break;
                }
            }
        }
        catch (Exception e)
        {
            if (!_stopping) Log.Error("video encoder", e);
        }
    }

    private void SyncLoop()
    {
        try
        {
            foreach (var item in _queue.GetConsumingEnumerable())
            {
                using (IMFSample sample = InputSample(item.slot, item.time))
                {
                    _encoder.ProcessInput(0, sample, 0);
                }
                _framesIn++;
                while (DrainOutput()) { }
            }
        }
        catch (Exception e)
        {
            if (!_stopping) Log.Error("video encoder", e);
        }
    }

    /// <summary>Takes one encoded frame from the encoder into the buffer. False when it has none.</summary>
    private bool DrainOutput()
    {
        var output = new OutputDataBuffer { StreamID = 0 };
        IMFSample? own = null;
        if (!_providesSamples)
        {
            int size = Math.Max(_width * _height * 2, _encoder.GetOutputStreamInfo(0).Size);
            own = MediaFactory.MFCreateSample();
            IMFMediaBuffer b = MediaFactory.MFCreateMemoryBuffer(size);
            own.AddBuffer(b);
            b.Dispose();
            output.Sample = own;
        }
        var hr = _encoder.ProcessOutput(ProcessOutputFlags.None, 1, ref output, out _);
        if (hr.Code == MF.ENeedMoreInput)
        {
            own?.Dispose();
            return false;
        }
        if (hr.Code == MF.EStreamChange)
        {
            // The encoder picked a new output type (it knows the stream better now): take it and carry on.
            own?.Dispose();
            using IMFMediaType t = _encoder.GetOutputAvailableType(0, 0);
            _encoder.SetOutputType(0, t, 0);
            return true;
        }
        hr.CheckError();
        IMFSample sample = output.Sample;
        try
        {
            byte[] data = MF.Bytes(sample);
            bool key = false;
            try { key = sample.GetUInt32(MF.CleanPoint) != 0; } catch { }
            if (OutputType == null) OutputType = _encoder.GetOutputCurrentType(0);
            if (key && SequenceHeader == null) SequenceHeader = H264.SequenceHeader(data);
            _buffer.AddVideo(new Encoded(data, sample.SampleTime, Math.Max(sample.SampleDuration, _frameTime), key));
        }
        finally
        {
            sample.Dispose();
            output.Events?.Dispose();
        }
        return true;
    }

    public long FramesEncoded => _framesIn;

    public void Dispose()
    {
        _stopping = true;
        _repeat.Dispose();
        _queue.CompleteAdding();
        try { _encoder.ProcessMessage(TMessageType.MessageNotifyEndOfStream, UIntPtr.Zero); } catch { }
        _thread.Join(1000);
        try { _encoder.Dispose(); } catch { }
        _manager?.Dispose();
        _staging?.Dispose();
        _inputView?.Dispose();
        _input?.Dispose();
        foreach (var v in _outputViews) v?.Dispose();
        _processor?.Dispose();
        _enumerator?.Dispose();
        foreach (var t in _nv12) t?.Dispose();
        _videoContext.Dispose();
        _videoDevice.Dispose();
    }
}

/// <summary>H.264 helpers.</summary>
internal static class H264
{
    /// <summary>The SPS and PPS NAL units of a keyframe (Annex B), which the MP4 writer needs as the sequence header.</summary>
    public static byte[]? SequenceHeader(byte[] data)
    {
        var parts = new List<byte[]>();
        foreach (var (start, end) in Nals(data))
        {
            int type = data[start] & 0x1F;
            if (type == 7 || type == 8)
            {
                var nal = new byte[4 + end - start];
                nal[3] = 1;
                Array.Copy(data, start, nal, 4, end - start);
                parts.Add(nal);
            }
        }
        return parts.Count == 0 ? null : parts.SelectMany(p => p).ToArray();
    }

    private static IEnumerable<(int start, int end)> Nals(byte[] d)
    {
        int i = 0, start = -1;
        while (i + 3 <= d.Length)
        {
            bool sc3 = d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1;
            bool sc4 = i + 4 <= d.Length && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1;
            if (sc3 || sc4)
            {
                int len = sc4 ? 4 : 3;
                if (start >= 0) yield return (start, i);
                start = i + len;
                i += len;
                continue;
            }
            i++;
        }
        if (start >= 0 && start < d.Length) yield return (start, d.Length);
    }
}
