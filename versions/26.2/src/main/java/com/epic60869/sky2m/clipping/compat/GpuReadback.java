package com.epic60869.sky2m.clipping.compat;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.RenderPipelines;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Minecraft 26.2's backend-neutral readback (com.mojang.blaze3d.systems): what the frame grabber uses on Vulkan, where
 * there is no OpenGL back buffer to read. It scales the presented texture into a capture-sized texture on the GPU (the
 * same blit Minecraft's Tracy frame capture uses) and copies that into mappable buffers, the way vanilla screenshots
 * read a frame back. Only the packages and a few call shapes differ between versions. Render thread only.
 */
public final class GpuReadback {
	private final GpuTexture texture;
	private final GpuTextureView view;
	private final GpuBuffer[] buffers;
	private final GpuBufferSlice.MappedView[] mapped;

	public GpuReadback(int width, int height, int slots) {
		GpuDevice device = RenderSystem.getDevice();
		buffers = new GpuBuffer[slots];
		mapped = new GpuBufferSlice.MappedView[slots];
		GpuTexture tex = null;
		GpuTextureView texView = null;
		try {
			tex = device.createTexture("Sky2M Clipping capture",
					GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
			texView = device.createTextureView(tex);
			long bytes = (long) width * height * 4L;
			for (int i = 0; i < slots; i++) {
				buffers[i] = device.createBuffer(() -> "Sky2M Clipping readback",
						GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
			}
		} catch (RuntimeException e) {
			for (GpuBuffer b : buffers) {
				if (b != null) {
					b.close();
				}
			}
			if (texView != null) {
				texView.close();
			}
			if (tex != null) {
				tex.close();
			}
			throw e;
		}
		texture = tex;
		view = texView;
	}

	/**
	 * Scales {@code source} (the {@code GpuTextureView} being presented) into the capture texture and queues its copy
	 * into {@code slot}'s buffer. {@code onCopied} runs later on the render thread, once the GPU has written the buffer.
	 *
	 * @return false if the frame could not be queued this time (nothing was recorded)
	 */
	public boolean capture(Object source, int slot, Runnable onCopied) {
		GpuTextureView src = (GpuTextureView) source;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		try (RenderPass pass = encoder.createRenderPass(() -> "Sky2M Clipping capture", view, Optional.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setPipeline(RenderPipelines.TRACY_BLIT);
			pass.bindTexture("InSampler", src, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(3, 1, 0, 0);
		}
		encoder.copyTextureToBuffer(texture, buffers[slot], 0L, onCopied, 0);
		return true;
	}

	/** Maps a slot whose copy has completed. Valid until {@link #unmap}; safe to read from another thread. */
	public ByteBuffer map(int slot) {
		mapped[slot] = buffers[slot].map(true, false);
		return mapped[slot].data();
	}

	public void unmap(int slot) {
		GpuBufferSlice.MappedView m = mapped[slot];
		mapped[slot] = null;
		if (m != null) {
			m.close();
		}
	}

	/** Frees everything. Every slot must have been unmapped and no copy may still be pending. */
	public void close() {
		for (int i = 0; i < buffers.length; i++) {
			unmap(i);
			buffers[i].close();
		}
		view.close();
		texture.close();
	}
}
