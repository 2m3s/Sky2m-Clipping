package com.epic60869.sky2m.clipping;

import com.epic60869.sky2m.clipping.config.ClippingConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The {@code sky2m-clipping} entrypoint Sky2M talks to (Sky2M's {@code Clips} finds it through Fabric and calls it
 * by reflection, so neither mod depends on the other):
 * <ul>
 *   <li>{@code configure(Map)}: Sky2M's Clips settings (seconds, fps, height, audio, mic, volumes, folder). They are
 *       layered over the player's own settings without being saved, and turn recording on.</li>
 *   <li>{@code save(String name)}: saves a clip; the future gives {@code path} and {@code seconds}.</li>
 *   <li>{@code status()}: {@code recording}, {@code encoder} and {@code status}.</li>
 *   <li>{@code shutdown()}: Sky2M's Clips were turned off, so recording stops.</li>
 *   <li>{@code upload(String path)}: the clip's share link, uploading it first if needed.</li>
 *   <li>{@code watch(String url)}: plays a clip link in game.</li>
 * </ul>
 * With Sky2M installed, its Clips toggle decides whether anything is recorded. Without it, the mod runs on its own
 * settings.
 */
public final class Sky2MBridge {
	private static final boolean SKY2M = FabricLoader.getInstance().isModLoaded("sky2m");

	/** Sky2M's last settings, or null while its Clips are off (or before it has sent them). */
	private static volatile Map<String, Object> settings;

	public static boolean sky2mPresent() {
		return SKY2M;
	}

	/** {@code own} with Sky2M's Clips settings layered on; {@code own} itself is never changed. */
	static ClippingConfig overlay(ClippingConfig own) {
		if (!SKY2M) {
			return own;
		}
		ClippingConfig c = own.copy();
		Map<String, Object> s = settings;
		if (s == null) {
			c.enabled = false;
			return c;
		}
		c.enabled = true;
		// The buffer must hold Sky2M's clip length as well as any longer clip hotkey of the player's own.
		c.clipDurationSeconds = Math.max(c.clipDurationSeconds, seconds(s));
		c.fps = clamp(number(s, "fps", 60), ClippingConfig.FPS_MIN, ClippingConfig.FPS_MAX);
		int height = number(s, "height", 1080);
		c.maxHeight = height > 0 ? clamp(height, 128, 4320) : 0;
		c.maxWidth = height > 0 ? clamp(Math.round(height * 16f / 9f), 128, 7680) : 0;
		// The recorder captures everything the PC plays: "Game only" and "Game + Discord" can't be split from it.
		String audio = String.valueOf(s.getOrDefault("audio", "game"));
		c.captureGameAudio = !"none".equals(audio);
		c.pcAudioVolume = clamp(number(s, "desktop".equals(audio) ? "desktopVolume" : "gameVolume", 100), 0, 200);
		c.captureMicrophone = Boolean.TRUE.equals(s.get("mic"));
		c.micVolume = clamp(number(s, "micVolume", 100), 0, 200);
		Object folder = s.get("folder");
		if (folder != null && !folder.toString().isBlank()) {
			c.outputFolder = folder.toString();
		}
		return c;
	}

	private static int seconds(Map<String, Object> s) {
		return clamp(number(s, "seconds", 30), ClippingConfig.DURATION_MIN_SECONDS, ClippingConfig.DURATION_MAX_SECONDS);
	}

	private static int number(Map<String, Object> s, String key, int fallback) {
		return s.get(key) instanceof Number n ? n.intValue() : fallback;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static void refresh() {
		ReplayBufferService svc = Sky2MClipping.service();
		if (svc == null) {
			return;
		}
		try {
			svc.refreshSky2MSettings();
		} catch (RuntimeException e) {
			// Sky2M stops Clips from a shutdown hook too, when the game may already be gone.
			Sky2MClipping.LOGGER.debug("Couldn't apply Sky2M's Clips settings", e);
		}
	}

	public void configure(Map<String, Object> newSettings) {
		settings = new HashMap<>(newSettings);
		refresh();
	}

	public CompletableFuture<Map<String, Object>> save(String name) {
		ReplayBufferService svc = Sky2MClipping.service();
		if (svc == null) {
			return CompletableFuture.failedFuture(new IllegalStateException("Sky2M Clipping hasn't started yet."));
		}
		Map<String, Object> s = settings;
		int length = s != null ? seconds(s) : svc.config().clipDurationSeconds;
		return svc.saveClip(length, name, true).thenApply(result -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("path", result.file().toString());
			out.put("seconds", result.seconds());
			return out;
		});
	}

	public Map<String, Object> status() {
		Map<String, Object> out = new LinkedHashMap<>();
		ReplayBufferService svc = Sky2MClipping.service();
		out.put("recording", svc != null && svc.isBuffering());
		out.put("encoder", svc == null ? "" : svc.encoderName());
		out.put("status", svc == null ? "Starting up" : svc.statusMessage());
		return out;
	}

	public void shutdown() {
		settings = null;
		refresh();
	}

	/** Sky2M's [Upload] on a saved clip: the clip's share link (uploading it if it has none yet). */
	public CompletableFuture<String> upload(String path) {
		ReplayBufferService svc = Sky2MClipping.service();
		if (svc == null) {
			return CompletableFuture.failedFuture(new IllegalStateException("Sky2M Clipping hasn't started yet."));
		}
		return svc.uploadLink(java.nio.file.Path.of(path));
	}

	/** Sky2M's [Watch] on a clip link in its chat: plays it in game. False if it isn't a video link. */
	public boolean watch(String url) {
		return Sky2MClipping.watch(url);
	}
}
