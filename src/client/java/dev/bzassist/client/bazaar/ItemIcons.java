package dev.bzassist.client.bazaar;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Icons for bazaar products, taken from the game itself: every time the bazaar shows a product, the
 * item stack on that slot is remembered, so the summary can draw the real icon rather than a guess.
 */
public final class ItemIcons {
	private static final Map<String, ItemStack> KNOWN = new HashMap<>(256);

	private ItemIcons() {
	}

	/** Remembers the stack a bazaar slot used for this product. */
	public static void remember(String id, ItemStack stack) {
		if (id == null || stack == null || stack.isEmpty()) {
			return;
		}

		KNOWN.putIfAbsent(id, stack.copyWithCount(1));
	}

	/** The icon for a product: the one the bazaar showed, or a best guess from its id. */
	public static ItemStack icon(String id) {
		if (id == null) {
			return new ItemStack(Items.PAPER);
		}

		ItemStack known = KNOWN.get(id);

		if (known != null) {
			return known;
		}

		if (id.startsWith("ENCHANTMENT_")) {
			return new ItemStack(Items.ENCHANTED_BOOK);
		}

		try {
			Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id.toLowerCase(Locale.ROOT)))
					.orElse(Items.AIR);

			if (item != Items.AIR) {
				return new ItemStack(item);
			}
		} catch (RuntimeException ignored) {
			// Not a registry name; fall through to the neutral icon.
		}

		return new ItemStack(Items.PAPER);
	}
}
