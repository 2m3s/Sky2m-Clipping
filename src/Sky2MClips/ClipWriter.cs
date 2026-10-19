using Sky2MClips.Native;
using Vortice.MediaFoundation;

namespace Sky2MClips;

/// <summary>
/// Writes a clip: the buffered H.264 and AAC samples go into an MP4 as they are (no re-encoding), so saving takes a
/// moment even for a long clip. Times are moved so the clip starts at 0.
/// </summary>
internal static class ClipWriter
{
    public static double Write(string path, List<Encoded> video, List<Encoded> audio, IMFMediaType videoType, byte[]? sequenceHeader,
                               IMFMediaType? audioType)
    {
        if (video.Count == 0) throw new InvalidOperationException("Nothing recorded yet.");
        long start = video[0].Time;
        // Windows picks the container from the extension, so the temporary name ends in .mp4 too.
        string temp = Path.ChangeExtension(path, ".saving.mp4");
        using (IMFAttributes attributes = MediaFactory.MFCreateAttributes(2))
        {
            attributes.Set(MF.DisableThrottling, 1u);
            using IMFSinkWriter writer = MediaFactory.MFCreateSinkWriterFromURL(temp, null!, attributes);

            using IMFMediaType vType = MediaFactory.MFCreateMediaType();
            videoType.CopyAllItems(vType);
            if (sequenceHeader != null) vType.SetBlob(MF.MpegSequenceHeader, sequenceHeader);
            int vStream = writer.AddStream(vType);
            writer.SetInputMediaType(vStream, vType, null!);

            int aStream = -1;
            if (audioType != null && audio.Count > 0)
            {
                using IMFMediaType aType = MediaFactory.MFCreateMediaType();
                audioType.CopyAllItems(aType);
                aStream = writer.AddStream(aType);
                writer.SetInputMediaType(aStream, aType, null!);
            }

            writer.BeginWriting();
            // Interleaved by time, as an MP4 player reads them.
            int vi = 0, ai = 0;
            while (vi < video.Count || (aStream >= 0 && ai < audio.Count))
            {
                bool takeVideo = aStream < 0 || ai >= audio.Count || (vi < video.Count && video[vi].Time <= audio[ai].Time);
                Encoded e = takeVideo ? video[vi++] : audio[ai++];
                long t = e.Time - start;
                if (t < 0) continue;
                using IMFSample sample = MF.Sample(e.Data, t, e.Duration, e.Key);
                writer.WriteSample(takeVideo ? vStream : aStream, sample);
            }
            writer.Finalize();
        }
        if (File.Exists(path)) File.Delete(path);
        File.Move(temp, path);
        Encoded last = video[^1];
        return (last.Time + last.Duration - start) / 10_000_000.0;
    }
}
