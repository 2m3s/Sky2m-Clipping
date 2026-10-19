namespace Sky2MClips;

/// <summary>One encoded frame (H.264) or audio frame (AAC); times are 100 ns on <see cref="Native.Clock"/>.</summary>
internal sealed record Encoded(byte[] Data, long Time, long Duration, bool Key);

/// <summary>
/// The last few seconds of encoded video and audio, kept in memory. Old samples are dropped as new ones come in, always
/// keeping the video from a keyframe so a clip can start cleanly.
/// </summary>
internal sealed class ReplayBuffer
{
    private readonly object _lock = new();
    private readonly LinkedList<Encoded> _video = new();
    private readonly LinkedList<Encoded> _audio = new();
    private long _keepFor;

    public ReplayBuffer(int seconds) => SetLength(seconds);

    public void SetLength(int seconds)
    {
        // A little extra so a clip asked for N seconds really has N after starting on a keyframe.
        lock (_lock) _keepFor = (seconds + 3) * 10_000_000L;
    }

    public void AddVideo(Encoded e)
    {
        lock (_lock)
        {
            _video.AddLast(e);
            long cutoff = e.Time - _keepFor;
            // Drop whole keyframe groups that end before the cutoff.
            while (_video.First != null && _video.First.Value.Time < cutoff)
            {
                var next = NextKey(_video.First);
                if (next == null || next.Value.Time > cutoff) break;
                while (_video.First != next) _video.RemoveFirst();
            }
            TrimAudio(cutoff);
        }
    }

    public void AddAudio(Encoded e)
    {
        lock (_lock)
        {
            _audio.AddLast(e);
            TrimAudio(e.Time - _keepFor);
        }
    }

    private void TrimAudio(long cutoff)
    {
        long videoStart = _video.First?.Value.Time ?? cutoff;
        long keepFrom = Math.Min(cutoff, videoStart) - 10_000_000L;
        while (_audio.First != null && _audio.First.Value.Time < keepFrom) _audio.RemoveFirst();
    }

    private static LinkedListNode<Encoded>? NextKey(LinkedListNode<Encoded> node)
    {
        for (var n = node.Next; n != null; n = n.Next)
            if (n.Value.Key) return n;
        return null;
    }

    public void Clear()
    {
        lock (_lock)
        {
            _video.Clear();
            _audio.Clear();
        }
    }

    /// <summary>
    /// The last <paramref name="seconds"/> of video, starting at the keyframe at or just before that point, and the audio
    /// over the same time.
    /// </summary>
    public (List<Encoded> video, List<Encoded> audio) Snapshot(int seconds)
    {
        lock (_lock)
        {
            if (_video.Count == 0) return (new(), new());
            long end = _video.Last!.Value.Time;
            long wanted = end - seconds * 10_000_000L;
            LinkedListNode<Encoded>? start = null;
            for (var n = _video.First; n != null; n = n.Next)
            {
                if (!n.Value.Key) continue;
                if (n.Value.Time <= wanted || start == null) start = n;
                if (n.Value.Time > wanted) break;
            }
            var video = new List<Encoded>();
            for (var n = start; n != null; n = n.Next) video.Add(n.Value);
            long from = video.Count > 0 ? video[0].Time : wanted;
            var audio = _audio.Where(a => a.Time >= from).ToList();
            return (video, audio);
        }
    }
}
