package com.epic60869.sky2m.clipping.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClippingConfigTest {

	@Test
	void emptyHotkeysSeedDefaults() {
		// A fresh / pre-2.0.0 config (no hotkeys) is migrated to sensible defaults.
		ClippingConfig c = new ClippingConfig();
		c.validated();
		assertFalse(c.hotkeys.isEmpty(), "migration seeds default hotkeys");
		assertTrue(c.hotkeys.stream().anyMatch(h -> h.action == ClippingConfig.HotkeyAction.CLIP),
				"there is a clip hotkey");
	}

	@Test
	void bufferMatchesLongestClipHotkey() {
		// The ring must cover the longest clip binding so every shorter one can be served.
		ClippingConfig c = new ClippingConfig();
		c.hotkeys.add(new ClippingConfig.Hotkey(ClippingConfig.HotkeyAction.CLIP, 71, true, false, false, 20));
		c.hotkeys.add(new ClippingConfig.Hotkey(ClippingConfig.HotkeyAction.CLIP, 72, true, false, false, 120));
		c.validated();
		assertEquals(120, c.clipDurationSeconds, "buffer sized to the longest clip hotkey");
	}

	@Test
	void perHotkeyDurationIsClamped() {
		ClippingConfig c = new ClippingConfig();
		c.hotkeys.add(new ClippingConfig.Hotkey(ClippingConfig.HotkeyAction.CLIP, 71, false, false, false, 99_999));
		c.validated();
		assertEquals(ClippingConfig.DURATION_MAX_SECONDS, c.hotkeys.get(0).durationSeconds,
				"each hotkey's clip length is clamped");
	}

	@Test
	void customDurationIsKept() {
		// 47s is not a preset, but custom values are honoured now (no snapping to a fixed list).
		ClippingConfig c = new ClippingConfig();
		c.clipDurationSeconds = 47;
		c.validated();
		assertEquals(47, c.clipDurationSeconds);
	}

	@Test
	void durationClampedToRange() {
		// The buffer length is derived from the seeded clip hotkey, so use a fresh config per direction.
		ClippingConfig low = new ClippingConfig();
		low.clipDurationSeconds = 1;
		low.validated();
		assertEquals(ClippingConfig.DURATION_MIN_SECONDS, low.clipDurationSeconds, "below-min clamps up");

		ClippingConfig high = new ClippingConfig();
		high.clipDurationSeconds = 99_999;
		high.validated();
		assertEquals(ClippingConfig.DURATION_MAX_SECONDS, high.clipDurationSeconds, "above-max clamps down");
	}

	@Test
	void validDurationIsKept() {
		ClippingConfig c = new ClippingConfig();
		c.clipDurationSeconds = 120;
		c.validated();
		assertEquals(120, c.clipDurationSeconds);
	}

	@Test
	void customFpsIsKept() {
		// 144 FPS used to snap back to 60; custom frame rates are allowed now.
		ClippingConfig c = new ClippingConfig();
		c.fps = 144;
		c.validated();
		assertEquals(144, c.fps);
	}

	@Test
	void fpsClampedToRange() {
		ClippingConfig c = new ClippingConfig();
		c.fps = 1;
		c.validated();
		assertEquals(ClippingConfig.FPS_MIN, c.fps, "below-min clamps up");

		c.fps = 10_000;
		c.validated();
		assertEquals(ClippingConfig.FPS_MAX, c.fps, "above-max clamps down");
	}

	@Test
	void bitrateIsClamped() {
		ClippingConfig c = new ClippingConfig();
		c.videoBitrateKbps = 5;
		c.validated();
		assertTrue(c.videoBitrateKbps >= 1000, "bitrate floor");

		c.videoBitrateKbps = 999_999;
		c.validated();
		assertTrue(c.videoBitrateKbps <= 200_000, "bitrate ceiling");
	}

	@Test
	void blankFoldersFallBackToDefaults() {
		ClippingConfig c = new ClippingConfig();
		c.outputFolder = "   ";
		c.tempFolder = "";
		c.validated();
		assertEquals("sky2m-clipping/clips", c.outputFolder);
		assertEquals("sky2m-clipping/temp", c.tempFolder);
	}

	@Test
	void ringAlwaysCoversRequestedDurationWithSpare() {
		ClippingConfig c = new ClippingConfig();
		c.clipDurationSeconds = 30;
		c.segmentSeconds = 1;
		c.validated();
		// 30s of 1s segments, plus spares for the in-progress and recycling segments.
		assertTrue(c.ringSegmentCount() * c.segmentSeconds >= 30 + 1,
				"ring must exceed the requested window");
		assertEquals(32, c.ringSegmentCount());
	}

	@Test
	void ringMathHandlesMultiSecondSegments() {
		ClippingConfig c = new ClippingConfig();
		c.clipDurationSeconds = 120;
		c.segmentSeconds = 2;
		c.validated();
		assertEquals(62, c.ringSegmentCount()); // ceil(120/2) + 2
		assertTrue(c.ringSegmentCount() * c.segmentSeconds > 120);
	}

	@Test
	void copyIsIndependentOfTheOriginal() {
		ClippingConfig c = new ClippingConfig();
		c.clipDurationSeconds = 60;
		ClippingConfig copy = c.copy();
		copy.clipDurationSeconds = 15;
		assertEquals(60, c.clipDurationSeconds, "editing the copy must not touch the original");
		assertEquals(15, copy.clipDurationSeconds);
	}
}
