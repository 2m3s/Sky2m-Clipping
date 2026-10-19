package com.epic60869.sky2m.clipping;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reflective API consumed by Sky2M Clips.java. Capture runs inside Minecraft's JVM; no EXE is launched. */
public final class ClippingEntrypoint {
    private static final Logger LOG = LoggerFactory.getLogger("sky2m-clipping");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss", Locale.ROOT);
    private final ExecutorService saves = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Sky2M clipping save"); t.setDaemon(true); return t;
    });
    private final Deque<Path> segments = new ArrayDeque<>();
    private volatile Settings settings = new Settings();
    private volatile boolean running, recording;
    private volatile String encoder = "", lastError = "";
    private volatile Thread worker;
    private volatile FFmpegFrameGrabber activeGrabber;
    private Path tempDir;

    public ClippingEntrypoint() {
        if (!windows()) lastError = "Sky2M Clipping currently supports Windows only.";
    }

    public synchronized void configure(Map<String, Object> values) {
        Settings next = Settings.from(values, settings);
        boolean restart = !settings.sameCapture(next);
        settings = next;
        if (!windows()) { lastError = "Sky2M Clipping currently supports Windows only."; return; }
        if (!running || restart) { stopCapture(); startCapture(); }
    }

    public CompletableFuture<Map<String, Object>> save(String name) {
        return CompletableFuture.supplyAsync(() -> saveClip(name), saves);
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recording", recording);
        out.put("encoder", encoder);
        out.put("error", lastError);
        out.put("audio", "not-yet-available");
        return out;
    }

    public synchronized void shutdown() {
        stopCapture();
        saves.shutdown();
        clearSegments();
    }

    private synchronized void startCapture() {
        if (!windows()) return;
        try {
            tempDir = Files.createTempDirectory("sky2m-clipping-");
            synchronized (segments) { segments.clear(); }
            running = true;
            worker = new Thread(this::captureLoop, "Sky2M clipping capture");
            worker.setDaemon(true);
            worker.start();
            lastError = "";
        } catch (Exception e) {
            running = false; lastError = msg(e); LOG.error("Could not start clipping", e);
        }
    }

    private void captureLoop() {
        FFmpegFrameGrabber grabber = null;
        FFmpegFrameRecorder recorder = null;
        Path current = null;
        long segmentStart = System.nanoTime();
        try {
            String title = windowTitle(settings.pid);
            if (title == null) throw new IllegalStateException("Couldn't find a visible Minecraft window for this game process.");
            int fps = Math.max(1, settings.fps);
            grabber = new FFmpegFrameGrabber("title=" + title);
            grabber.setFormat("gdigrab");
            grabber.setFrameRate(fps);
            grabber.setOption("draw_mouse", "1");
            grabber.setOption("framerate", Integer.toString(fps));
            grabber.setTimeout(1500);
            grabber.start();
            activeGrabber = grabber;
            int width = even(grabber.getImageWidth()), height = even(grabber.getImageHeight());
            if (width < 2 || height < 2) throw new IllegalStateException("Windows returned an invalid Minecraft window size.");
            recording = true;
            encoder = "FFmpeg H.264 (NVENC)";
            LOG.info("Capturing '{}' at {}x{} {} FPS", title, width, height, fps);
            while (running && !Thread.currentThread().isInterrupted()) {
                Frame frame = grabber.grabImage();
                if (frame == null || frame.image == null) continue;
                long now = System.nanoTime();
                if (recorder == null || now - segmentStart >= 1_000_000_000L) {
                    closeRecorder(recorder);
                    if (current != null) remember(current);
                    current = tempDir.resolve("segment-" + Long.toUnsignedString(now) + ".mp4");
                    recorder = newRecorder(current, width, height, fps);
                    recorder.start();
                    segmentStart = now;
                }
                recorder.record(frame);
                prune();
            }
        } catch (Exception e) {
            if (running) { lastError = msg(e); LOG.error("Capture failed: {}", lastError, e); }
        } finally {
            recording = false; activeGrabber = null;
            closeRecorder(recorder);
            if (current != null && Files.isRegularFile(current)) remember(current);
            if (grabber != null) {
                try { grabber.stop(); } catch (Exception ignored) {}
                try { grabber.release(); } catch (Exception ignored) {}
            }
            if (Thread.currentThread() == worker) worker = null;
            running = false;
        }
    }

    private FFmpegFrameRecorder newRecorder(Path file, int width, int height, int fps) {
        FFmpegFrameRecorder r = new FFmpegFrameRecorder(file.toString(), width, height, 0);
        r.setFormat("mp4");
        r.setFrameRate(fps);
        r.setGopSize(Math.max(1, fps));
        r.setVideoBitrate(8_000_000);
        r.setVideoCodecName("h264_nvenc");
        r.setVideoOption("preset", "p4");
        return r;
    }

    private static void closeRecorder(FFmpegFrameRecorder r) {
        if (r == null) return;
        try { r.stop(); } catch (Exception ignored) {}
        try { r.release(); } catch (Exception ignored) {}
    }

    private Map<String, Object> saveClip(String requestedName) {
        List<Path> copy;
        synchronized (segments) { copy = new ArrayList<>(segments); }
        if (copy.isEmpty()) throw new IllegalStateException("The replay buffer is warming up; try again in a few seconds.");
        Settings cfg = settings;
        try {
            Path folder = cfg.folder.isBlank() ? Path.of(System.getProperty("user.home", "."), "Videos", "Sky2M Clips") : Path.of(cfg.folder);
            Files.createDirectories(folder);
            String name = safeName(requestedName);
            Path output = folder.resolve("Sky2M " + STAMP.format(LocalDateTime.now()) + (name.isBlank() ? "" : " " + name) + ".mp4");
            int width = 0, height = 0;
            for (Path p : copy) {
                if (!Files.isRegularFile(p)) continue;
                try (FFmpegFrameGrabber probe = new FFmpegFrameGrabber(p.toString())) {
                    probe.start(); width = even(probe.getImageWidth()); height = even(probe.getImageHeight()); probe.stop(); break;
                }
            }
            if (width < 2 || height < 2) throw new IllegalStateException("The replay buffer contains no readable frames.");
            FFmpegFrameRecorder out = newRecorder(output, width, height, cfg.fps);
            boolean started = false;
            try {
                out.start(); started = true;
                for (Path p : copy) {
                    if (!Files.isRegularFile(p)) continue;
                    try (FFmpegFrameGrabber input = new FFmpegFrameGrabber(p.toString())) {
                        input.start();
                        Frame f;
                        while ((f = input.grabImage()) != null) if (f.image != null) out.record(f);
                        input.stop();
                    }
                }
            } finally {
                if (started) try { out.stop(); } catch (Exception ignored) {}
                try { out.release(); } catch (Exception ignored) {}
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", output.toAbsolutePath().toString());
            result.put("seconds", Math.min(cfg.seconds, copy.size()));
            return result;
        } catch (Exception e) {
            lastError = msg(e); throw new IllegalStateException("Couldn't save clip: " + lastError, e);
        }
    }

    private void remember(Path p) {
        if (p == null || !Files.isRegularFile(p)) return;
        synchronized (segments) { segments.addLast(p); pruneLocked(); }
    }
    private void prune() { synchronized (segments) { pruneLocked(); } }
    private void pruneLocked() {
        int max = Math.max(2, settings.seconds + 2);
        while (segments.size() > max) {
            Path old = segments.removeFirst();
            try { Files.deleteIfExists(old); } catch (Exception ignored) {}
        }
    }

    private synchronized void stopCapture() {
        running = false;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            try { t.join(1800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        FFmpegFrameGrabber g = activeGrabber;
        if (g != null) {
            try { g.stop(); } catch (Exception ignored) {}
            try { g.release(); } catch (Exception ignored) {}
        }
        recording = false;
    }

    private void clearSegments() {
        synchronized (segments) {
            while (!segments.isEmpty()) try { Files.deleteIfExists(segments.removeFirst()); } catch (Exception ignored) {}
        }
        if (tempDir != null) try { Files.deleteIfExists(tempDir); } catch (Exception ignored) {}
    }

    private static String windowTitle(long pid) {
        if (pid <= 0) return null;
        String[] found = new String[1];
        User32.INSTANCE.EnumWindows((HWND hwnd, com.sun.jna.Pointer data) -> {
            if (!User32.INSTANCE.IsWindowVisible(hwnd)) return true;
            IntByReference processId = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, processId);
            if (Integer.toUnsignedLong(processId.getValue()) != pid) return true;
            char[] title = new char[1024];
            int len = User32.INSTANCE.GetWindowText(hwnd, title, title.length);
            if (len > 0) { found[0] = new String(title, 0, len); return false; }
            return true;
        }, null);
        return found[0];
    }

    private static int even(int n) { return n > 0 ? n & ~1 : n; }
    private static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    private static String safeName(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
    }
    private static String msg(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null || t.getMessage().isBlank() ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static final class Settings {
        long pid; int seconds = 30, fps = 60, height;
        String audio = "none", folder = ""; boolean mic;
        int gameVolume = 100, discordVolume = 100, desktopVolume = 100, micVolume = 100;
        static Settings from(Map<String, Object> m, Settings old) {
            Settings s = new Settings();
            s.pid = number(m, "pid", old.pid);
            s.seconds = clamp(number(m, "seconds", old.seconds), 5, 180);
            s.fps = number(m, "fps", old.fps) == 30 ? 30 : 60;
            s.height = Math.max(0, number(m, "height", old.height));
            s.audio = String.valueOf(m.getOrDefault("audio", old.audio));
            s.mic = Boolean.parseBoolean(String.valueOf(m.getOrDefault("mic", old.mic)));
            s.gameVolume = clamp(number(m, "gameVolume", old.gameVolume), 0, 200);
            s.discordVolume = clamp(number(m, "discordVolume", old.discordVolume), 0, 200);
            s.desktopVolume = clamp(number(m, "desktopVolume", old.desktopVolume), 0, 200);
            s.micVolume = clamp(number(m, "micVolume", old.micVolume), 0, 200);
            s.folder = String.valueOf(m.getOrDefault("folder", old.folder));
            return s;
        }
        boolean sameCapture(Settings o) { return pid == o.pid && fps == o.fps; }
        private static int number(Map<String, Object> m, String k, long d) { Object v = m.get(k); return v instanceof Number n ? (int)n.longValue() : (int)d; }
        private static int clamp(int n, int min, int max) { return Math.max(min, Math.min(max, n)); }
    }
}
