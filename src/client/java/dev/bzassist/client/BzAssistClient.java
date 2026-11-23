package dev.bzassist.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import dev.bzassist.BzAssist;
import dev.bzassist.client.bazaar.BazaarApi;
import dev.bzassist.client.bazaar.BazaarScreenReader;
import dev.bzassist.client.bazaar.ChatWatch;
import dev.bzassist.client.bazaar.FlipSession;
import dev.bzassist.client.bazaar.GuideEngine;
import dev.bzassist.client.bazaar.ItemNames;
import dev.bzassist.client.font.Fonts;

import net.minecraft.client.KeyMapping;

public class BzAssistClient implements ClientModInitializer {
	/** Starts and stops the flipping session. Unbound by default: set it in Options > Controls. */
	private static KeyMapping startKey;

	@Override
	public void onInitializeClient() {
		startKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.bzassist.start", InputConstants.KEY_F6, KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (startKey.consumeClick()) {
				FlipSession.toggle();
			}
		});
		// Nothing is drawn by default; use FontRenderer#draw from a HudElement or Screen.
		BzAssist.LOGGER.info("Text renderer ready (font: {}, use FontRenderer#draw)", Fonts.INTER_SEMIBOLD);
		BazaarApi.start();
		ChatWatch.register();
		ItemNames.ensureLoaded();
		BazaarScreenReader.register();
		GuideEngine.register();
		AssistOverlay.register();
		InputAssist.register();
		BazaarCommands.register();
		BzAssist.LOGGER.info("Bazaar assistant ready: /bzassist best");
	}
}
