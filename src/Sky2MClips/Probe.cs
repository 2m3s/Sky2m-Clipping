using System.Runtime.InteropServices;
using Sky2MClips.Native;
using Vortice.MediaFoundation;

namespace Sky2MClips;

/// <summary>
/// <c>--probe clip.mp4 [frame.bmp]</c>: reads a clip back with Media Foundation and prints its streams, how many samples
/// each has and how long it is; with a .bmp path also saves the middle video frame, to check the picture.
/// </summary>
internal static class Probe
{
    public static int Run(string file, string? bmp)
    {
        MediaFactory.MFStartup(false).CheckError();
        using IMFAttributes attrs = MediaFactory.MFCreateAttributes(1);
        attrs.Set(MF.SourceReaderEnableVideoProcessing, 1u);
        using IMFSourceReader reader = MediaFactory.MFCreateSourceReaderFromURL(file, attrs);
        int videoStream = -1, audioStream = -1;
        for (int stream = 0; ; stream++)
        {
            IMFMediaType type;
            try
            {
                type = reader.GetCurrentMediaType(stream);
            }
            catch
            {
                break;
            }
            Guid major = type.GetGUID(MF.MajorType);
            Guid sub = type.GetGUID(MF.Subtype);
            string what = major == MF.MediaTypeVideo ? "video" : major == MF.MediaTypeAudio ? "audio" : major.ToString();
            string detail = "";
            if (major == MF.MediaTypeVideo)
            {
                if (videoStream < 0) videoStream = stream;
                ulong size = type.GetUInt64(MF.FrameSize);
                detail = $"{size >> 32}x{size & 0xFFFFFFFF}";
            }
            else if (major == MF.MediaTypeAudio)
            {
                if (audioStream < 0) audioStream = stream;
                detail = $"{type.GetUInt32(MF.AudioSamplesPerSecond)} Hz, {type.GetUInt32(MF.AudioNumChannels)} ch";
            }
            type.Dispose();
            Console.WriteLine($"stream {stream}: {what} {Name(sub)} {detail}");
        }

        // Count samples per stream, and keep the middle video frame as RGB32 if asked.
        if (videoStream < 0) bmp = null;
        if (bmp != null)
        {
            using IMFMediaType rgb = MediaFactory.MFCreateMediaType();
            rgb.Set(MF.MajorType, MF.MediaTypeVideo);
            rgb.Set(MF.Subtype, MF.FormatRgb32);
            reader.SetCurrentMediaType(videoStream, rgb);
        }
        if (audioStream >= 0)
        {
            // Decode the sound to 16-bit PCM to measure how loud it is (silence means nothing was recorded).
            using IMFMediaType pcm = MediaFactory.MFCreateMediaType();
            pcm.Set(MF.MajorType, MF.MediaTypeAudio);
            pcm.Set(MF.Subtype, MF.FormatPcm);
            pcm.Set(MF.AudioBitsPerSample, 16u);
            reader.SetCurrentMediaType(audioStream, pcm);
        }
        double sumSquares = 0, peak = 0;
        long audioValues = 0;
        var counts = new Dictionary<int, int>();
        var last = new Dictionary<int, long>();
        var frames = new List<byte[]>();
        int width = 0, height = 0;
        if (bmp != null)
        {
            using IMFMediaType cur = reader.GetCurrentMediaType(videoStream);
            ulong size = cur.GetUInt64(MF.FrameSize);
            width = (int)(size >> 32);
            height = (int)(size & 0xFFFFFFFF);
        }
        while (true)
        {
            IMFSample? sample = reader.ReadSample(unchecked((int)0xFFFFFFFE), 0, out int stream, out var flags, out long time);
            if (((int)flags & 0x2) != 0) break; // end of stream
            if (sample == null) continue;
            counts[stream] = counts.GetValueOrDefault(stream) + 1;
            last[stream] = time + sample.SampleDuration;
            if (bmp != null && stream == videoStream) frames.Add(MF.Bytes(sample));
            if (stream == audioStream)
            {
                byte[] a = MF.Bytes(sample);
                for (int i = 0; i + 1 < a.Length; i += 2)
                {
                    double v = (short)(a[i] | a[i + 1] << 8) / 32768.0;
                    sumSquares += v * v;
                    peak = Math.Max(peak, Math.Abs(v));
                    audioValues++;
                }
            }
            sample.Dispose();
        }
        foreach (var (stream, n) in counts.OrderBy(k => k.Key))
            Console.WriteLine($"stream {stream}: {n} samples, {last[stream] / 10_000_000.0:0.00} s");
        if (audioValues > 0)
            Console.WriteLine($"sound: peak {20 * Math.Log10(Math.Max(peak, 1e-9)):0.0} dB, average {10 * Math.Log10(Math.Max(sumSquares / audioValues, 1e-18)):0.0} dB");
        if (bmp != null && frames.Count > 0) WriteBmp(bmp, frames[frames.Count / 2], width, height);
        return 0;
    }

    private static string Name(Guid sub) =>
        sub == MF.FormatH264 ? "H.264" : sub == MF.FormatAac ? "AAC" : sub == MF.FormatRgb32 ? "RGB32" : sub.ToString();

    private static void WriteBmp(string path, byte[] bgra, int width, int height)
    {
        using var f = File.Create(path);
        using var w = new BinaryWriter(f);
        int imageSize = width * height * 4;
        w.Write((byte)'B');
        w.Write((byte)'M');
        w.Write(54 + imageSize);
        w.Write(0);
        w.Write(54);
        w.Write(40);
        w.Write(width);
        w.Write(-height); // top-down
        w.Write((short)1);
        w.Write((short)32);
        w.Write(0);
        w.Write(imageSize);
        w.Write(2835);
        w.Write(2835);
        w.Write(0);
        w.Write(0);
        // Decoded rows can be padded (H.264 codes widths in steps of 16): take each row from its real start.
        int stride = height > 0 ? bgra.Length / height : width * 4;
        Console.WriteLine($"frame {width}x{height}, row {stride} bytes");
        for (int y = 0; y < height; y++) w.Write(bgra, y * stride, Math.Min(width * 4, bgra.Length - y * stride));
    }
}
