package com.epic60869.sky2m.clipping.encode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clip links in chat: any http(s) link to a video file (what the share hosts give out, e.g. {@code x0.at/Ab3.mp4},
 * {@code litter.catbox.moe/xyz.mp4}, {@code tmpfiles.org/dl/123/clip.mp4}), and downloading one to watch it in game.
 * No Minecraft reference, so it is unit tested.
 */
public final class VideoLinks {

	/** Bigger than any clip the share hosts take (500 MB), so a link can't fill the disk. */
	public static final long MAX_BYTES = 500L * 1024 * 1024;

	private static final Pattern LINK = Pattern.compile(
			"https?://[^\\s\"'<>]+?\\.(?:mp4|webm|mov|mkv|m4v)(?:\\?[^\\s\"'<>]*)?(?=$|[\\s\"'<>)\\],.!?])",
			Pattern.CASE_INSENSITIVE);

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	private VideoLinks() {
	}

	/** The video links in {@code text}, in order, without repeats. */
	public static List<String> find(String text) {
		List<String> out = new ArrayList<>();
		if (text == null) {
			return out;
		}
		Matcher m = LINK.matcher(text);
		while (m.find()) {
			if (!out.contains(m.group())) {
				out.add(m.group());
			}
		}
		return out;
	}

	/** Whether {@code url} is a whole video link (and nothing else). */
	public static boolean isVideoLink(String url) {
		return url != null && LINK.matcher(url).matches();
	}

	/**
	 * Downloads {@code url} to {@code dest}, reporting bytes so far to {@code progress}. Blocking; stops early if
	 * {@code cancelled} turns true. Fails with a message fit for the player.
	 */
	public static void download(String url, Path dest, LongConsumer progress, java.util.function.BooleanSupplier cancelled)
			throws IOException {
		if (!isVideoLink(url)) {
			throw new IOException("That isn't a video link.");
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMinutes(5))
				.header("User-Agent", "Mozilla/5.0 (compatible; Sky2M Clipping/1.0)")
				.GET().build();
		HttpResponse<InputStream> response;
		try {
			response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Cancelled.");
		}
		try (InputStream in = response.body()) {
			int code = response.statusCode();
			if (code == 404 || code == 410) {
				throw new IOException("The clip is gone (the link has expired).");
			}
			if (code != 200) {
				throw new IOException("The host answered HTTP " + code + ".");
			}
			String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
			if (type.startsWith("text/html")) {
				throw new IOException("That link is a web page, not a video file.");
			}
			long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
			if (length > MAX_BYTES) {
				throw new IOException("The clip is too big to watch here (over 500 MB).");
			}
			long total = 0;
			try (OutputStream out = Files.newOutputStream(dest)) {
				byte[] buf = new byte[64 * 1024];
				int n;
				while ((n = in.read(buf)) > 0) {
					if (cancelled.getAsBoolean()) {
						throw new IOException("Cancelled.");
					}
					total += n;
					if (total > MAX_BYTES) {
						throw new IOException("The clip is too big to watch here (over 500 MB).");
					}
					out.write(buf, 0, n);
					progress.accept(total);
				}
			}
			if (total == 0) {
				throw new IOException("The clip is empty.");
			}
		}
	}
}
