package com.epic60869.sky2m.clipping.capture;

import com.epic60869.sky2m.clipping.ClippingLog;
import com.epic60869.sky2m.clipping.compat.GpuReadback;
import com.mojang.blaze3d.systems.RenderSystem;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Frame capture through Minecraft's backend-neutral GPU API, for graphics backends with no OpenGL back buffer to read
 * (Vulkan). {@link FrameGrabber} stays the OpenGL path: it hands persistently mapped pixel buffers straight to the
 * encoder, which this cannot match.
 *
 * <p>Each captured frame is scaled into a capture-sized texture on the GPU and copied into one of a small ring of
 * mappable buffers ({@link GpuReadback}). Minecraft calls back on the render thread once the GPU has written a buffer;
 * the buffer is mapped there and a converter thread turns it into the top-down BGRA the encoder takes (GPU textures
 * read back bottom-up RGBA), so the render thread never touches pixels. The converter hands the buffer back, and the
 * next capture unmaps it on the render thread.
 *
 * <p>As with {@link FrameGrabber}, a frame is skipped rather than queued when no slot or pooled buffer is free, and a
 * ring is only destroyed once every slot has come back.
 */
public final class GpuFrameGrabber {

	private static final int SLOTS = 4;
	/** Consecutive failed readbacks tolerated before capture is disabled. */
	private static final int MAX_ERROR_STREAK = 120;

	/** One thread, so frames reach the encoder in the order the GPU finished them. */
	private static final ExecutorService CONVERTER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Sky2M Clipping-convert");
		t.setDaemon(true);
		// Like the encoder writer: falling behind costs a dropped capture, never the game's frame time.
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});

	private Ring ring;
	/** Rings closed while slots were still out; destroyed once every slot has come back. */
	private final List<Ring> retiredRings = new ArrayList<>();

	private int width;
	private int height;
	private boolean initialized;
	private boolean broken;

	private FramePool pool;
	private Consumer<CapturedFrame> sink;

	private final AtomicLong framesEmitted = new AtomicLong();
	private long framesDroppedNoBuffer;
	private long framesSkippedGpuBusy;
	private int errorStreak;

	/**
	 * Allocates the capture texture and readback ring. Must run on the render thread.
	 *
	 * @return false if the GPU rejected the setup, in which case this grabber stays disabled
	 */
	public boolean init(int captureWidth, int captureHeight, FramePool framePool, Consumer<CapturedFrame> frameSink) {
		RenderSystem.assertOnRenderThread();
		close();
		this.width = captureWidth;
		this.height = captureHeight;
		this.pool = framePool;
		this.sink = frameSink;
		this.broken = false;
		this.errorStreak = 0;
		try {
			ring = new Ring(new GpuReadback(captureWidth, captureHeight, SLOTS), SLOTS);
		} catch (RuntimeException e) {
			ClippingLog.LOGGER.error("Sky2M Clipping could not create its GPU capture surface", e);
			broken = true;
			return false;
		}
		initialized = true;
		ClippingLog.LOGGER.info("Capture surface ready: {}x{} (GPU readback, {} slots)", width, height, SLOTS);
		return true;
	}

	public boolean isReady() {
		return initialized && !broken;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/**
	 * Queues a readback of the texture about to be presented.
	 *
	 * @param source   the presented {@code GpuTextureView}; scaled to the capture size on the GPU
	 * @param nowNanos presentation timestamp for this frame
	 */
	public void capture(Object source, long nowNanos) {
		RenderSystem.assertOnRenderThread();
		if (!isReady() || source == null) {
			return;
		}
		reclaimSlots();

		Ring owner = ring;
		Integer claimed = owner.claim();
		if (claimed == null) {
			framesSkippedGpuBusy++;
			return;
		}
		int slot = claimed;
		boolean issued = false;
		try {
			issued = owner.readback.capture(source, slot, () -> onCopied(owner, slot, nowNanos));
			if (!issued) {
				framesSkippedGpuBusy++;
			}
		} catch (RuntimeException e) {
			ClippingLog.LOGGER.error("Sky2M Clipping GPU frame capture failed; disabling capture", e);
			broken = true;
		} finally {
			if (!issued) {
				owner.recycle(slot);
			}
		}
	}

	/** Render thread, from Minecraft's pending GPU tasks: the copy into {@code slot} has landed. */
	private void onCopied(Ring owner, int slot, long nanos) {
		if (owner.retired) {
			owner.recycle(slot);
			return;
		}
		FramePool framePool = pool;
		Consumer<CapturedFrame> frameSink = sink;
		ByteBuffer dst = framePool != null ? framePool.acquire() : null;
		if (dst == null || frameSink == null) {
			framesDroppedNoBuffer++;
			owner.recycle(slot);
			return;
		}
		ByteBuffer src;
		try {
			src = owner.readback.map(slot);
		} catch (RuntimeException e) {
			framePool.release(dst);
			owner.recycle(slot);
			if (errorStreak == 0) {
				ClippingLog.LOGGER.error("Sky2M Clipping could not map a GPU readback; a frame was dropped", e);
			}
			if (++errorStreak >= MAX_ERROR_STREAK) {
				ClippingLog.LOGGER.error("Sky2M Clipping GPU readback failed {} times in a row; disabling capture",
						MAX_ERROR_STREAK);
				broken = true;
			}
			return;
		}
		errorStreak = 0;
		int w = width;
		int h = height;
		CONVERTER.execute(() -> {
			boolean handedOver = false;
			try {
				CaptureMath.rgbaBottomUpToBgraTopDown(src, dst, w, h);
				framesEmitted.incrementAndGet();
				frameSink.accept(new CapturedFrame(dst, nanos, () -> framePool.release(dst)));
				handedOver = true;
			} catch (Throwable t) {
				ClippingLog.LOGGER.error("Sky2M Clipping could not convert a captured frame", t);
			} finally {
				if (!handedOver) {
					framePool.release(dst);
				}
				// Unmapping is GPU-API work, so it waits for the render thread.
				owner.converted.add(slot);
			}
		});
	}

	/** Unmaps and frees every slot the converter has finished with, and destroys drained retired rings. */
	private void reclaimSlots() {
		if (ring != null) {
			ring.reclaim();
		}
		retiredRings.removeIf(r -> {
			r.reclaim();
			if (!r.drained()) {
				return false;
			}
			r.destroy();
			return true;
		});
	}

	/** Releases the GPU objects, or retires them until outstanding slots come back. Render thread only. */
	public void close() {
		if (!initialized && ring == null && retiredRings.isEmpty()) {
			return;
		}
		RenderSystem.assertOnRenderThread();
		if (ring != null) {
			ring.retired = true;
			retiredRings.add(ring);
			ring = null;
		}
		reclaimSlots();
		initialized = false;
		pool = null;
		sink = null;
	}

	public long framesEmitted() {
		return framesEmitted.get();
	}

	public long framesDroppedNoBuffer() {
		return framesDroppedNoBuffer;
	}

	public long framesSkippedGpuBusy() {
		return framesSkippedGpuBusy;
	}

	/** A readback ring and which of its slots are free, being converted, or waiting to be unmapped. */
	private static final class Ring {

		final GpuReadback readback;
		private final ConcurrentLinkedQueue<Integer> free = new ConcurrentLinkedQueue<>();
		/** Slots the converter is done with; still mapped until the render thread unmaps them. */
		final ConcurrentLinkedQueue<Integer> converted = new ConcurrentLinkedQueue<>();
		private final AtomicInteger checkedOut = new AtomicInteger();
		/** Set on the render thread once this ring is no longer capturing. */
		boolean retired;

		Ring(GpuReadback readback, int slots) {
			this.readback = readback;
			for (int i = 0; i < slots; i++) {
				free.add(i);
			}
		}

		Integer claim() {
			Integer slot = free.poll();
			if (slot != null) {
				checkedOut.incrementAndGet();
			}
			return slot;
		}

		void recycle(int slot) {
			free.add(slot);
			checkedOut.decrementAndGet();
		}

		/** Render thread. */
		void reclaim() {
			Integer slot;
			while ((slot = converted.poll()) != null) {
				readback.unmap(slot);
				recycle(slot);
			}
		}

		boolean drained() {
			return checkedOut.get() == 0;
		}

		void destroy() {
			readback.close();
		}
	}
}
