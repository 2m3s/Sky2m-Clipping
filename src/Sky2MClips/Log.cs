namespace Sky2MClips;

/// <summary>
/// The helper's log: %LOCALAPPDATA%\Sky2MClips\log.txt (the last run only), and stderr, which the mod copies into its
/// own log. Stdout is kept for the protocol.
/// </summary>
internal static class Log
{
    private static readonly object Lock = new();
    private static readonly StreamWriter? File;

    static Log()
    {
        try
        {
            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Sky2MClips");
            Directory.CreateDirectory(dir);
            File = new StreamWriter(Path.Combine(dir, "log.txt"), false) { AutoFlush = true };
        }
        catch
        {
            File = null;
        }
    }

    public static void Info(string message) => Write("INFO", message);

    public static void Error(string what, Exception e) => Write("ERROR", $"{what}: {e}");

    private static void Write(string level, string message)
    {
        string line = $"{DateTime.Now:HH:mm:ss.fff} [{level}] {message}";
        lock (Lock)
        {
            try { File?.WriteLine(line); } catch { }
            try { Console.Error.WriteLine(line); } catch { }
        }
    }
}
