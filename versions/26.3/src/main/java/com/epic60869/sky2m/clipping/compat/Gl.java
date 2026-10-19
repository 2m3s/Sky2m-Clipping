package com.epic60869.sky2m.clipping.compat;

import com.mojang.renderpearl.backend.opengl.GlStateManager;

/**
 * Minecraft 26.3's OpenGL state cache (com.mojang.renderpearl.backend.opengl.GlStateManager). The frame grabber goes through it, not raw GL,
 * so Minecraft's cached GL state stays right; only the package differs between versions.
 */
public final class Gl {
	private Gl() {
	}

	public static void _disableScissorTest() {
		GlStateManager._disableScissorTest();
	}

	public static int _glGenBuffers() {
		return GlStateManager._glGenBuffers();
	}

	public static void _glBindBuffer(int a, int b) {
		GlStateManager._glBindBuffer(a, b);
	}

	public static void _glBufferData(int a, long b, int c) {
		GlStateManager._glBufferData(a, b, c);
	}

	public static java.nio.ByteBuffer _glMapBufferRange(int a, long b, long c, int d) {
		return GlStateManager._glMapBufferRange(a, b, c, d);
	}

	public static void _glUnmapBuffer(int a) {
		GlStateManager._glUnmapBuffer(a);
	}

	public static void _glDeleteBuffers(int a) {
		GlStateManager._glDeleteBuffers(a);
	}

	public static void _glBindFramebuffer(int a, int b) {
		GlStateManager._glBindFramebuffer(a, b);
	}

	public static int getFrameBuffer(int a) {
		return GlStateManager.getFrameBuffer(a);
	}

	public static void _glBlitFrameBuffer(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j) {
		GlStateManager._glBlitFrameBuffer(a, b, c, d, e, f, g, h, i, j);
	}

	public static void _glDeleteFramebuffers(int a) {
		GlStateManager._glDeleteFramebuffers(a);
	}

	public static int glGenFramebuffers() {
		return GlStateManager.glGenFramebuffers();
	}

	public static void _pixelStore(int a, int b) {
		GlStateManager._pixelStore(a, b);
	}

	public static void _readPixels(int a, int b, int c, int d, int e, int f, long g) {
		GlStateManager._readPixels(a, b, c, d, e, f, g);
	}

	public static int _getError() {
		return GlStateManager._getError();
	}

	public static void clearGlErrors() {
		GlStateManager.clearGlErrors();
	}

	public static long _glFenceSync(int a, int b) {
		return GlStateManager._glFenceSync(a, b);
	}

	public static int _glClientWaitSync(long a, int b, long c) {
		return GlStateManager._glClientWaitSync(a, b, c);
	}

	public static void _glDeleteSync(long a) {
		GlStateManager._glDeleteSync(a);
	}
}
