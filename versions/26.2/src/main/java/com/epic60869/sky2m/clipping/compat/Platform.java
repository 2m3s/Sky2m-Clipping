package com.epic60869.sky2m.clipping.compat;

import java.nio.file.Path;

/** Opening files and folders in the system's file manager on Minecraft 26.2. */
public final class Platform {
	private Platform() {
	}

	public static void openPath(Path path) {
		net.minecraft.util.Util.getPlatform().openPath(path);
	}
}
