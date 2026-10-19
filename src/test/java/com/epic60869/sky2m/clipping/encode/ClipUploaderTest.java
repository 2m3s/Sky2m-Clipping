package com.epic60869.sky2m.clipping.encode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the real multipart upload path against the live host. Opt-in (it hits the network and
 * creates a public file), enabled with {@code -Dsky2m-clipping.testUpload=1}. Uploads to the temporary
 * host so the artifact auto-expires.
 */
class ClipUploaderTest {

	@Test
	void uploadsAndReturnsALink(@TempDir Path tempDir) throws Exception {
		assumeTrue("1".equals(System.getProperty("sky2m-clipping.testUpload")),
				"Opt-in: set -Dsky2m-clipping.testUpload=1 to run the live upload test");

		Path file = tempDir.resolve("sky2m-clipping-uploadtest.txt");
		Files.writeString(file, "Sky2M Clipping upload test — safe to ignore. " + System.nanoTime());

		String url = ClipUploader.upload(file, ClipUploader.Service.LITTERBOX, 1);
		System.out.println("Uploaded to: " + url);

		assertTrue(url.startsWith("https://"), "should return an https URL, got: " + url);
		assertTrue(url.contains("catbox") || url.contains("litter"), "unexpected host: " + url);
	}
}
