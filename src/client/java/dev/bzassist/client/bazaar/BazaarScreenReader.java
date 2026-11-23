package dev.bzassist.client.bazaar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import dev.bzassist.client.mixin.AbstractContainerScreenAccessor;

/**
 * Reads whatever container is open into a {@link BazaarView} every other tick, and learns the things
 * the game will not tell the API: the tax rate, the order slots, our own orders, and the real display
 * name of every product whose page the player opens.
 *
 * <p>Nothing here is guessed at render time: the scan happens on the client tick, so the render pass
 * only reads the finished view.</p>
 */
public final class BazaarScreenReader {
	private static final Pattern PERCENT = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*%");
	private static final Pattern SLOTS_USED =
			Pattern.compile("orders?\\D{0,12}?(\\d{1,2})\\s*/\\s*(\\d{1,2})");

	private static volatile BazaarView view = BazaarView.NONE;
	private static volatile BazaarView lastContainer = BazaarView.NONE;
	private static Screen activeScreen;
	private static BazaarView.Kind lastKind = BazaarView.Kind.NONE;
	private static String lastTitle = "";
	private static int ticks;

	private BazaarScreenReader() {
	}

	public static void register() {
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			activeScreen = screen;
			net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.remove(screen)
					.register(removed -> {
						if (activeScreen == removed) {
							activeScreen = null;
						}
					});
		});
	}

	public static BazaarView view() {
		return view;
	}

	/** Cheap: rebuilds at most every other tick, and only for container screens. */
	public static void tick() {
		if (++ticks % 2 != 0) {
			return;
		}

		Screen screen = activeScreen;

		if (!(screen instanceof AbstractContainerScreen<?> container)) {
			view = BazaarView.NONE;
			noteChange(view);
			return;
		}

		BazaarView scanned = scan(container);

		if (scanned.kind() != BazaarView.Kind.ORDERS && looksLikeOrders(scanned)) {
			// The order list can be titled "(Co-op) Bazaar Orders" or something the classifier has not
			// seen; its rows are unmistakable, so trust them over the title.
			scanned = new BazaarView(BazaarView.Kind.ORDERS, scanned.title(), scanned.slots());
		}

		if (scanned.kind() != BazaarView.Kind.OTHER) {
			readFacts(scanned);
		}

		view = scanned;
		lastContainer = scanned;
		noteChange(scanned);
		DebugLog.screen(scanned);
	}

	/**
	 * True when a page's rows are unmistakably our own orders: a row titled "BUY …" or "SELL …" whose lore
	 * prints "Offer amount". A product page also prints "Price per unit" and the words "Sell Offer", which
	 * is why the older, looser test misread those pages as the order list.
	 */
	private static boolean looksLikeOrders(BazaarView view) {
		if (view.kind() != BazaarView.Kind.OTHER && view.kind() != BazaarView.Kind.ROOT) {
			return false;
		}

		for (BazaarView.SlotView slot : view.slots()) {
			String name = slot.name().toLowerCase(java.util.Locale.ROOT);

			if ((name.startsWith("buy ") || name.startsWith("sell ")) && slot.loreContains("offer amount")) {
				return true;
			}
		}

		return false;
	}

	/** Records every change of container in the session report, so a session can be replayed. */
	private static void noteChange(BazaarView scanned) {
		if (scanned.kind() == lastKind && scanned.title().equals(lastTitle)) {
			return;
		}

		lastKind = scanned.kind();
		lastTitle = scanned.title();
		SessionLog.screen(scanned);
	}

	/** The most recent bazaar page, which stays valid while a sign opened from it is up. */
	public static BazaarView lastContainer() {
		return lastContainer;
	}

	/** True while a sign screen is open (the custom amount or price sign). */
	public static boolean signOpen() {
		return activeScreen instanceof AbstractSignEditScreen;
	}

	private static BazaarView scan(AbstractContainerScreen<?> container) {
		String title = ItemNames.strip(container.getTitle().getString()).trim();
		BazaarView.Kind kind = BazaarView.classify(title);
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) (Object) container;
		int left = accessor.bzassist$leftPos();
		int top = accessor.bzassist$topPos();
		AbstractContainerMenu menu = container.getMenu();
		List<BazaarView.SlotView> slots = new ArrayList<>();
		int containerSize = Math.max(0, menu.slots.size() - 36);

		for (int i = 0; i < menu.slots.size() && i < containerSize + 36; i++) {
			Slot slot = menu.slots.get(i);
			ItemStack stack = slot.getItem();

			if (stack.isEmpty()) {
				continue;
			}

			boolean playerInventory = i >= containerSize;

			if (playerInventory) {
				continue;
			}

			String name = ItemNames.strip(stack.getHoverName().getString()).trim();
			String product = ItemNames.idForScreenName(name);

			if (product != null) {
				ItemIcons.remember(product, stack);
			}

			ItemIcons.remember(skyblockId(stack), stack);
			slots.add(new BazaarView.SlotView(i, left + slot.x, top + slot.y, name,
					skyblockId(stack), stack.getCount(), lore(stack)));
		}

		return new BazaarView(kind, title, slots);
	}

	private static List<String> lore(ItemStack stack) {
		ItemLore component = stack.get(DataComponents.LORE);

		if (component == null) {
			return List.of();
		}

		List<String> lines = new ArrayList<>(component.lines().size());

		for (var line : component.lines()) {
			lines.add(ItemNames.strip(line.getString()).trim());
		}

		return lines;
	}

	private static void readFacts(BazaarView scanned) {
		// The sell/confirm screens print the live tax, which already includes Bazaar Flipper.
		for (BazaarView.SlotView slot : scanned.slots()) {
			if (!slot.loreContains("tax")) {
				continue;
			}

			Matcher matcher = PERCENT.matcher(slot.loreText());

			if (matcher.find()) {
				try {
					BazaarConfig.setTaxPercent(Double.parseDouble(matcher.group(1)));
				} catch (NumberFormatException ignored) {
					// A weird lore line is not worth crashing over.
				}
			}
		}

		for (BazaarView.SlotView slot : scanned.slots()) {
			Matcher matcher = SLOTS_USED.matcher(slot.loreText());

			if (matcher.find()) {
				try {
					BazaarConfig.setOrderSlots(Integer.parseInt(matcher.group(2)));
				} catch (NumberFormatException ignored) {
					// Ignored on purpose.
				}
			}
		}

		// Any product on screen teaches us its real name.
		for (BazaarView.SlotView slot : scanned.slots()) {
			if (!slot.itemId().isEmpty()) {
				ItemNames.learn(slot.itemId(), slot.name());
			}
		}

		if (scanned.kind() == BazaarView.Kind.PRODUCT) {
			String name = BazaarView.productName(scanned.title());
			BazaarView.SlotView chosen = scanned.withLore("the item you have chosen");

			if (chosen == null) {
				chosen = scanned.named(name);
			}

			if (chosen != null && !chosen.itemId().isEmpty()) {
				ItemNames.learn(chosen.itemId(), name.isEmpty() ? chosen.name() : name);
			}
		}
	}

	/** The Skyblock id carried in {@code ExtraAttributes}, or "" when the item is not a Skyblock item. */
	public static String skyblockId(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "";
		}

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

	/** Debug helper: every slot of the current view as lines of text. */
	public static List<String> describe() {
		BazaarView current = view;
		List<String> lines = new ArrayList<>();
		lines.add("view: " + current.kind() + " \"" + current.title() + "\" slots=" + current.slots().size());

		for (BazaarView.SlotView slot : current.slots()) {
			lines.add(String.format("[%2d] %-30s x%d id=%s", slot.index(), slot.name(), slot.count(), slot.itemId()));

			for (String lore : slot.lore()) {
				if (!lore.isBlank()) {
					lines.add("      | " + lore);
				}
			}
		}

		return lines;
	}
}
