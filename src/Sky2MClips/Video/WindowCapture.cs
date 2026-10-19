using System.Runtime.InteropServices;
using Vortice.Direct3D11;
using Vortice.DXGI;
using Windows.Graphics;
using Windows.Graphics.Capture;
using Windows.Graphics.DirectX;
using Windows.Graphics.DirectX.Direct3D11;
using WinRT;

namespace Sky2MClips.Video;

/// <summary>
/// Captures one window with Windows.Graphics.Capture, the API OBS and the Xbox Game Bar use: frames come as GPU
/// textures, so nothing is copied to the CPU. The yellow capture border is turned off where Windows allows it (11+).
/// </summary>
internal sealed class WindowCapture : IDisposable
{
    [ComImport, Guid("3628E81B-3CAC-4C60-B7F4-23CE0E0C3356"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IGraphicsCaptureItemInterop
    {
        IntPtr CreateForWindow(IntPtr window, in Guid iid);
        IntPtr CreateForMonitor(IntPtr monitor, in Guid iid);
    }

    [ComImport, Guid("A9B3D012-3DF2-4EE3-B8D1-8695F457D3C1"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDirect3DDxgiInterfaceAccess
    {
        IntPtr GetInterface(in Guid iid);
    }

    [DllImport("d3d11.dll", ExactSpelling = true)]
    private static extern int CreateDirect3D11DeviceFromDXGIDevice(IntPtr dxgiDevice, out IntPtr graphicsDevice);

    private static readonly Guid IidGraphicsCaptureItem = new("79C3F95B-31F7-4EC2-A464-632EF5D30760");
    private static readonly Guid IidTexture2D = new("6f15aaf2-d208-4e89-9ab4-489535d34f9c");

    /// <summary>A captured frame: its texture (only valid during the callback), size and time (100 ns, QPC).</summary>
    public delegate void FrameHandler(ID3D11Texture2D texture, int width, int height, long time);

    private readonly IDirect3DDevice _device;
    private readonly GraphicsCaptureItem _item;
    private Direct3D11CaptureFramePool _pool;
    private readonly GraphicsCaptureSession _session;
    private SizeInt32 _size;
    private readonly FrameHandler _onFrame;
    private bool _closed;

    public IntPtr Window { get; }

    public WindowCapture(ID3D11Device d3d, IntPtr hwnd, FrameHandler onFrame)
    {
        Window = hwnd;
        _onFrame = onFrame;
        using (IDXGIDevice dxgi = d3d.QueryInterface<IDXGIDevice>())
        {
            Marshal.ThrowExceptionForHR(CreateDirect3D11DeviceFromDXGIDevice(dxgi.NativePointer, out IntPtr inspectable));
            _device = MarshalInterface<IDirect3DDevice>.FromAbi(inspectable);
            Marshal.Release(inspectable);
        }

        var factory = ActivationFactory.Get("Windows.Graphics.Capture.GraphicsCaptureItem");
        var interop = (IGraphicsCaptureItemInterop)Marshal.GetObjectForIUnknown(factory.ThisPtr);
        IntPtr itemPtr = interop.CreateForWindow(hwnd, IidGraphicsCaptureItem);
        _item = GraphicsCaptureItem.FromAbi(itemPtr);
        Marshal.Release(itemPtr);

        _size = _item.Size;
        _pool = Direct3D11CaptureFramePool.CreateFreeThreaded(_device, DirectXPixelFormat.B8G8R8A8UIntNormalized, 2, _size);
        _pool.FrameArrived += OnFrameArrived;
        _session = _pool.CreateCaptureSession(_item);
        try { _session.IsCursorCaptureEnabled = false; } catch { }
        try { _session.IsBorderRequired = false; } catch { }
        _item.Closed += (_, _) => _closed = true;
        _session.StartCapture();
    }

    /// <summary>The window was closed (or the game exited).</summary>
    public bool Closed => _closed;

    private void OnFrameArrived(Direct3D11CaptureFramePool pool, object args)
    {
        using Direct3D11CaptureFrame? frame = pool.TryGetNextFrame();
        if (frame == null) return;
        SizeInt32 size = frame.ContentSize;
        if (size.Width != _size.Width || size.Height != _size.Height)
        {
            // The window was resized: make the pool the new size; the next frames come at it.
            _size = size;
            pool.Recreate(_device, DirectXPixelFormat.B8G8R8A8UIntNormalized, 2, size);
            return;
        }
        if (size.Width <= 0 || size.Height <= 0) return;

        var access = frame.Surface.As<IDirect3DDxgiInterfaceAccess>();
        IntPtr texturePtr = access.GetInterface(IidTexture2D);
        using var texture = new ID3D11Texture2D(texturePtr);
        try
        {
            _onFrame(texture, size.Width, size.Height, frame.SystemRelativeTime.Ticks);
        }
        catch (Exception e)
        {
            Log.Error("frame", e);
        }
    }

    public void Dispose()
    {
        try { _session.Dispose(); } catch { }
        try { _pool.Dispose(); } catch { }
    }
}
