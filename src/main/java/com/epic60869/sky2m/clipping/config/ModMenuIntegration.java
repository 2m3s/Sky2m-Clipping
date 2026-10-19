package com.epic60869.sky2m.clipping.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.epic60869.sky2m.clipping.ui.ClippingConfigScreen;

/**
 * Registers the config screen with Mod Menu. Declared in {@code fabric.mod.json} under the
 * {@code modmenu} entrypoint; Mod Menu is a soft dependency, so this class is only loaded when Mod
 * Menu is present.
 */
public final class ModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ClippingConfigScreen::new;
	}
}
