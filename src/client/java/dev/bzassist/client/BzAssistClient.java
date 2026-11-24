package dev.bzassist.client;

import net.fabricmc.api.ClientModInitializer;

import dev.bzassist.BzAssist;
import dev.bzassist.client.bazaar.BazaarApi;
import dev.bzassist.client.bazaar.BazaarScreenTracker;
import dev.bzassist.client.bazaar.ItemNames;
import dev.bzassist.client.bazaar.OptimizerService;
import dev.bzassist.client.bazaar.ProfitTracker;
import dev.bzassist.client.font.Fonts;

public class BzAssistClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BazaarApi.start();
		ItemNames.ensureLoaded();
		ProfitTracker.register();
		BazaarScreenTracker.register();
		OptimizerService.register();
		OptimizerOverlay.register();
		SignAutofill.register();
		BazaarCommands.register();
		BzAssist.LOGGER.info("BzAssist optimizer ready (font: {}); open the Bazaar for the top 20",
				Fonts.INTER_SEMIBOLD);
	}
}
