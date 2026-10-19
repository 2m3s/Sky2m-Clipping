package com.epic60869.sky2m.clipping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared SLF4J logger, kept in its own class free of any Minecraft reference.
 *
 * <p>This lets the {@code capture} and {@code encode} packages log without dragging in
 * {@link Sky2MClipping} — whose static initialiser touches Minecraft's key-binding registry — so the
 * FFmpeg pipeline can be exercised by plain JUnit tests on a headless JVM.
 */
public final class ClippingLog {

	public static final Logger LOGGER = LoggerFactory.getLogger("Sky2M Clipping");

	private ClippingLog() {
	}
}
