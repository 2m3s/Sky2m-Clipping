package com.epic60869.sky2m.clipping.mixin;

import com.epic60869.sky2m.clipping.Sky2MClipping;
import com.mojang.blaze3d.opengl.GlSurface;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The capture hooks, on Minecraft 26.2's backend-neutral window surface (com.mojang.blaze3d.systems.GpuSurface).
 *
 * <p>OpenGL: the head of {@code present()} comes just before the buffer swap, the last moment the window's back buffer
 * holds the finished frame (world, HUD and any open screen) exactly as the player sees it.
 *
 * <p>Every other backend (Vulkan): the head of {@code blitFromTexture} receives the main render target's colour
 * texture, which is that same finished frame, just before it is copied to the window. It is read back through the
 * GPU API instead.
 */
@Mixin(GpuSurface.class)
public class PresentMixin {

	@Shadow
	@Final
	private GpuSurfaceBackend backend;

	@Inject(method = "blitFromTexture", at = @At("HEAD"))
	private void sky2mClipping$captureTexture(CommandEncoder commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		if (!(backend instanceof GlSurface) && textureView != null) {
			Sky2MClipping.onFrameRendered(textureView, textureView.getWidth(0), textureView.getHeight(0));
		}
	}

	@Inject(method = "present", at = @At("HEAD"))
	private void sky2mClipping$captureFrame(CallbackInfo ci) {
		if (!(backend instanceof GlSurface)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Window window = mc != null ? mc.getWindow() : null;
		if (window != null) {
			Sky2MClipping.onFramePresented(window.getWidth(), window.getHeight());
		}
	}
}
