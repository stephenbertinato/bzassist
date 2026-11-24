package dev.bzassist.client.bazaar;

import java.util.Locale;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Knows whether the player is looking at the Bazaar.
 *
 * <p>The root and category screens say "Bazaar"; a product screen is titled "Farming ➜ Wheat" and
 * keeps the panel alive through the {@code inBazaar} flow flag. Any other container, chat, inventory
 * or the game HUD turns it off and stops the feed.</p>
 */
public final class BazaarScreenTracker {
	private static boolean open;
	private static boolean inBazaar;
	private static Screen scannedScreen;
	private static boolean scannedLooks;
	private static int tickCounter;

	private BazaarScreenTracker() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	public static boolean isOpen() {
		return open;
	}

	private static void tick() {
		tickCounter++;

		Screen screen = Minecraft.getInstance().gui.screen();
		boolean nextOpen = false;

		if (screen == null) {
			inBazaar = false;
		} else if (screen instanceof InventoryScreen) {
			inBazaar = false;
		} else if (screen instanceof AbstractContainerScreen<?> container) {
			String title = ItemNames.strip(container.getTitle().getString()).toLowerCase(Locale.ROOT);

			if (title.contains("bazaar")) {
				inBazaar = true;
				nextOpen = true;
			} else if (inBazaar && hasArrow(title)) {
				nextOpen = true;
			} else if (!inBazaar && hasArrow(title) && looksLikeBazaarCached(container)) {
				// A direct /bz <item> opens the product page without ever showing the root title.
				inBazaar = true;
				nextOpen = true;
			} else {
				inBazaar = false;
			}
		}
		// A sign or chat screen is not a container: keep inBazaar so closing it returns to the panel.

		setOpen(nextOpen);

		// The optimizer only needs purse/inventory while its panel can be visible. Keeping the
		// scoreboard and inventory scans off the normal HUD path avoids idle client-tick work.
		if (nextOpen) {
			PlayerState.tick();
		}
	}

	private static boolean hasArrow(String title) {
		return title.contains("➜") || title.contains("→") || title.contains("->");
	}

	/**
	 * The slot scan is only needed for a direct product page. Cache a negative result per screen and
	 * refresh it once a second, rather than walking every slot on every client tick.
	 */
	private static boolean looksLikeBazaarCached(AbstractContainerScreen<?> container) {
		if (container != scannedScreen || tickCounter % 20 == 0) {
			scannedScreen = container;
			scannedLooks = looksLikeBazaar(container);
		}

		return scannedLooks;
	}

	/** A product page carries at least one of the bazaar's own action buttons. */
	private static boolean looksLikeBazaar(AbstractContainerScreen<?> container) {
		for (Slot slot : container.getMenu().slots) {
			if (slot.getItem().isEmpty()) {
				continue;
			}

			String name = ItemNames.strip(slot.getItem().getHoverName().getString()).toLowerCase(Locale.ROOT);

			if (name.contains("instant buy") || name.contains("buy instantly")
					|| name.contains("sell instantly") || name.contains("create buy order")
					|| name.contains("create sell offer") || name.contains("bazaar")) {
				return true;
			}
		}

		return false;
	}

	private static void setOpen(boolean value) {
		if (value == open) {
			return;
		}

		open = value;
		BazaarApi.setActive(value);

		if (value) {
			PlayerState.refresh();
			OptimizerService.requestRefresh();
		}
	}
}
