package com.epic60869.sky2m.clipping;

import com.epic60869.sky2m.clipping.capture.CapturedFrame;
import com.epic60869.sky2m.clipping.capture.FrameGrabber;
import com.epic60869.sky2m.clipping.capture.FramePool;
import com.epic60869.sky2m.clipping.config.ClippingConfig;
import com.epic60869.sky2m.clipping.encode.AudioCapture;
import com.epic60869.sky2m.clipping.encode.ClipAssembler;
import com.epic60869.sky2m.clipping.encode.ClipLinks;
import com.epic60869.sky2m.clipping.encode.ClipUploader;
import com.epic60869.sky2m.clipping.encode.ClipLibrary;
import com.epic60869.sky2m.clipping.encode.FfmpegProvider;
import com.epic60869.sky2m.clipping.encode.SegmentRecorder;
import com.epic60869.sky2m.clipping.encode.TempWorkspace;
import com.epic60869.sky2m.clipping.encode.VideoEncoder;
import com.epic60869.sky2m.clipping.ui.Notifications;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the replay buffer lifecycle and enforces the thread split that keeps Minecraft smooth.
 *
 * <p>Two threads touch this class and each has a strict remit:
 * <ul>
 *   <li><b>Render thread</b> — {@link #onFramePresented} only. Everything OpenGL lives here, plus
 *       cheap state checks. It never allocates a process, never does file I/O, never blocks.</li>
 *   <li><b>{@code Sky2M Clipping-worker}</b> — a single background thread that starts and stops FFmpeg,
 *       probes encoders, downloads the binary and assembles clips. Serialising this work on one
 *       thread removes any chance of two saves or a save and a restart racing.</li>
 * </ul>
 */
public final class ReplayBufferService {

	/** Frames allowed in flight; also the cap on capture memory (frameBytes * this). */
	private static final int FRAME_POOL_SIZE = 6;
	/** How long a mismatched window aspect must persist before we restart at the new shape. */
	private static final long ASPECT_RESTART_NANOS = TimeUnit.SECONDS.toNanos(3);
	private static final long RESTART_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(10);
	private static final int MAX_QUEUED_SAVES = 3;

	private enum Stage {
		/** Waiting for FFmpeg, or disabled. */
		IDLE,
		/** A recorder start has been submitted to the worker. */
		STARTING,
		/** Recorder is live; frames are being captured. */
		BUFFERING,
		/** Something failed; {@link #statusMessage} explains it. */
		FAILED
	}

	private final Minecraft client;
	private final FfmpegProvider ffmpegProvider;
	/** Optional bundled watermark (none ships with Sky2M Clipping); overlaid onto the shared (link) copy only. */
	private volatile Path watermarkPng;
	private final ExecutorService worker;
	private final AtomicInteger queuedSaves = new AtomicInteger();

	/** The player's own settings, as saved in config/sky2m-clipping.json. */
	private volatile ClippingConfig savedConfig;
	/** What the buffer actually records with: {@link #savedConfig} with Sky2M's Clips settings layered on. */
	private volatile ClippingConfig config;
	private volatile TempWorkspace workspace;
	private volatile VideoEncoder encoder;
	private volatile ClipAssembler assembler;
	private volatile String statusMessage = "Starting up";
	/** Capture hooks the OpenGL buffer swap: with Minecraft's Vulkan renderer no frame ever arrives. */
	private static final String NEEDS_OPENGL =
			"Clips need the OpenGL renderer: set Graphics Backend to OpenGL in Video Settings and restart.";
	private static final long OPENGL_GRACE_NANOS = TimeUnit.SECONDS.toNanos(10);
	private volatile boolean sawGlFrame;
	private volatile long bootstrapNanos;
	private volatile boolean shuttingDown;
	/** Path of the most recently saved clip, for the "Share last clip" action. */
	private volatile Path lastSavedClip;

	// --- render-thread-only state -------------------------------------------
	private final FrameGrabber grabber = new FrameGrabber();
	private Stage stage = Stage.IDLE;
	private SegmentRecorder recorder;
	private FramePool pool;
	private long nextCaptureNanos;
	private long aspectMismatchSinceNanos;
	private long restartNotBeforeNanos;
	private int activeFps;

	/** Rolling audio capture (mic + desktop), running alongside the video recorder. */
	private volatile AudioCapture audioCapture;

	/** Handed to the worker thread when a recorder is ready to be attached. */
	private volatile SegmentRecorder pendingRecorder;
	private volatile boolean pendingStartFailed;
	private volatile AudioCapture pendingAudio;

	private final ClipLinks links;

	public ReplayBufferService(Minecraft client, ClippingConfig config, Path gameDir) {
		this.client = client;
		this.savedConfig = config;
		this.config = Sky2MBridge.overlay(config);
		this.ffmpegProvider = new FfmpegProvider(gameDir);
		this.links = new ClipLinks(gameDir.resolve("sky2m-clipping").resolve("links.properties"));
		ThreadFactory factory = r -> {
			Thread t = new Thread(r, "Sky2M Clipping-worker");
			t.setDaemon(true);
			return t;
		};
		this.worker = Executors.newSingleThreadExecutor(factory);
	}

	public ClippingConfig config() {
		return config;
	}

	/** The player's own settings, without Sky2M's Clips settings; what the settings screen edits. */
	public ClippingConfig persistedConfig() {
		return savedConfig;
	}

	/** The selected video encoder, or an empty string before it has been picked. */
	public String encoderName() {
		VideoEncoder e = encoder;
		return e == null ? "" : e.toString();
	}

	public String statusMessage() {
		return noOpenGl() ? NEEDS_OPENGL : statusMessage;
	}

	/** True once the game has been up a while without presenting a single OpenGL frame (the Vulkan renderer). */
	private boolean noOpenGl() {
		long since = bootstrapNanos;
		return !sawGlFrame && since != 0L && System.nanoTime() - since > OPENGL_GRACE_NANOS;
	}

	public boolean isBuffering() {
		return stage == Stage.BUFFERING;
	}

	public FfmpegProvider ffmpegProvider() {
		return ffmpegProvider;
	}

	public SegmentRecorder currentRecorder() {
		return recorder;
	}

	public FrameGrabber grabber() {
		return grabber;
	}

	/**
	 * Live microphone loudness (0–1) from the running capture, or {@code -1} when the mic isn't being
	 * captured. Lets the Mic Test reuse the recorder's line instead of opening a conflicting second one.
	 */
	public float liveMicLevel() {
		AudioCapture ac = audioCapture;
		return ac != null && ac.hasMic() ? ac.micLevel() : -1f;
	}

	// ------------------------------------------------------------------ boot

	/** Kicks off temp cleanup, FFmpeg resolution and encoder probing on the worker thread. */
	public void bootstrap() {
		bootstrapNanos = System.nanoTime();
		worker.execute(() -> {
			try {
				workspace = TempWorkspace.open(config.resolveTempFolder());
			} catch (IOException e) {
				statusMessage = "Could not prepare the temp folder: " + e.getMessage();
				Sky2MClipping.LOGGER.error("Temp workspace setup failed", e);
				return;
			}

			statusMessage = "Locating FFmpeg";
			if (!ffmpegProvider.resolve(config.allowFfmpegDownload)) {
				statusMessage = ffmpegProvider.errorMessage();
				return;
			}

			statusMessage = "Selecting an encoder";
			encoder = VideoEncoder.probe(ffmpegProvider.executable(), ffmpegProvider.encoderNames(), config.encoder);
			assembler = new ClipAssembler(ffmpegProvider.executable());
			watermarkPng = extractWatermark();
			statusMessage = "Ready";
			Sky2MClipping.LOGGER.info("Sky2M Clipping ready — {}, clips go to {}", encoder, config.resolveOutputFolder());
		});
	}

	private boolean readyToRecord() {
		return workspace != null && assembler != null && encoder != null
				&& ffmpegProvider.state() == FfmpegProvider.State.READY;
	}

	/** Extracts the bundled watermark PNG next to ffmpeg so it can be overlaid onto shared clips. */
	private Path extractWatermark() {
		try {
			Path dest = ffmpegProvider.executable().getParent().resolve("watermark.png");
			try (java.io.InputStream in = getClass().getResourceAsStream("/assets/sky2m-clipping/watermark.png")) {
				if (in == null) {
					return null;
				}
				Files.createDirectories(dest.getParent());
				Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			return dest;
		} catch (IOException e) {
			Sky2MClipping.LOGGER.warn("Could not extract the watermark image", e);
			return null;
		}
	}

	// --------------------------------------------------------- render thread

	/**
	 * Called at the top of {@code RenderSystem.flipFrame}, i.e. after Minecraft has blitted the
	 * finished frame — world, HUD and any open GUI — into the window's back buffer but before it
	 * is presented. Must never throw into Minecraft.
	 */
	public void onFramePresented(int framebufferWidth, int framebufferHeight) {
		sawGlFrame = true;
		try {
			tick(framebufferWidth, framebufferHeight);
		} catch (Throwable t) {
			Sky2MClipping.LOGGER.error("Sky2M Clipping capture tick failed; backing off before retrying", t);
			statusMessage = "Capture failed: " + t;
			detachRecorder();
			stage = Stage.FAILED;
			restartNotBeforeNanos = System.nanoTime() + RESTART_BACKOFF_NANOS;
		}
	}

	private void tick(int fbWidth, int fbHeight) {
		long now = System.nanoTime();

		if (shuttingDown || !config.enabled || !readyToRecord()) {
			if (stage != Stage.IDLE && stage != Stage.FAILED) {
				detachRecorder();
				stage = Stage.IDLE;
			}
			return;
		}

		// Buffer everywhere - in a world AND on menus (title, multiplayer, pause) - so a clip or
		// screenshot can be grabbed from a menu too. (Costs a little GPU while sitting in menus.)

		if (fbWidth <= 0 || fbHeight <= 0) {
			return; // Minimised.
		}

		switch (stage) {
			case IDLE, FAILED -> maybeStart(now, fbWidth, fbHeight);
			case STARTING -> attachIfReady();
			case BUFFERING -> captureIfDue(now, fbWidth, fbHeight);
		}
	}

	private void maybeStart(long now, int fbWidth, int fbHeight) {
		if (now < restartNotBeforeNanos) {
			return;
		}
		ClippingConfig cfg = config;
		int[] size = com.epic60869.sky2m.clipping.capture.CaptureMath.fitCaptureSize(fbWidth, fbHeight, cfg.maxWidth, cfg.maxHeight);
		int captureWidth = size[0];
		int captureHeight = size[1];

		stage = Stage.STARTING;
		pendingRecorder = null;
		pendingStartFailed = false;
		activeFps = cfg.fps;

		// Only used on GPUs that cannot persistently map a readback buffer; where they can, the
		// grabber hands mapped frames straight to the encoder and this pool never allocates. The
		// constructor only sizes the free list, so building it here is cheap enough for the render
		// thread either way.
		this.pool = new FramePool(captureWidth * captureHeight * 4, FRAME_POOL_SIZE);

		worker.execute(() -> {
			try {
				SegmentRecorder rec = new SegmentRecorder(
						ffmpegProvider.executable(), workspace.segmentsDir(),
						captureWidth, captureHeight, cfg.fps, cfg.segmentSeconds,
						cfg.ringSegmentCount(), cfg.videoBitrateKbps, encoder);
				// Audio is captured separately (Java Sound) so it can never stall the video encoder.
				// The ring covers a whole segment more than the longest clip: a save takes whole
				// segments, so the video it assembles starts up to one segment before the requested
				// window and the sound for that head has to still be in the ring.
				AudioCapture ac = null;
				if (cfg.captureMicrophone || cfg.captureGameAudio) {
					AudioCapture cap = new AudioCapture(cfg.clipDurationSeconds + cfg.segmentSeconds + 3,
							ffmpegProvider.executable().getParent());
					if (cap.start(cfg.captureMicrophone, cfg.micDevice, cfg.micVolume,
							cfg.captureGameAudio, cfg.pcAudioDevices, cfg.pcAudioVolume)) {
						ac = cap;
					}
				}
				if (rec.start()) {
					pendingRecorder = rec;
					pendingAudio = ac;
				} else {
					pendingStartFailed = true;
					if (ac != null) {
						ac.stop();
					}
				}
			} catch (Exception e) {
				Sky2MClipping.LOGGER.error("Could not start the replay buffer", e);
				statusMessage = "Could not start the replay buffer: " + e.getMessage();
				pendingStartFailed = true;
			}
		});
	}

	private void attachIfReady() {
		if (pendingStartFailed) {
			stage = Stage.FAILED;
			restartNotBeforeNanos = System.nanoTime() + RESTART_BACKOFF_NANOS;
			pendingStartFailed = false;
			return;
		}
		SegmentRecorder rec = pendingRecorder;
		if (rec == null) {
			return; // Still spawning.
		}
		pendingRecorder = null;

		if (!grabber.init(rec.width(), rec.height(), pool, this::onFrameCaptured)) {
			statusMessage = "The GPU rejected the capture surface; see the log.";
			stage = Stage.FAILED;
			restartNotBeforeNanos = System.nanoTime() + RESTART_BACKOFF_NANOS;
			worker.execute(rec::stop);
			AudioCapture failedAudio = pendingAudio;
			pendingAudio = null;
			if (failedAudio != null) {
				worker.execute(failedAudio::stop);
			}
			return;
		}

		recorder = rec;
		audioCapture = pendingAudio;
		pendingAudio = null;
		stage = Stage.BUFFERING;
		nextCaptureNanos = System.nanoTime();
		aspectMismatchSinceNanos = 0L;
		statusMessage = "Buffering";
	}

	private void captureIfDue(long now, int fbWidth, int fbHeight) {
		SegmentRecorder rec = recorder;
		if (rec == null || !rec.isRunning()) {
			String why = rec == null ? "recorder went away" : rec.failure();
			Sky2MClipping.LOGGER.warn("Replay buffer stopped unexpectedly ({}); restarting shortly", why);
			statusMessage = why != null ? why : "Replay buffer stopped";
			detachRecorder();
			stage = Stage.IDLE;
			restartNotBeforeNanos = now + RESTART_BACKOFF_NANOS;
			return;
		}

		if (checkAspectDrift(now, fbWidth, fbHeight)) {
			return;
		}

		// Pace to the configured frame rate. Rendering faster than this simply drops the extra
		// frames; rendering slower is corrected by the encoder thread duplicating frames.
		long period = 1_000_000_000L / activeFps;
		if (now < nextCaptureNanos) {
			return;
		}
		// Re-base rather than accumulate so a long stall cannot produce a burst of catch-up frames.
		nextCaptureNanos = (now - nextCaptureNanos > period * 4) ? now + period : nextCaptureNanos + period;

		grabber.capture(fbWidth, fbHeight, now);
	}

	/**
	 * Resolution changes are absorbed for free by the scaling blit, but a genuine aspect-ratio
	 * change would letterbox every later frame. If it persists, restart at the new shape.
	 */
	private boolean checkAspectDrift(long now, int fbWidth, int fbHeight) {
		double windowAspect = (double) fbWidth / fbHeight;
		double captureAspect = (double) grabber.width() / grabber.height();
		if (Math.abs(windowAspect - captureAspect) / captureAspect < 0.02) {
			aspectMismatchSinceNanos = 0L;
			return false;
		}
		if (aspectMismatchSinceNanos == 0L) {
			aspectMismatchSinceNanos = now;
			return false;
		}
		if (now - aspectMismatchSinceNanos < ASPECT_RESTART_NANOS) {
			return false;
		}
		Sky2MClipping.LOGGER.info("Window aspect changed ({}x{}); restarting the replay buffer", fbWidth, fbHeight);
		detachRecorder();
		stage = Stage.IDLE;
		aspectMismatchSinceNanos = 0L;
		return true;
	}

	/** Render thread → encoder thread handoff. */
	private void onFrameCaptured(CapturedFrame frame) {
		SegmentRecorder rec = recorder;
		if (rec != null) {
			rec.offer(frame);
		} else {
			frame.release();
		}
	}

	/** Tears down GL resources here and hands the FFmpeg shutdown to the worker. */
	private void detachRecorder() {
		grabber.close();
		SegmentRecorder rec = recorder;
		recorder = null;
		pool = null;
		AudioCapture ac = audioCapture;
		audioCapture = null;
		if (rec != null) {
			worker.execute(rec::stop);
		}
		if (ac != null) {
			worker.execute(ac::stop);
		}
	}

	// ------------------------------------------------------------------ save

	/** Saves the full buffered window (the longest configured clip length). */
	public void saveClip() {
		saveClip(config.clipDurationSeconds);
	}

	/**
	 * Saves the previous {@code requestedSeconds} of footage. Returns immediately; the work happens on
	 * the worker thread. {@code requestedSeconds} is clamped to the buffered window, so a per-hotkey
	 * clip length can never ask for more than the ring actually holds.
	 */
	public void saveClip(int requestedSeconds) {
		saveClip(requestedSeconds, null, false);
	}

	/**
	 * Like {@link #saveClip(int)}, but names the file after {@code name} when it isn't blank and returns a future
	 * with the saved clip, or failed with the reason it couldn't be saved. With {@code quiet}, no toasts are shown:
	 * the caller (Sky2M) reports the result itself.
	 */
	public CompletableFuture<ClipAssembler.Result> saveClip(int requestedSeconds, String name, boolean quiet) {
		CompletableFuture<ClipAssembler.Result> future = new CompletableFuture<>();
		if (!isBuffering() || recorder == null) {
			fail(future, notReadyReason(), quiet);
			return future;
		}
		if (queuedSaves.get() >= MAX_QUEUED_SAVES) {
			fail(future, "Still saving the previous clips — give it a moment.", quiet);
			return future;
		}

		SegmentRecorder rec = recorder;
		SegmentRecorder.Layout layout = rec.layout();
		ClippingConfig cfg = config;
		int seconds = Math.max(1, Math.min(requestedSeconds, cfg.clipDurationSeconds));
		Path outputDir = cfg.resolveOutputFolder();
		Path workRoot = workspace.clipWorkDir();
		ClipAssembler asm = assembler;
		AudioCapture ac = audioCapture;
		// The assembler asks for exactly the stretch of sound that matches the video it picked, cut
		// from the recorder's own clock — that is what keeps the two in sync.
		ClipAssembler.AudioTrack audio = ac != null && ac.hasSources() ? ac::writeClipWav : null;

		queuedSaves.incrementAndGet();
		if (!quiet) {
			Notifications.saving(client, seconds);
		}

		worker.execute(() -> {
			try {
				ClipAssembler.Result result = asm.assemble(layout, seconds, outputDir, workRoot,
						audio, rec.timelineOriginNanos());
				result = named(result, name);
				Sky2MClipping.LOGGER.info("Saved {} ({}s)", result.file(), String.format("%.1f", result.seconds()));
				if (!quiet) {
					Notifications.saved(client, result);
				}
				lastSavedClip = result.file();
				future.complete(result);
				// No auto-upload: a share link is only generated on explicit request (Clips list
				// right-click → Generate link, or the in-game editor's Generate link button).
			} catch (ClipAssembler.ClipException e) {
				Sky2MClipping.LOGGER.error("Clip save failed", e);
				fail(future, e.getMessage(), quiet);
			} catch (Exception e) {
				Sky2MClipping.LOGGER.error("Unexpected error while saving a clip", e);
				fail(future, "Unexpected error: " + e, quiet);
			} finally {
				queuedSaves.decrementAndGet();
			}
		});
		return future;
	}

	private void fail(CompletableFuture<?> future, String message, boolean quiet) {
		if (!quiet) {
			Notifications.error(client, message);
		}
		future.completeExceptionally(new IllegalStateException(message));
	}

	/** Renames a saved clip after the name the player gave it (/s2 clip <name>), keeping it unique. */
	private static ClipAssembler.Result named(ClipAssembler.Result result, String name) {
		if (name == null) {
			return result;
		}
		String clean = name.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
		if (clean.isBlank() || clean.startsWith(".")) {
			return result;
		}
		if (clean.length() > 80) {
			clean = clean.substring(0, 80);
		}
		Path dir = result.file().getParent();
		Path target = dir.resolve(clean + ".mp4");
		for (int i = 2; Files.exists(target); i++) {
			target = dir.resolve(clean + " (" + i + ").mp4");
		}
		try {
			Files.move(result.file(), target);
			return new ClipAssembler.Result(target, result.seconds());
		} catch (IOException e) {
			Sky2MClipping.LOGGER.warn("Could not rename {} to {}", result.file(), target, e);
			return result;
		}
	}

	/**
	 * Uploads a specific clip file and copies the link — used by the Clips screen's
	 * "generate &amp; copy link". Non-blocking; the upload runs on the worker thread.
	 */
	public void shareClipFile(Path file) {
		shareOrCopy(file, null);
	}

	/** True if {@code file} already has a generated share link (so "Copy link" can be instant). */
	public boolean hasLink(Path file) {
		return links.has(file);
	}

	/** Forgets the cached share link for a file — call after its content is overwritten so a fresh
	 * link must be generated (the old upload still holds the pre-edit video). */
	public void forgetLink(Path file) {
		links.remove(file);
	}

	/**
	 * Copies an existing share link to the clipboard, or uploads to generate one first and then copies
	 * it. If {@code status} is non-null it receives short UI messages on the client thread (used by the
	 * editor); otherwise progress is shown as toasts (used by the clip list). Non-blocking.
	 */
	public void shareOrCopy(Path file, Consumer<String> status) {
		if (file == null || !Files.isRegularFile(file)) {
			report(status, "That clip no longer exists.", true);
			return;
		}
		String cached = links.get(file);
		if (cached != null) {
			copyLink(cached, status);
			return;
		}
		if (status != null) {
			client.execute(() -> status.accept("Uploading…"));
		} else {
			Notifications.uploading(client);
		}
		ClippingConfig cfg = config;
		worker.execute(() -> {
			ClipUploader.Service target = switch (cfg.shareTarget) {
				// CLIPS was Clipify's own upload server, which Sky2M Clipping doesn't use.
				case CLIPS, CATBOX -> ClipUploader.Service.CATBOX;
				case LITTERBOX -> ClipUploader.Service.LITTERBOX;
			};
			// Upload a watermarked copy instead of the file itself: the "sky2m-clipping" mark lives only on the
			// shared copy, never on the local clip. Same resolution and (near-)same quality as the
			// recording — the link is meant to look like what was recorded.
			Path toUpload = file;
			Path shareTmp = null;
			try {
				if (watermarkPng != null && workspace != null
						&& ffmpegProvider.state() == FfmpegProvider.State.READY) {
					Path tmp = workspace.clipWorkDir().resolve("share-" + System.nanoTime() + ".mp4");
					try {
						ClipLibrary.shareCopy(ffmpegProvider.executable(), file, watermarkPng, tmp);
						toUpload = tmp;
						shareTmp = tmp;
					} catch (IOException wmErr) {
						Sky2MClipping.LOGGER.warn("Watermark/downscale failed — uploading the raw clip instead", wmErr);
					}
				}
				String url = ClipUploader.upload(toUpload, target, cfg.litterboxHours);
				links.put(file, url);
				copyLink(url, status);
			} catch (ClipUploader.UploadException e) {
				Sky2MClipping.LOGGER.error("Clip upload failed", e);
				report(status, "Upload failed: " + e.getMessage(), true);
			} finally {
				if (shareTmp != null) {
					try {
						Files.deleteIfExists(shareTmp);
					} catch (IOException ignored) {
						// swept on the next temp cleanup
					}
				}
			}
		});
	}

	private void copyLink(String url, Consumer<String> status) {
		if (status != null) {
			client.execute(() -> {
				client.keyboardHandler.setClipboard(url);
				status.accept("Link copied");
			});
		} else {
			client.execute(() -> Notifications.shared(client, url)); // sets clipboard + toast
		}
	}

	private void report(Consumer<String> status, String message, boolean error) {
		if (status != null) {
			client.execute(() -> status.accept(message));
		} else if (error) {
			Notifications.error(client, message);
		}
	}

	private String notReadyReason() {
		if (noOpenGl()) {
			return NEEDS_OPENGL;
		}
		FfmpegProvider.State s = ffmpegProvider.state();
		return switch (s) {
			case DOWNLOADING -> "Still downloading the video encoder (" + ffmpegProvider.downloadPercent() + "%).";
			case INSTALLING -> "Still installing the video encoder.";
			case SEARCHING, IDLE -> "The replay buffer is still starting up.";
			case FAILED -> ffmpegProvider.errorMessage();
			case READY -> config.enabled
					? "The replay buffer is still starting up."
					: "Sky2M Clipping is disabled in the config.";
		};
	}

	// -------------------------------------------------------------- reconfig

	/** Applies edited settings: persists them and rebuilds the buffer with the new parameters. */
	public void applyConfig(ClippingConfig updated) {
		updated.validated();
		this.savedConfig = updated;
		updated.save();
		restartWith(Sky2MBridge.overlay(updated));
	}

	/** Re-layers Sky2M's Clips settings (after they change) and restarts the buffer; nothing is saved. */
	public void refreshSky2MSettings() {
		if (shuttingDown) {
			return;
		}
		restartWith(Sky2MBridge.overlay(savedConfig));
	}

	private void restartWith(ClippingConfig updated) {
		this.config = updated;
		client.execute(() -> {
			detachRecorder();
			stage = Stage.IDLE;
			restartNotBeforeNanos = 0L;
		});
		worker.execute(() -> {
			if (ffmpegProvider.state() == FfmpegProvider.State.READY) {
				encoder = VideoEncoder.probe(ffmpegProvider.executable(), ffmpegProvider.encoderNames(), updated.encoder);
			}
		});
	}

	// -------------------------------------------------------------- shutdown

	public void shutdown() {
		shuttingDown = true;
		SegmentRecorder rec = recorder;
		recorder = null;
		// GL teardown only if we are on the render thread; otherwise the context may already be
		// gone and the process is exiting anyway.
		if (client.isSameThread()) {
			grabber.close();
		}
		if (rec != null) {
			rec.stop();
		}
		worker.shutdown();
		try {
			if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
				worker.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			worker.shutdownNow();
		}
		TempWorkspace ws = workspace;
		if (ws != null) {
			ws.close();
		}
		Sky2MClipping.LOGGER.info("Sky2M Clipping shut down cleanly");
	}

	// ------------------------------------------------------------- utilities

}
