package dev.bzassist.client.bazaar;

import java.util.List;
import java.util.Locale;

/**
 * A snapshot of one open container screen: its title and every filled slot, with names and lore
 * already stripped of formatting codes.
 *
 * <p>Slot {@code x}/{@code y} are absolute GUI coordinates, so a highlight can be drawn without
 * asking the screen anything else.</p>
 */
public record BazaarView(Kind kind, String title, List<SlotView> slots) {
	public static final BazaarView NONE = new BazaarView(Kind.NONE, "", List.of());

	public BazaarView {
		slots = List.copyOf(slots);
	}

	public enum Kind {
		/** No container open. */
		NONE,
		/** The main "Bazaar" menu. */
		ROOT,
		/** "Bazaar ➜ Farming" and similar. */
		CATEGORY,
		/** "<Category> ➜ <Product>". */
		PRODUCT,
		/** Choosing an amount: "Instant Buy", "How many do you want?". */
		QUANTITY,
		/** Choosing a price: "How much do you want to pay?", "At what price are you selling?". */
		PRICE,
		/** "Confirm Buy Order" / "Confirm Sell Offer". */
		CONFIRM,
		/** "(Co-op) Bazaar Orders". */
		ORDERS,
		/** "Order options": one order, with its Cancel Order button. */
		ORDER_DETAIL,
		/** "View Stash": items or materials waiting to be collected. */
		STASH,
		/** Any other container. */
		OTHER
	}

	public record SlotView(int index, int x, int y, String name, String itemId, int count, List<String> lore) {
		public SlotView {
			lore = List.copyOf(lore);
		}

		public boolean isNamed(String needle) {
			return name.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
		}

		public String loreText() {
			return String.join(" ", lore).toLowerCase(Locale.ROOT);
		}

		public boolean loreContains(String needle) {
			return loreText().contains(needle.toLowerCase(Locale.ROOT));
		}
	}

	/** First slot whose name contains {@code needle}. */
	public SlotView named(String needle) {
		for (SlotView slot : slots) {
			if (slot.isNamed(needle)) {
				return slot;
			}
		}

		return null;
	}

	/** First slot whose lore contains {@code needle}. */
	public SlotView withLore(String needle) {
		for (SlotView slot : slots) {
			if (slot.loreContains(needle)) {
				return slot;
			}
		}

		return null;
	}

	public SlotView withItemId(String id) {
		if (id == null) {
			return null;
		}

		for (SlotView slot : slots) {
			if (id.equals(slot.itemId())) {
				return slot;
			}
		}

		return null;
	}

	public boolean isOpen() {
		return kind != Kind.NONE;
	}

	/** Classifies a screen from its title alone; every rule here came from the bazaar's own screens. */
	public static Kind classify(String title) {
		String text = title.toLowerCase(Locale.ROOT).trim();

		if (text.isEmpty()) {
			return Kind.NONE;
		}

		if (text.contains("view stash") || text.equals("stash") || text.endsWith(" stash")) {
			return Kind.STASH;
		}

		if (text.contains("order options")) {
			return Kind.ORDER_DETAIL;
		}

		if (text.contains("order") && (text.contains("bazaar orders") || text.contains("manage orders"))) {
			return Kind.ORDERS;
		}

		if (text.startsWith("confirm ")) {
			return Kind.CONFIRM;
		}

		if (text.contains("how many")) {
			return Kind.QUANTITY;
		}

		if (text.contains("how much do you want to pay") || text.contains("at what price")) {
			return Kind.PRICE;
		}

		if (text.contains("instant buy")) {
			return Kind.QUANTITY;
		}

		if (text.contains("settings") || text.contains("history")) {
			return Kind.OTHER;
		}

		int arrow = Math.max(text.indexOf('➜'), Math.max(text.indexOf('→'), text.indexOf("->")));

		if (arrow >= 0) {
			String before = text.substring(0, arrow).trim();
			return before.startsWith("bazaar") ? Kind.CATEGORY : Kind.PRODUCT;
		}

		if (text.startsWith("bazaar") || text.contains("bazaar")) {
			return Kind.ROOT;
		}

		return Kind.OTHER;
	}

	/** The product name on a "<Category> ➜ <Product>" screen. */
	public static String productName(String title) {
		int arrow = Math.max(title.indexOf('➜'), Math.max(title.indexOf('→'), title.indexOf("->")));

		if (arrow < 0) {
			return "";
		}

		String tail = title.substring(arrow + 1).trim();
		return tail.startsWith("-") || tail.startsWith(">") ? tail.substring(1).trim() : tail;
	}
}
