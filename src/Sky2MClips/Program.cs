using System.Text.Json;
using System.Text.Json.Nodes;
using Sky2MClips.Native;

namespace Sky2MClips;

/// <summary>
/// Sky2M Clips: records the Minecraft window and its sound into a replay buffer and saves the last N seconds when the
/// Sky2M mod asks. The mod starts it and talks to it with one JSON object per line over stdin/stdout (see README):
/// <code>
///   mod -> clips: {"type":"config", "pid":1234, "seconds":30, "fps":60, "height":1080, "audio":"game_discord", "mic":false, ...}
///                 {"type":"save", "id":"7", "name":"Hotkey"}
///                 {"type":"quit"}
///   clips -> mod: {"type":"ready", "version":"1.0.0"}
///                 {"type":"status", "recording":true, "encoder":"NVIDIA H.264 Encoder MFT", "width":1920, "height":1080}
///                 {"type":"saved", "id":"7", "path":"C:\\...\\Sky2M 2026-10-19 15-30-12 Hotkey.mp4", "seconds":30.0}
///                 {"type":"error", "id":"7", "message":"..."}
/// </code>
/// It exits when stdin closes (the game exited or crashed) or the game's process ends.
/// Test commands: <c>--test "window title" seconds [folder] [audio] [sound pid]</c> records a window and saves one clip;
/// <c>--probe file.mp4 [frame.bmp]</c> prints what a clip contains.
/// </summary>
internal static class Program
{
    public const string Version = "1.0.0";
    private static readonly object Out = new();

    // MTA (no [STAThread]): Windows hands per-program audio clients back on another thread, and they only work there.
    [MTAThread]
    private static int Main(string[] args)
    {
        try
        {
            if (args.Length >= 2 && args[0] == "--probe") return Probe.Run(args[1], args.Length > 2 ? args[2] : null);
            if (args.Length >= 3 && args[0] == "--test")
                return Test(args[1], int.Parse(args[2]), args.Length > 3 ? args[3] : "", args.Length > 4 ? args[4] : "desktop",
                    args.Length > 5 ? int.Parse(args[5]) : 0);
            return Serve();
        }
        catch (Exception e)
        {
            Log.Error("fatal", e);
            Send(new { type = "error", message = e.Message });
            return 1;
        }
    }

    private static int Serve()
    {
        using var recorder = new Recorder();
        recorder.Status += (type, payload) => Send(Merge(type, payload));
        Send(new { type = "ready", version = Version });
        int parentPid = 0;
        using var parentWatch = new Timer(_ =>
        {
            if (parentPid > 0 && !Win32.ProcessAlive(parentPid))
            {
                Log.Info("the game exited");
                Environment.Exit(0);
            }
        }, null, 5000, 5000);

        string? line;
        while ((line = Console.In.ReadLine()) != null)
        {
            if (string.IsNullOrWhiteSpace(line)) continue;
            JsonNode? msg;
            try
            {
                msg = JsonNode.Parse(line);
            }
            catch (Exception e)
            {
                Log.Info("bad message: " + e.Message);
                continue;
            }
            string type = (string?)msg?["type"] ?? "";
            string? id = (string?)msg?["id"];
            try
            {
                switch (type)
                {
                    case "config":
                        var config = ReadConfig(msg!);
                        parentPid = config.Pid;
                        recorder.Configure(config);
                        break;
                    case "save":
                        string name = (string?)msg!["name"] ?? "";
                        // Saving takes a moment for a long clip: don't hold up the next message.
                        Task.Run(() =>
                        {
                            try
                            {
                                var (path, seconds) = recorder.Save(name);
                                Send(new { type = "saved", id, path, seconds = Math.Round(seconds, 1) });
                            }
                            catch (Exception e)
                            {
                                Log.Error("save", e);
                                Send(new { type = "error", id, message = e.Message });
                            }
                        });
                        break;
                    case "quit":
                        return 0;
                }
            }
            catch (Exception e)
            {
                Log.Error(type, e);
                Send(new { type = "error", id, message = e.Message });
            }
        }
        Log.Info("the mod closed the connection");
        return 0;
    }

    private static Config ReadConfig(JsonNode m)
    {
        int I(string k, int d) => m[k] is JsonValue v && v.TryGetValue(out int x) ? x : d;
        string S(string k, string d) => m[k] is JsonValue v && v.TryGetValue(out string? x) && x != null ? x : d;
        bool B(string k, bool d) => m[k] is JsonValue v && v.TryGetValue(out bool x) ? x : d;
        string audio = S("audio", "game");
        return new Config
        {
            Pid = I("pid", 0),
            Seconds = Math.Clamp(I("seconds", 30), 5, 300),
            Fps = Math.Clamp(I("fps", 60), 15, 120),
            Height = Math.Max(0, I("height", 1080)),
            BitrateKbps = Math.Clamp(I("bitrate", 0), 0, 100_000),
            Audio = audio is "desktop" or "game" or "game_discord" or "none" ? audio : "game",
            Mic = B("mic", false),
            GameVolume = Math.Clamp(I("gameVolume", 100), 0, 200),
            DiscordVolume = Math.Clamp(I("discordVolume", 100), 0, 200),
            DesktopVolume = Math.Clamp(I("desktopVolume", 100), 0, 200),
            MicVolume = Math.Clamp(I("micVolume", 100), 0, 200),
            Folder = S("folder", "")
        };
    }

    private static JsonObject Merge(string type, object payload)
    {
        var o = JsonSerializer.SerializeToNode(payload)!.AsObject();
        o.Insert(0, "type", type);
        return o;
    }

    public static void Send(object message)
    {
        string json = message is JsonNode node ? node.ToJsonString() : JsonSerializer.Serialize(message);
        lock (Out)
        {
            Console.Out.WriteLine(json);
            Console.Out.Flush();
        }
    }

    /// <summary>Records a window for a while and saves one clip, for trying the recorder without the mod.</summary>
    private static int Test(string title, int seconds, string folder, string audio, int audioPid)
    {
        using var recorder = new Recorder();
        recorder.Status += (type, payload) => Send(Merge(type, payload));
        recorder.Configure(new Config { WindowTitle = title, Seconds = seconds, Audio = audio, AudioPid = audioPid, Folder = folder, Height = 720 });
        Thread.Sleep((seconds + 4) * 1000);
        var (path, length) = recorder.Save("test");
        Send(new { type = "saved", path, seconds = length, encoder = recorder.EncoderName });
        return 0;
    }
}
