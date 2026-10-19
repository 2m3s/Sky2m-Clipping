using System.Runtime.InteropServices;

namespace Sky2MClips.Audio;

/// <summary>
/// The Windows audio (WASAPI) interfaces the recorder needs, declared directly: the default speakers' loopback (everything
/// you hear), the default microphone, and process loopback (one program and its child processes, Windows 10 2004+).
/// </summary>
internal static class Wasapi
{
    public const int SampleRate = 48000;
    public const int Channels = 2;

    private const uint ClsctxAll = 0x17;
    private const int StreamFlagsLoopback = 0x00020000;
    private const int StreamFlagsEventCallback = 0x00040000;
    private const int StreamFlagsAutoConvertPcm = unchecked((int)0x80000000);
    private const int StreamFlagsSrcDefaultQuality = 0x08000000;

    private static readonly Guid ClsidDeviceEnumerator = new("BCDE0395-E52F-467C-8E3D-C4579291692E");
    private static readonly Guid IidAudioClient = new("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
    private static readonly Guid IidAudioCaptureClient = new("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
    private static readonly Guid FloatSubtype = new("00000003-0000-0010-8000-00aa00389b71");

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig] int Activate(in Guid iid, uint clsCtx, IntPtr activationParams, out IntPtr result);
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, in WaveFormatExtensible format, IntPtr sessionGuid);
        [PreserveSig] int GetBufferSize(out uint frames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint frames);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
        [PreserveSig] int GetMixFormat(out IntPtr format);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr handle);
        [PreserveSig] int GetService(in Guid iid, out IntPtr service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAudioCaptureClient
    {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IActivateAudioInterfaceAsyncOperation
    {
        void GetActivateResult(out int activateResult, [MarshalAs(UnmanagedType.IUnknown)] out object activatedInterface);
    }

    [ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IActivateAudioInterfaceCompletionHandler
    {
        void ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation);
    }

    /// <summary>Marks the completion handler as callable from any thread, which ActivateAudioInterfaceAsync requires.</summary>
    [ComImport, Guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAgileObject
    {
    }

    [ComVisible(true)]
    private sealed class ActivationHandler : IActivateAudioInterfaceCompletionHandler, IAgileObject
    {
        public readonly TaskCompletionSource<IAudioClient> Done = new();

        public void ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation)
        {
            try
            {
                operation.GetActivateResult(out int hr, out object client);
                if (hr < 0) Done.SetException(Marshal.GetExceptionForHR(hr)!);
                else Done.SetResult((IAudioClient)client);
            }
            catch (Exception e)
            {
                Done.TrySetException(e);
            }
        }
    }

    [DllImport("Mmdevapi.dll", ExactSpelling = true, PreserveSig = true)]
    private static extern int ActivateAudioInterfaceAsync([MarshalAs(UnmanagedType.LPWStr)] string deviceInterfacePath, in Guid riid,
                                                          IntPtr activationParams, IActivateAudioInterfaceCompletionHandler handler,
                                                          out IActivateAudioInterfaceAsyncOperation operation);

    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    public struct WaveFormatExtensible
    {
        public ushort FormatTag;
        public ushort Channels;
        public uint SamplesPerSec;
        public uint AvgBytesPerSec;
        public ushort BlockAlign;
        public ushort BitsPerSample;
        public ushort ExtraSize;
        public ushort ValidBitsPerSample;
        public uint ChannelMask;
        public Guid SubFormat;
    }

    /// <summary>48 kHz stereo float, the one format every source is captured in (Windows converts to it).</summary>
    public static WaveFormatExtensible FloatStereo => new()
    {
        FormatTag = 0xFFFE, Channels = Channels, SamplesPerSec = SampleRate, AvgBytesPerSec = SampleRate * Channels * 4,
        BlockAlign = Channels * 4, BitsPerSample = 32, ExtraSize = 22, ValidBitsPerSample = 32, ChannelMask = 3, SubFormat = FloatSubtype
    };

    [StructLayout(LayoutKind.Sequential)]
    private struct ActivationParams
    {
        public int ActivationType; // 1 = process loopback
        public uint TargetProcessId;
        public int ProcessLoopbackMode; // 0 = include the process tree
    }

    [StructLayout(LayoutKind.Explicit, Size = 24)]
    private struct PropVariantBlob
    {
        [FieldOffset(0)] public ushort Vt; // VT_BLOB = 65
        [FieldOffset(8)] public uint Size;
        [FieldOffset(16)] public IntPtr Data;
    }

    /// <summary>A started capture client: the default output's loopback ("everything you hear").</summary>
    public static (IAudioClient, IAudioCaptureClient) DesktopLoopback(IntPtr wakeEvent) => FromDevice(0, StreamFlagsLoopback, wakeEvent);

    /// <summary>The default microphone.</summary>
    public static (IAudioClient, IAudioCaptureClient) Microphone(IntPtr wakeEvent) => FromDevice(1, 0, wakeEvent);

    private static (IAudioClient, IAudioCaptureClient) FromDevice(int flow, int flags, IntPtr wakeEvent)
    {
        var enumerator = (IMMDeviceEnumerator)Activator.CreateInstance(Type.GetTypeFromCLSID(ClsidDeviceEnumerator)!)!;
        Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(flow, 1, out IMMDevice device)); // eMultimedia
        Marshal.ThrowExceptionForHR(device.Activate(IidAudioClient, ClsctxAll, IntPtr.Zero, out IntPtr clientPtr));
        var client = (IAudioClient)Marshal.GetObjectForIUnknown(clientPtr);
        Marshal.Release(clientPtr);
        return Start(client, flags, wakeEvent);
    }

    /// <summary>One program's sound, with its child processes (Discord plays sound from a child process).</summary>
    public static (IAudioClient, IAudioCaptureClient) ProcessLoopback(int pid, IntPtr wakeEvent)
    {
        var p = new ActivationParams { ActivationType = 1, TargetProcessId = (uint)pid, ProcessLoopbackMode = 0 };
        IntPtr paramsPtr = Marshal.AllocHGlobal(Marshal.SizeOf<ActivationParams>());
        IntPtr variantPtr = Marshal.AllocHGlobal(Marshal.SizeOf<PropVariantBlob>());
        try
        {
            Marshal.StructureToPtr(p, paramsPtr, false);
            Marshal.StructureToPtr(new PropVariantBlob { Vt = 65, Size = (uint)Marshal.SizeOf<ActivationParams>(), Data = paramsPtr }, variantPtr, false);
            var handler = new ActivationHandler();
            Marshal.ThrowExceptionForHR(ActivateAudioInterfaceAsync("VAD\\Process_Loopback", IidAudioClient, variantPtr, handler, out _));
            if (!handler.Done.Task.Wait(5000)) throw new TimeoutException("Windows didn't answer the process sound request.");
            return Start(handler.Done.Task.Result, StreamFlagsLoopback, wakeEvent);
        }
        finally
        {
            Marshal.FreeHGlobal(variantPtr);
            Marshal.FreeHGlobal(paramsPtr);
        }
    }

    private static (IAudioClient, IAudioCaptureClient) Start(IAudioClient client, int flags, IntPtr wakeEvent)
    {
        var format = FloatStereo;
        int all = flags | StreamFlagsEventCallback | StreamFlagsAutoConvertPcm | StreamFlagsSrcDefaultQuality;
        Marshal.ThrowExceptionForHR(client.Initialize(0, all, 2_000_000, 0, format, IntPtr.Zero)); // 200 ms buffer
        Marshal.ThrowExceptionForHR(client.SetEventHandle(wakeEvent));
        Marshal.ThrowExceptionForHR(client.GetService(IidAudioCaptureClient, out IntPtr capturePtr));
        var capture = (IAudioCaptureClient)Marshal.GetObjectForIUnknown(capturePtr);
        Marshal.Release(capturePtr);
        Marshal.ThrowExceptionForHR(client.Start());
        return (client, capture);
    }
}
