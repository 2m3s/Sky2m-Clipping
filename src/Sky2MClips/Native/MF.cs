using System.Runtime.InteropServices;
using Vortice.MediaFoundation;

namespace Sky2MClips.Native;

/// <summary>Media Foundation GUIDs and helpers (values from mfapi.h, mftransform.h and codecapi.h).</summary>
internal static class MF
{
    public static readonly Guid MajorType = new("48eba18e-f8c9-4687-bf11-0a74c9f96a8f");
    public static readonly Guid Subtype = new("f7e34c9a-42e8-4714-b74b-cb29d72c35e5");
    public static readonly Guid FrameSize = new("1652c33d-d6b2-4012-b834-72030849a37d");
    public static readonly Guid FrameRate = new("c459a2e8-3d2c-4e44-b132-fee5156c7bb0");
    public static readonly Guid PixelAspectRatio = new("c6376a1e-8d0a-4027-be45-6d9a0ad39bb6");
    public static readonly Guid AvgBitrate = new("20332624-fb0d-4d9e-bd0d-cbf6786c102e");
    public static readonly Guid InterlaceMode = new("e2724bb8-e676-4806-b4b2-a8d6efb44ccd");
    public static readonly Guid MpegSequenceHeader = new("3c036de7-3ad0-4c9e-9216-ee6d6ac21cb3");
    public static readonly Guid Mpeg2Profile = new("ad76a80b-2d5c-4e0b-b375-64e520137036");
    public static readonly Guid AudioNumChannels = new("37e48bf5-645e-4c5b-89de-ada9e29b696a");
    public static readonly Guid AudioSamplesPerSecond = new("5faeeae7-0290-4c31-9e8a-c534f68d9dba");
    public static readonly Guid AudioBitsPerSample = new("f2deb57f-40fa-4764-aa33-ed4f2d1ff669");
    public static readonly Guid AudioAvgBytesPerSecond = new("1aab75c8-cfef-451c-ab95-ac034b8e1731");
    public static readonly Guid AudioBlockAlignment = new("322de230-9eeb-43bd-ab7a-ff412251541d");
    public static readonly Guid AacPayloadType = new("bfbabe79-7434-4d1c-94f0-72a3b9e17188");
    public static readonly Guid AllSamplesIndependent = new("c9173739-5e56-461c-b713-46fb995cb95f");

    public static readonly Guid MediaTypeVideo = new("73646976-0000-0010-8000-00AA00389B71");
    public static readonly Guid MediaTypeAudio = new("73647561-0000-0010-8000-00AA00389B71");
    public static readonly Guid FormatH264 = new("34363248-0000-0010-8000-00AA00389B71");
    public static readonly Guid FormatNV12 = new("3231564E-0000-0010-8000-00AA00389B71");
    public static readonly Guid FormatAac = new("00001610-0000-0010-8000-00AA00389B71");
    public static readonly Guid FormatPcm = new("00000001-0000-0010-8000-00AA00389B71");
    public static readonly Guid FormatRgb32 = new("00000016-0000-0010-8000-00AA00389B71");

    public static readonly Guid CategoryVideoEncoder = new("f79eac7d-e545-4387-bdee-d647d7bde42a");
    public static readonly Guid CategoryAudioEncoder = new("91c64bd0-f91e-4d8c-9276-db248279d975");

    public static readonly Guid TransformAsyncUnlock = new("e5666d6b-3422-4eb6-a421-da7db1f8e207");
    public static readonly Guid TransformAsync = new("f81a699a-649a-497d-8c73-29f8fed6ad7a");
    public static readonly Guid D3D11Aware = new("206b4fc8-fcf9-4c51-afe3-9764369e33a0");
    public static readonly Guid FriendlyName = new("314ffbae-5b41-4c95-9c19-4e7d586face3");
    public static readonly Guid CleanPoint = new("9cdf01d8-a0f0-43ba-b077-eaa06cbd728a");
    public static readonly Guid EnableHardwareTransforms = new("a634a91c-822b-41b9-a494-4de4643612b0");
    public static readonly Guid DisableThrottling = new("08b845d8-2b74-4afe-9d53-be16d2d5ae4f");
    public static readonly Guid LowLatency = new("9c27891a-ed7a-40e1-88e8-b22727a024ee");
    public static readonly Guid SourceReaderEnableVideoProcessing = new("fb394f3d-ccf1-42ee-bbb3-f9b845d5681d");

    public static readonly Guid CodecRateControlMode = new("1c0608e9-370c-4710-8a58-cb6181c42423");
    public static readonly Guid CodecMeanBitRate = new("f7222374-2144-4815-b550-a37f8e12ee52");
    public static readonly Guid CodecGopSize = new("95f31b26-95a4-41aa-9303-246a7fc6eef1");
    public static readonly Guid CodecBPictureCount = new("8d390aac-dc5c-4200-b57f-814d04babab2");
    public static readonly Guid CodecForceKeyFrame = new("398c1b98-8353-475a-9ef2-8f265d260345");

    public const uint EnumSync = 0x1, EnumAsync = 0x2, EnumHardware = 0x4, EnumSortAndFilter = 0x40;
    public const int ENeedMoreInput = unchecked((int)0xC00D6D72);
    public const int EStreamChange = unchecked((int)0xC00D6D61);
    public const int OutputStreamProvidesSamples = 0x100;

    public static ulong Pack(uint high, uint low) => ((ulong)high << 32) | low;

    public static IMFMediaType VideoType(Guid subtype, int width, int height, int fps)
    {
        IMFMediaType t = MediaFactory.MFCreateMediaType();
        t.Set(MajorType, MediaTypeVideo);
        t.Set(Subtype, subtype);
        t.Set(FrameSize, Pack((uint)width, (uint)height));
        t.Set(FrameRate, Pack((uint)fps, 1));
        t.Set(PixelAspectRatio, Pack(1, 1));
        t.Set(InterlaceMode, 2u);
        return t;
    }

    /// <summary>An IMFSample holding a copy of <paramref name="data"/>.</summary>
    public static IMFSample Sample(ReadOnlySpan<byte> data, long time, long duration, bool keyframe)
    {
        IMFMediaBuffer buffer = MediaFactory.MFCreateMemoryBuffer(Math.Max(1, data.Length));
        buffer.Lock(out IntPtr ptr, out _, out _);
        unsafe
        {
            data.CopyTo(new Span<byte>((void*)ptr, data.Length));
        }
        buffer.Unlock();
        buffer.CurrentLength = data.Length;
        IMFSample sample = MediaFactory.MFCreateSample();
        sample.AddBuffer(buffer);
        buffer.Dispose();
        sample.SampleTime = time;
        sample.SampleDuration = duration;
        if (keyframe) sample.Set(CleanPoint, 1u);
        return sample;
    }

    /// <summary>The bytes in a sample.</summary>
    public static byte[] Bytes(IMFSample sample)
    {
        using IMFMediaBuffer buffer = sample.ConvertToContiguousBuffer();
        buffer.Lock(out IntPtr ptr, out _, out int length);
        try
        {
            var bytes = new byte[length];
            Marshal.Copy(ptr, bytes, 0, length);
            return bytes;
        }
        finally
        {
            buffer.Unlock();
        }
    }

    // ---------------------------------------------------------------- ICodecAPI (vtable calls)

    [StructLayout(LayoutKind.Explicit, Size = 24)]
    private struct Variant
    {
        [FieldOffset(0)] public ushort Vt;
        [FieldOffset(8)] public uint UI4;
        [FieldOffset(8)] public ulong UI8;
    }

    private static readonly Guid IidCodecApi = new("901db4c7-31ce-41a2-85dc-8fa0bf41b8da");

    /// <summary>Sets a codec property (ICodecAPI::SetValue) to a UINT32. Returns whether the encoder took it.</summary>
    public static unsafe bool SetCodecValue(IntPtr transform, Guid key, uint value)
    {
        Guid iid = IidCodecApi;
        if (Marshal.QueryInterface(transform, ref iid, out IntPtr api) != 0 || api == IntPtr.Zero) return false;
        try
        {
            var v = new Variant { Vt = 19, UI4 = value };
            // IUnknown (3) + IsSupported, IsModifiable, GetParameterRange, GetParameterValues, GetDefaultValue,
            // GetValue, then SetValue at slot 9.
            var setValue = (delegate* unmanaged[Stdcall]<IntPtr, Guid*, Variant*, int>)(*(IntPtr**)api)[9];
            return setValue(api, &key, &v) >= 0;
        }
        finally
        {
            Marshal.Release(api);
        }
    }
}
