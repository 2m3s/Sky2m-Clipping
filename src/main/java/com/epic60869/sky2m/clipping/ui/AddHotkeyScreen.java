package com.epic60869.sky2m.clipping.ui;

import com.epic60869.sky2m.clipping.config.ClippingConfig;
import com.epic60869.sky2m.clipping.config.ClippingConfig.Hotkey;
import com.epic60869.sky2m.clipping.config.ClippingConfig.HotkeyAction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Small picker opened by the Hotkeys tab's "Add Hotkey" button: choose which action the new hotkey
 * triggers. The chosen action is appended to the shared working config as an unbound hotkey (the
 * user then binds a key for it back on the Hotkeys tab).
 */
public final class AddHotkeyScreen extends Screen {

	private static final int W = 220;

	private final ClippingConfigScreen parent;
	private final ClippingConfig working;

	public AddHotkeyScreen(ClippingConfigScreen parent, ClippingConfig working) {
		super(Component.translatable("sky2m-clipping.config.add_hotkey"));
		this.parent = parent;
		this.working = working;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - W / 2;
		int y = this.height / 2 - 72;
		addAction(x, y, HotkeyAction.CLIP, "sky2m-clipping.hotkey.clip");
		addAction(x, y + 24, HotkeyAction.OPEN_LAST_CLIP, "sky2m-clipping.hotkey.open_last");
		addAction(x, y + 48, HotkeyAction.SCREENSHOT, "sky2m-clipping.hotkey.screenshot");
		addAction(x, y + 72, HotkeyAction.OPEN_FOLDER, "sky2m-clipping.hotkey.open_folder");
		addAction(x, y + 96, HotkeyAction.OPEN_SETTINGS, "sky2m-clipping.hotkey.open_settings");
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(x, y + 128, W, 20).build());
	}

	private void addAction(int x, int y, HotkeyAction action, String labelKey) {
		addRenderableWidget(Button.builder(Component.translatable(labelKey), b -> {
			working.hotkeys.add(new Hotkey(action, -1, false, false, false, 30));
			onClose();
		}).bounds(x, y, W, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 90, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}
}
