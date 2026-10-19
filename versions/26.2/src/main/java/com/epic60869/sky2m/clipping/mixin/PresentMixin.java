package com.epic60869.sky2m.clipping.mixin;

import com.epic60869.sky2m.clipping.Sky2MClipping;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.opengl.GlSurface;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The capture hook. On Minecraft 26.2, {@code GlSurface.present()} is just the buffer swap (glfwSwapBuffers), so its head
 * is the last moment the window's back buffer holds the finished frame (world, HUD and any open screen) exactly as
 * the player sees it. Only the OpenGL renderer goes through here; with Vulkan nothing is captured.
 */
@Mixin(GlSurface.class)
public class PresentMixin {

	@Inject(method = "present", at = @At("HEAD"))
	private void sky2mClipping$captureFrame(CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		Window window = mc != null ? mc.getWindow() : null;
		if (window != null) {
			Sky2MClipping.onFramePresented(window.getWidth(), window.getHeight());
		}
	}
}
