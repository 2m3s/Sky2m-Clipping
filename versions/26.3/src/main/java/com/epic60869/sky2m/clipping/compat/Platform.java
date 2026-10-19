package com.epic60869.sky2m.clipping.compat;

import java.nio.file.Path;

/** Opening files and folders in the system's file manager on Minecraft 26.3. */
public final class Platform {
	private Platform() {
	}

	public static void openPath(Path path) {
		com.mojang.blaze3d.Blaze3D.openPath(path);
	}
}
