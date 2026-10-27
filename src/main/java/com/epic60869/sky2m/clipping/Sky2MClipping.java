package com.epic60869.sky2m.clipping;

import com.epic60869.sky2m.clipping.config.ClippingConfig;
import com.epic60869.sky2m.clipping.config.ClippingConfig.Hotkey;
import com.epic60869.sky2m.clipping.encode.ClipLibrary;
import com.epic60869.sky2m.clipping.ui.Notifications;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import com.epic60869.sky2m.clipping.compat.Input;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client entrypoint. Wires the lifecycle hooks to {@link ReplayBufferService} and dispatches the
 * user-defined {@linkplain ClippingConfig#hotkeys hotkeys}.
 *
 * <p>Sky2M Clipping no longer registers vanilla {@code KeyMapping}s (so nothing shows up in Options →
 * Controls): the hotkey list lives entirely in the Sky2M Clipping settings and is polled here each tick.
 * That is what lets a user bind several keys to the same action — e.g. a different key per clip
 * length.
 */
public final class Sky2MClipping implements ClientModInitializer {

	public static final String MOD_ID = "sky2m-clipping";
	public static final Logger LOGGER = ClippingLog.LOGGER;

	private static volatile ReplayBufferService service;

	/** Signatures of hotkeys whose combo was already held last tick, so each press fires exactly once. */
	private final Set<String> downLastTick = new HashSet<>();

	@Override
	public void onInitializeClient() {
		ClippingConfig config = ClippingConfig.load();
		Minecraft client = Minecraft.getInstance();

		ReplayBufferService svc = new ReplayBufferService(client, config, FabricLoader.getInstance().getGameDir());
		service = svc;

		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
		// IMPORTANT: bootstrap() spawns FFmpeg subprocesses (including an NVENC probe that creates a
		// GPU context). onInitializeClient runs BEFORE Minecraft initialises GLFW/OpenGL, and doing
		// GPU-touching work concurrently with glfwInit intermittently breaks the NVIDIA driver's
		// context creation ("GLFW error before init"). CLIENT_STARTED fires only once the window and
		// GL context are fully up, so all of that heavy work now happens safely after init.
		ClientLifecycleEvents.CLIENT_STARTED.register(c -> svc.bootstrap());
		ClientLifecycleEvents.CLIENT_STOPPING.register(c -> shutdown());

		LOGGER.info("Sky2M Clipping loaded — {} hotkey(s), {}s max buffer (edit them in Sky2M Clipping settings → Hotkeys)",
				config.hotkeys.size(), config.clipDurationSeconds);
	}

	/** The live service, or null before {@code onInitializeClient} has run. */
	public static ReplayBufferService service() {
		return service;
	}

	/**
	 * Called from {@link com.epic60869.sky2m.clipping.mixin.PresentMixin} on the render thread once per
	 * presented frame with the OpenGL renderer. Kept static and null-tolerant because the mixin can fire during
	 * early startup.
	 */
	public static void onFramePresented(int framebufferWidth, int framebufferHeight) {
		ReplayBufferService svc = service;
		if (svc != null) {
			svc.onFramePresented(framebufferWidth, framebufferHeight);
		}
	}

	/**
	 * Called from {@link com.epic60869.sky2m.clipping.mixin.PresentMixin} on the render thread once per frame with
	 * any other renderer (Vulkan), with the {@code GpuTextureView} about to be presented.
	 */
	public static void onFrameRendered(Object texture, int width, int height) {
		ReplayBufferService svc = service;
		if (svc != null) {
			svc.onFrameRendered(texture, width, height);
		}
	}

	// ------------------------------------------------------------- hotkeys

	private void onClientTick(Minecraft client) {
		ReplayBufferService svc = service;
		if (svc == null) {
			return;
		}
		// Fire in-game AND over most screens (so you can screenshot/clip a menu or chat). The one
		// exception is Sky2M Clipping's own key-capturing screens, where a press is being *bound*, not fired.
		// Use a modifier combo for menu hotkeys so it doesn't collide with typing in chat.
		net.minecraft.client.gui.screens.Screen screen = client.gui.screen();
		if (screen instanceof com.epic60869.sky2m.clipping.ui.ClippingConfigScreen
				|| screen instanceof com.epic60869.sky2m.clipping.ui.AddHotkeyScreen
				|| screen instanceof com.epic60869.sky2m.clipping.ui.AudioSettingsScreen) {
			downLastTick.clear();
			return;
		}

		List<Hotkey> hotkeys = svc.config().hotkeys;
		if (hotkeys == null || hotkeys.isEmpty()) {
			downLastTick.clear();
			return;
		}

		Set<String> downNow = new HashSet<>();
		for (Hotkey hk : hotkeys) {
			if (hk == null || !hk.isBound()) {
				continue;
			}
			if (!comboDown(client, hk)) {
				continue;
			}
			String sig = signature(hk);
			downNow.add(sig);
			if (!downLastTick.contains(sig)) {
				fire(client, svc, hk); // rising edge → treat as one "tap"
			}
		}
		downLastTick.clear();
		downLastTick.addAll(downNow);
	}

	private static boolean comboDown(Minecraft client, Hotkey hk) {
		return Input.keyDown(client, hk.key)
				&& modifiersMatch(client, hk.ctrl, hk.shift, hk.alt);
	}

	private static void fire(Minecraft client, ReplayBufferService svc, Hotkey hk) {
		switch (hk.action) {
			case CLIP -> svc.saveClip(hk.durationSeconds);
			case SCREENSHOT -> com.epic60869.sky2m.clipping.ui.Screenshotter.capture(client, svc.config());
			case OPEN_LAST_CLIP -> openLastClip(client, svc);
			case OPEN_FOLDER -> openClipsFolder(client, svc.config());
			case OPEN_SETTINGS -> client.gui.setScreen(new com.epic60869.sky2m.clipping.ui.ClippingConfigScreen(client.gui.screen()));
		}
	}

	/** Opens the most recent saved clip straight in the editor, or toasts if there are none yet. */
	private static void openLastClip(Minecraft client, ReplayBufferService svc) {
		List<ClipLibrary.Clip> clips = ClipLibrary.list(svc.config().resolveOutputFolder());
		if (clips.isEmpty()) {
			Notifications.error(client, "No clips yet.");
			return;
		}
		client.gui.setScreen(new com.epic60869.sky2m.clipping.ui.ClipEditScreen(
				new com.epic60869.sky2m.clipping.ui.ClipsScreen(client.gui.screen()), clips.get(0).file()));
	}

	/** Stable identity for edge detection: two identical combos share a signature (a harmless dup). */
	private static String signature(Hotkey hk) {
		return hk.action + ":" + hk.key + ":" + (hk.ctrl ? 1 : 0) + (hk.shift ? 1 : 0) + (hk.alt ? 1 : 0);
	}

	/**
	 * Modifiers are checked exactly: requiring Ctrl means a bare key press is ignored, and not
	 * requiring one means the Ctrl+key combo is ignored too, so two hotkeys sharing a key but
	 * differing in modifiers can never both fire.
	 */
	private static boolean modifiersMatch(Minecraft client, boolean ctrl, boolean shift, boolean alt) {
		boolean ctrlDown = Input.keyDown(client, InputConstants.KEY_LCONTROL)
				|| Input.keyDown(client, InputConstants.KEY_RCONTROL);
		boolean shiftDown = Input.keyDown(client, InputConstants.KEY_LSHIFT)
				|| Input.keyDown(client, InputConstants.KEY_RSHIFT);
		boolean altDown = Input.keyDown(client, InputConstants.KEY_LALT)
				|| Input.keyDown(client, InputConstants.KEY_RALT);
		return ctrlDown == ctrl && shiftDown == shift && altDown == alt;
	}

	// ------------------------------------------------------------- hotkey labels (used by the UI)

	/** Localised name of a GLFW key code, or {@code "Unbound"} for {@code -1}/unknown. */
	public static String keyLabel(int glfwKey) {
		if (glfwKey == -1 || glfwKey == InputConstants.UNKNOWN.getValue()) {
			return "Unbound";
		}
		return Input.keyName(glfwKey);
	}

	/** Human label for a whole combo, e.g. {@code "Ctrl+G"} or {@code "Unbound"}. */
	public static String hotkeyLabel(Hotkey hk) {
		if (hk == null || !hk.isBound()) {
			return "Unbound";
		}
		return modifierPrefix(hk.ctrl, hk.shift, hk.alt) + keyLabel(hk.key);
	}

	public static String modifierPrefix(boolean ctrl, boolean shift, boolean alt) {
		StringBuilder sb = new StringBuilder();
		if (ctrl) {
			sb.append("Ctrl+");
		}
		if (shift) {
			sb.append("Shift+");
		}
		if (alt) {
			sb.append("Alt+");
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------- folder

	public static void openClipsFolder(Minecraft client, ClippingConfig config) {
		Path dir = config.resolveOutputFolder();
		try {
			Files.createDirectories(dir);
			com.epic60869.sky2m.clipping.compat.Platform.openPath(dir);
		} catch (IOException e) {
			LOGGER.error("Could not open {}", dir, e);
			Notifications.error(client, "Could not open " + dir);
		}
	}

	// -------------------------------------------------------------- shutdown

	private static void shutdown() {
		ReplayBufferService svc = service;
		service = null;
		if (svc != null) {
			svc.shutdown();
		}
	}

	public static Component clipsFolderText(ClippingConfig config) {
		return Component.literal(config.resolveOutputFolder().toString());
	}
}
