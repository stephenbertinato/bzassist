package dev.bzassist.client;

import java.util.List;
import java.util.Locale;

import dev.bzassist.BzAssist;
import dev.bzassist.client.bazaar.Flip;
import dev.bzassist.client.bazaar.ItemNames;
import dev.bzassist.client.bazaar.OptimizerService;
import dev.bzassist.client.mixin.AbstractContainerScreenAccessor;
import dev.bzassist.client.mixin.AbstractSignEditScreenAccessor;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Pre-fills the Bazaar's custom amount and custom price signs with the recommendation for the
 * product and side the player opened.
 *
 * <p>This is input assistance, not automation: it only writes line 0 of the sign editor that the
 * bazaar already opened. The player still clicks the button, reviews the value and presses Done, and
 * nothing is sent until the screen closes.</p>
 */
public final class SignAutofill {
	/** A server round trip opens the sign; allow a few seconds for a lag spike before giving up. */
	private static final int PENDING_TICKS = 100;
	private static final String[] NO_ARROWS = { "->", "→", "➜" };

	private enum Kind {
		QUANTITY,
		BUY_PRICE,
		SELL_PRICE
	}

	private static String pendingProductId;
	private static Kind pendingKind;
	private static int pendingTicks;
	private static String lastProductId;

	private SignAutofill() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof AbstractContainerScreen<?> container) {
				onContainer(container);
			} else if (screen instanceof AbstractSignEditScreen sign) {
				fill(sign);
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	private static void tick() {
		if (pendingTicks > 0 && --pendingTicks == 0) {
			clearPending();
		}
	}

	private static void onContainer(AbstractContainerScreen<?> container) {
		String title = ItemNames.strip(container.getTitle().getString());

		if (isProduct(title)) {
			// Remember the product page this quantity/price child belongs to. Clear it when the page
			// cannot be matched, so a later sign can never inherit a different product.
			lastProductId = findProductId(container);
		} else if (isQuantity(title) || isPrice(title)) {
			String productId = findProductId(container);

			if (productId != null) {
				lastProductId = productId;
			}

			ScreenMouseEvents.beforeMouseClick(container).register(
					(screen, event) -> onMouseClick(container, title, event));
		}
	}

	private static void onMouseClick(AbstractContainerScreen<?> container, String title, MouseButtonEvent event) {
		Slot slot = slotAt(container, event.x(), event.y());

		if (slot == null || slot.getItem().isEmpty()) {
			return;
		}

		String label = ItemNames.strip(slot.getItem().getHoverName().getString()).trim().toLowerCase(Locale.ROOT);
		Kind kind;

		if (label.equals("custom amount")) {
			kind = Kind.QUANTITY;
		} else if (label.equals("custom price")) {
			kind = priceKind(title);
		} else {
			return;
		}

		if (kind == null) {
			return;
		}

		String productId = findProductId(container);

		if (productId == null) {
			productId = lastProductId;
		}

		if (productId == null || findFlip(productId) == null) {
			return;
		}

		pendingProductId = productId;
		pendingKind = kind;
		pendingTicks = PENDING_TICKS;
	}

	private static void fill(AbstractSignEditScreen sign) {
		if (pendingTicks <= 0 || pendingProductId == null || pendingKind == null) {
			return;
		}

		String productId = pendingProductId;
		Flip flip = findFlip(productId);
		Kind kind = pendingKind;
		clearPending();

		if (flip == null) {
			return;
		}

		String value = switch (kind) {
			case QUANTITY -> Integer.toString(Math.max(1, flip.quantity()));
			case BUY_PRICE -> trimPrice(flip.buyPrice());
			case SELL_PRICE -> trimPrice(flip.sellPrice());
		};

		String[] lines = ((AbstractSignEditScreenAccessor) (Object) sign).bzassist$messages();

		if (lines == null || lines.length == 0 || value.isEmpty()) {
			return;
		}

		lines[0] = value;
		BzAssist.LOGGER.debug("Sign pre-filled with {} for {}", value, productId);
	}

	private static Slot slotAt(AbstractContainerScreen<?> container, double mouseX, double mouseY) {
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) (Object) container;

		for (Slot slot : container.getMenu().slots) {
			int x = accessor.bzassist$leftPos() + slot.x;
			int y = accessor.bzassist$topPos() + slot.y;

			if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
				return slot;
			}
		}

		return null;
	}

	private static Flip findFlip(String productId) {
		if (productId == null) {
			return null;
		}

		for (Flip flip : OptimizerService.top()) {
			if (flip.id().equals(productId)) {
				return flip;
			}
		}

		return null;
	}

	/**
	 * The product shown by a quantity/price page. Its item stack normally carries the SkyBlock id;
	 * the screen title is the fallback for pages that only show a display name.
	 */
	private static String findProductId(AbstractContainerScreen<?> container) {
		List<Flip> flips = OptimizerService.top();

		if (flips.isEmpty()) {
			return null;
		}

		int containerSlots = Math.max(0, container.getMenu().slots.size() - 36);

		for (int i = 0; i < containerSlots; i++) {
			ItemStack stack = container.getMenu().slots.get(i).getItem();

			if (stack.isEmpty()) {
				continue;
			}

			String id = skyblockId(stack);

			if (!id.isEmpty() && findFlip(id) != null) {
				return id;
			}
		}

		for (int i = 0; i < containerSlots; i++) {
			ItemStack stack = container.getMenu().slots.get(i).getItem();

			if (stack.isEmpty()) {
				continue;
			}

			String shown = key(ItemNames.strip(stack.getHoverName().getString()));

			if (shown.isEmpty()) {
				continue;
			}

			for (Flip flip : flips) {
				if (shown.equals(key(ItemNames.name(flip.id())))) {
					return flip.id();
				}
			}
		}

		String titleProduct = key(productName(container.getTitle().getString()));

		if (!titleProduct.isEmpty()) {
			for (Flip flip : flips) {
				if (titleProduct.equals(key(ItemNames.name(flip.id())))) {
					return flip.id();
				}
			}
		}

		return null;
	}

	private static String skyblockId(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);

		if (data == null) {
			return "";
		}

		CompoundTag tag = data.copyTag();
		CompoundTag extra = tag.getCompoundOrEmpty("ExtraAttributes");
		String id = extra.getStringOr("id", "");

		if (id.isEmpty()) {
			id = tag.getStringOr("id", "");
		}

		return id.toUpperCase(Locale.ROOT);
	}

	private static boolean isQuantity(String title) {
		String lower = title.toLowerCase(Locale.ROOT);
		return lower.contains("how many") || lower.contains("instant buy");
	}

	private static boolean isPrice(String title) {
		String lower = title.toLowerCase(Locale.ROOT);
		return lower.contains("how much do you want to pay") || lower.contains("at what price");
	}

	private static boolean isProduct(String title) {
		int firstArrow = firstArrowIndex(title);

		if (firstArrow < 0) {
			return false;
		}

		String before = title.substring(0, firstArrow).trim().toLowerCase(Locale.ROOT);
		int secondArrow = arrowIndex(title.substring(firstArrow + 1));
		return !before.startsWith("bazaar") || secondArrow >= 0;
	}

	private static Kind priceKind(String title) {
		String lower = title.toLowerCase(Locale.ROOT);

		if (lower.contains("selling") || lower.contains("sell")) {
			return Kind.SELL_PRICE;
		}

		if (lower.contains("pay") || lower.contains("buy")) {
			return Kind.BUY_PRICE;
		}

		return null;
	}

	private static String productName(String title) {
		int arrow = arrowIndex(title);

		if (arrow < 0) {
			return "";
		}

		String tail = title.substring(arrow + 1).trim();
		return tail.startsWith("-") || tail.startsWith(">") ? tail.substring(1).trim() : tail;
	}

	private static int arrowIndex(String title) {
		int best = -1;

		for (String arrow : NO_ARROWS) {
			best = Math.max(best, title.indexOf(arrow));
		}

		return best;
	}

	private static int firstArrowIndex(String title) {
		int first = Integer.MAX_VALUE;

		for (String arrow : NO_ARROWS) {
			int index = title.indexOf(arrow);

			if (index >= 0) {
				first = Math.min(first, index);
			}
		}

		return first == Integer.MAX_VALUE ? -1 : first;
	}

	private static String key(String text) {
		StringBuilder builder = new StringBuilder(text.length());

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			if (Character.isLetterOrDigit(c)) {
				builder.append(Character.toLowerCase(c));
			}
		}

		return builder.toString();
	}

	private static String trimPrice(double price) {
		return price == Math.floor(price)
				? Long.toString((long) price)
				: String.format(Locale.ROOT, "%.1f", price);
	}

	private static void clearPending() {
		pendingProductId = null;
		pendingKind = null;
		pendingTicks = 0;
	}
}
