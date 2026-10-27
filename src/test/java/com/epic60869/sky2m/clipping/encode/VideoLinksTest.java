package com.epic60869.sky2m.clipping.encode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoLinksTest {

	@Test
	void findsShareHostLinksInChat() {
		assertEquals(List.of("https://x0.at/Ab3x.mp4"), VideoLinks.find("[MVP+] Bob: look https://x0.at/Ab3x.mp4"));
		assertEquals(List.of("https://litter.catbox.moe/q1w2e3.mp4"), VideoLinks.find("clip: https://litter.catbox.moe/q1w2e3.mp4!"));
		assertEquals(List.of("https://tmpfiles.org/dl/123/clip.mp4"), VideoLinks.find("(https://tmpfiles.org/dl/123/clip.mp4)"));
	}

	@Test
	void findsAnyVideoFileLinkOnce() {
		assertEquals(List.of("http://example.com/a/b.webm?x=1", "https://cdn.example.com/c.MOV"),
				VideoLinks.find("http://example.com/a/b.webm?x=1 and https://cdn.example.com/c.MOV and http://example.com/a/b.webm?x=1"));
	}

	@Test
	void ignoresOtherLinks() {
		assertTrue(VideoLinks.find("https://i.ibb.co/abc/shot.png https://youtube.com/watch?v=1 https://x.com/mp4").isEmpty());
		assertTrue(VideoLinks.find(null).isEmpty());
	}

	@Test
	void wholeLinkCheck() {
		assertTrue(VideoLinks.isVideoLink("https://x0.at/Ab3x.mp4"));
		assertFalse(VideoLinks.isVideoLink("https://x0.at/Ab3x.mp4 extra"));
		assertFalse(VideoLinks.isVideoLink("file:///C:/clip.mp4"));
		assertFalse(VideoLinks.isVideoLink("https://example.com/page"));
	}
}
