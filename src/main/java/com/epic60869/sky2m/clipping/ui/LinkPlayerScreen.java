package com.epic60869.sky2m.clipping.ui;

import com.epic60869.sky2m.clipping.ReplayBufferService;
import com.epic60869.sky2m.clipping.Sky2MClipping;
import com.epic60869.sky2m.clipping.encode.ClipLibrary;
import com.epic60869.sky2m.clipping.encode.VideoLinks;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Watches a clip link from chat in game: downloads the video to a temporary file, then plays it with sound using
 * the clip editor's {@link VideoPlayer}. Click the picture or press Space to pause, drag the bar to seek, Esc to
 * close. The download is deleted when the screen closes.
 */
public final class LinkPlayerScreen extends Screen {

	private static final int TEXT = 0xFFE7E8EC;
	private static final int MUTED = 0xFF9AA0AA;
	private static final int FAINT = 0xFF5A5F6A;
	private static final int ICON_TEX = 48;
	private static final int BAR_H = 52;

	private final Screen parent;
	private final String url;

	private volatile String status = "Downloading…";
	private volatile long downloaded;
	private volatile boolean closed;
	private volatile Path file;
	private VideoPlayer player;
	private double duration;
	private boolean seeking;
	/** Whether it was playing when a seek drag began, so letting go carries on (or stays paused). */
	private boolean playAfterSeek;
	private boolean started;

	public LinkPlayerScreen(Screen parent, String url) {
		super(Component.literal("Clip"));
		this.parent = parent;
		this.url = url;
	}

	private static Path ffmpeg() {
		ReplayBufferService svc = Sky2MClipping.service();
		return svc != null ? svc.ffmpegProvider().executable() : null;
	}

	@Override
	protected void init() {
		// init() runs again on every resize: download only once.
		if (!started) {
			started = true;
			startDownload();
		}
		int w = 90;
		addRenderableWidget(Button.builder(Component.literal("Copy Link"), b -> {
			this.minecraft.keyboardHandler.setClipboard(url);
			b.setMessage(Component.literal("Copied"));
		}).bounds(this.width - 2 * w - 30, 6, w, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
				.bounds(this.width - w - 20, 6, w, 20).build());
	}

	private void startDownload() {
		Path ff = ffmpeg();
		if (ff == null) {
			status = "FFmpeg isn't ready yet: try again in a moment.";
			return;
		}
		Thread t = new Thread(() -> {
			Path tmp = null;
			try {
				tmp = Files.createTempFile("sky2m-watch-", ".mp4");
				VideoLinks.download(url, tmp, n -> downloaded = n, () -> closed);
				status = "Loading…";
				double d = ClipLibrary.durationSeconds(ff, tmp);
				if (d <= 0) {
					throw new IOException("That file isn't a video Minecraft can play.");
				}
				Path ready = tmp;
				tmp = null;
				this.minecraft.execute(() -> {
					if (closed) {
						delete(ready);
						return;
					}
					file = ready;
					duration = d;
					player = new VideoPlayer(this.minecraft, ff, ready, d);
					player.play();
					status = "";
				});
			} catch (IOException e) {
				if (!closed) {
					status = e.getMessage() == null ? "Couldn't load the clip." : e.getMessage();
				}
			} catch (RuntimeException e) {
				Sky2MClipping.LOGGER.warn("Couldn't play {}", url, e);
				status = "Couldn't load the clip.";
			} finally {
				if (tmp != null) {
					delete(tmp);
				}
			}
		}, "Sky2M Clipping-watch");
		t.setDaemon(true);
		t.start();
	}

	// ------------------------------------------------------------------ input

	private int barY() {
		return this.height - BAR_H + 14;
	}

	private int barLeft() {
		return 24;
	}

	private int barW() {
		return this.width - 48;
	}

	private static boolean in(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx <= x + w && my >= y && my <= y + h;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != 0 || player == null) {
			return false;
		}
		double mx = event.x();
		double my = event.y();
		if (my >= barY() - 6 && my <= barY() + 8) {
			seeking = true;
			playAfterSeek = player.isPlaying();
			seekTo(mx);
			return true;
		}
		if (in(mx, my, 24, this.height - 33, 20, 20) || (my > 30 && my < this.height - BAR_H)) {
			player.togglePlay();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (seeking && event.button() == 0) {
			seekTo(event.x());
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (seeking && event.button() == 0) {
			seeking = false;
			if (player != null && playAfterSeek) {
				player.play();
			}
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_SPACE && player != null) {
			player.togglePlay();
			return true;
		}
		return super.keyPressed(event);
	}

	private void seekTo(double mx) {
		if (player != null && duration > 0) {
			player.seek(Math.max(0, Math.min(1, (mx - barLeft()) / barW())) * duration);
		}
	}

	// ------------------------------------------------------------------ render

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (player != null) {
			player.tick();
		}
		// Vanilla background and the top buttons first; the video area below them is black.
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		int areaTop = 30;
		graphics.fill(0, areaTop, this.width, this.height, 0xFF000000);
		int areaH = this.height - BAR_H - areaTop;
		if (player != null && player.hasFrame()) {
			int tw = player.texWidth();
			int th = player.texHeight();
			float sc = Math.min((float) this.width / tw, (float) areaH / th);
			int dw = Math.max(1, Math.round(tw * sc));
			int dh = Math.max(1, Math.round(th * sc));
			player.setDisplayWidth((int) Math.ceil(dw * (double) this.minecraft.getWindow().getGuiScale()));
			graphics.blit(RenderPipelines.GUI_TEXTURED, player.textureId(), this.width / 2 - dw / 2,
					areaTop + areaH / 2 - dh / 2, 0f, 0f, dw, dh, tw, th, tw, th);
		} else {
			String text = status;
			if (text.startsWith("Downloading")) {
				text = String.format(Locale.ROOT, "Downloading… %.1f MB", downloaded / 1e6);
			}
			graphics.centeredText(this.font, Component.literal(text), this.width / 2, areaTop + areaH / 2 - 4,
					text.startsWith("Downloading") || text.startsWith("Loading") ? MUTED : 0xFFFF6B6B);
		}
		graphics.text(this.font, Component.literal(shortUrl()), 12, 12, MUTED, true);

		graphics.fill(0, this.height - BAR_H, this.width, this.height, 0x99000000);
		if (player != null) {
			int sy = barY();
			graphics.fill(barLeft(), sy, barLeft() + barW(), sy + 3, 0xFF3A3D46);
			int px = barLeft() + (int) Math.round(Math.max(0, Math.min(1, player.time() / duration)) * barW());
			graphics.fill(barLeft(), sy, px, sy + 3, 0xFFD0D4DC);
			graphics.fill(px - 2, sy - 3, px + 3, sy + 6, 0xFFFFFFFF);
			boolean hover = in(mouseX, mouseY, 24, this.height - 33, 20, 20);
			icon(graphics, player.isPlaying() ? "pause" : "play", 24, this.height - 33, 20, hover ? 0xFFFFFFFF : MUTED);
			graphics.text(this.font, Component.literal(clock(player.time()) + " / " + clock(duration)),
					50, this.height - 27, MUTED, true);
			graphics.text(this.font, Component.literal("Space: pause · drag the bar to seek · Esc: close"),
					this.width - 24 - this.font.width("Space: pause · drag the bar to seek · Esc: close"),
					this.height - 27, FAINT, true);
		}
	}

	private String shortUrl() {
		String s = url.replaceFirst("^https?://", "");
		return s.length() <= 60 ? s : s.substring(0, 57) + "…";
	}

	private void icon(GuiGraphicsExtractor graphics, String name, int x, int y, int size, int color) {
		Identifier id = Identifier.fromNamespaceAndPath("sky2m-clipping", "textures/gui/icons/" + name + ".png");
		graphics.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, size, size, ICON_TEX, ICON_TEX, ICON_TEX, ICON_TEX, color);
	}

	private static String clock(double seconds) {
		int s = (int) Math.floor(Math.max(0, seconds));
		return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}

	// ------------------------------------------------------------------ closing

	private static void delete(Path path) {
		// FFmpeg may still hold the file for a moment on Windows.
		Thread t = new Thread(() -> ClipLibrary.deleteWithRetry(path), "Sky2M Clipping-watch-cleanup");
		t.setDaemon(true);
		t.start();
	}

	@Override
	public void removed() {
		closed = true;
		if (player != null) {
			player.close();
			player = null;
		}
		if (file != null) {
			delete(file);
			file = null;
		}
		super.removed();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
