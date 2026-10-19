package com.epic60869.sky2m.clipping.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;

/** Keyboard input on Minecraft 26.3. */
public final class Input {
	private Input() {
	}

	public static boolean keyDown(Minecraft mc, int key) {
		return InputConstants.isKeyDown(key);
	}

	public static String keyName(int key) {
		return InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString();
	}
}
