using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace Sky2MClips.Native;

/// <summary>Plain Win32 calls: windows of a process, the process tree, and the time base everything shares.</summary>
internal static partial class Win32
{
    private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);

    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr hwnd);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int max);

    [DllImport("user32.dll")]
    private static extern bool GetClientRect(IntPtr hwnd, out Rect rect);

    [DllImport("user32.dll")]
    public static extern bool IsIconic(IntPtr hwnd);

    [DllImport("user32.dll")]
    public static extern bool IsWindow(IntPtr hwnd);

    [StructLayout(LayoutKind.Sequential)]
    private struct Rect
    {
        public int Left, Top, Right, Bottom;
    }

    /// <summary>The biggest visible top-level window of a process (Minecraft's game window), or zero.</summary>
    public static IntPtr MainWindowOf(int pid)
    {
        IntPtr best = IntPtr.Zero;
        long bestArea = 0;
        EnumWindows((hwnd, _) =>
        {
            GetWindowThreadProcessId(hwnd, out uint owner);
            if (owner != (uint)pid || !IsWindowVisible(hwnd)) return true;
            var title = new StringBuilder(256);
            GetWindowText(hwnd, title, title.Capacity);
            if (title.Length == 0) return true;
            GetClientRect(hwnd, out Rect r);
            long area = (long)(r.Right - r.Left) * (r.Bottom - r.Top);
            if (area > bestArea)
            {
                bestArea = area;
                best = hwnd;
            }
            return true;
        }, IntPtr.Zero);
        return best;
    }

    /// <summary>The first visible window whose title contains <paramref name="text"/> (test mode).</summary>
    public static (IntPtr hwnd, int pid) WindowByTitle(string text)
    {
        IntPtr found = IntPtr.Zero;
        int pid = 0;
        EnumWindows((hwnd, _) =>
        {
            if (!IsWindowVisible(hwnd)) return true;
            var title = new StringBuilder(256);
            GetWindowText(hwnd, title, title.Capacity);
            if (title.ToString().Contains(text, StringComparison.OrdinalIgnoreCase))
            {
                GetWindowThreadProcessId(hwnd, out uint owner);
                found = hwnd;
                pid = (int)owner;
                return false;
            }
            return true;
        }, IntPtr.Zero);
        return (found, pid);
    }

    // ---------------------------------------------------------------- processes

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ProcessEntry32
    {
        public uint dwSize;
        public uint cntUsage;
        public uint th32ProcessID;
        public IntPtr th32DefaultHeapID;
        public uint th32ModuleID;
        public uint cntThreads;
        public uint th32ParentProcessID;
        public int pcPriClassBase;
        public uint dwFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string szExeFile;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr CreateToolhelp32Snapshot(uint flags, uint pid);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern bool Process32FirstW(IntPtr snapshot, ref ProcessEntry32 entry);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern bool Process32NextW(IntPtr snapshot, ref ProcessEntry32 entry);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    /// <summary>Every running process with its parent and exe name.</summary>
    public static List<(int pid, int parent, string exe)> Processes()
    {
        var list = new List<(int, int, string)>();
        IntPtr snap = CreateToolhelp32Snapshot(0x2, 0);
        if (snap == IntPtr.Zero || snap == new IntPtr(-1)) return list;
        try
        {
            var e = new ProcessEntry32 { dwSize = (uint)Marshal.SizeOf<ProcessEntry32>() };
            if (!Process32FirstW(snap, ref e)) return list;
            do
            {
                list.Add(((int)e.th32ProcessID, (int)e.th32ParentProcessID, e.szExeFile));
            } while (Process32NextW(snap, ref e));
        }
        finally
        {
            CloseHandle(snap);
        }
        return list;
    }

    /// <summary>
    /// The top Discord process (Discord, PTB, Canary or Development whose parent isn't Discord too), or 0. Its audio is
    /// played by a child process, so it is captured with its whole process tree.
    /// </summary>
    public static int DiscordRoot()
    {
        var all = Processes();
        var discord = all.Where(p => p.exe.StartsWith("Discord", StringComparison.OrdinalIgnoreCase)
                                     && p.exe.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)).ToList();
        var ids = discord.Select(p => p.pid).ToHashSet();
        var root = discord.FirstOrDefault(p => !ids.Contains(p.parent));
        return root.pid;
    }

    public static bool ProcessAlive(int pid)
    {
        try
        {
            using var p = Process.GetProcessById(pid);
            return !p.HasExited;
        }
        catch
        {
            return false;
        }
    }
}

/// <summary>
/// One clock for video and audio: the performance counter in 100 ns units, the same base Windows.Graphics.Capture
/// stamps frames with (SystemRelativeTime).
/// </summary>
internal static class Clock
{
    private static readonly double Ticks100ns = 10_000_000.0 / Stopwatch.Frequency;

    public static long Now => (long)(Stopwatch.GetTimestamp() * Ticks100ns);
}
