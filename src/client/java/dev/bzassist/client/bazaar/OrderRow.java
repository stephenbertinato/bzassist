package dev.bzassist.client.bazaar;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One row of our own bazaar order list, parsed from exactly what the row prints.
 *
 * <p>Hypixel titles a row {@code SELL <name>} or {@code BUY <name>} — the side is the name prefix,
 * not the lore. The lore carries {@code Offer amount: 89x} and
 * {@code Price per unit: 749,986.8 coins}. There is <b>no</b> line marking an order as outbid or
 * undercut and no reliable filled line, so those questions are answered against the live API
 * ({@link BazaarProduct#unitsAhead}) rather than from the tooltip.</p>
 */
public record OrderRow(String id, String name, boolean sellSide, double price, int filled, int total) {
	private static final Pattern SPLIT = Pattern.compile("(\\d[\\d,]*)\\s*/\\s*(\\d[\\d,]*)");
	/** "Offer amount: 89x" as the order rows print it when nothing has filled yet. */
	private static final Pattern OFFER_AMOUNT =
			Pattern.compile("(?:offer|order)?\\s*amount\\s*:?\\s*(\\d[\\d,]*)");
	private static final Pattern FILLED = Pattern.compile("filled\\s*:?\\s*(\\d[\\d,]*)");
	private static final Pattern UNIT_PRICE = Pattern.compile("price per unit\\s*:?\\s*(\\d[\\d,]*(?:\\.\\d+)?)");
	private static final Pattern MONEY = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)");

	public OrderRow {
		filled = Math.max(0, filled);
		total = Math.max(filled, Math.max(1, total));
	}

	/** Units still waiting inside the order. */
	public int open() {
		return Math.max(total - filled, 0);
	}

	/** True when the row resolved to a bazaar product and can be priced against the book. */
	public boolean readable() {
		return id != null;
	}

	/** Null when the slot is not one of our order rows at all. */
	public static OrderRow parse(BazaarView.SlotView slot) {
		if (slot == null) {
			return null;
		}

		String rawName = slot.name();
		String lower = rawName.toLowerCase(Locale.ROOT);
		String lore = slot.loreText();
		boolean sellSide = lower.startsWith("sell ") || lore.contains("sell offer");
		boolean buySide = lower.startsWith("buy ") || lore.contains("buy order");

		if (!sellSide && !buySide) {
			return null;
		}

		String id = idOf(slot);
		String name = id != null ? ItemNames.name(id) : stripSide(rawName);
		int total = Math.max(1, slot.count());
		int filled = 0;
		Matcher split = SPLIT.matcher(lore);
		Matcher amount = OFFER_AMOUNT.matcher(lore);
		Matcher filledLine = FILLED.matcher(lore);

		if (split.find()) {
			filled = number(split.group(1));
			total = Math.max(filled, number(split.group(2)));
		} else if (amount.find()) {
			total = Math.max(1, number(amount.group(1)));
		}

		if (filledLine.find()) {
			filled = Math.max(filled, number(filledLine.group(1)));
		}

		return new OrderRow(id, name, sellSide, priceOf(slot), filled, total);
	}

	/** "SELL Broken Radar" to "Broken Radar". */
	private static String stripSide(String name) {
		String trimmed = name.trim();
		String lower = trimmed.toLowerCase(Locale.ROOT);

		if (lower.startsWith("sell ") || lower.startsWith("buy ")) {
			return trimmed.substring(5).trim();
		}

		return trimmed;
	}

	/**
	 * The product an order row is about. The game prints the SkyBlock id on the item itself
	 * ({@code NULL_ATOM}), which is also the bazaar id, so that is tried first; the name and then the
	 * lore are the fallbacks.
	 */
	private static String idOf(BazaarView.SlotView slot) {
		String itemId = slot.itemId();

		if (itemId != null && !itemId.isBlank() && BazaarApi.snapshot().get(itemId) != null) {
			return itemId;
		}

		String id = ItemNames.idForScreenName(slot.name());

		if (id == null) {
			id = ItemNames.idFor(slot.name());
		}

		if (id == null) {
			for (String line : slot.lore()) {
				id = ItemNames.idFor(line);

				if (id != null) {
					break;
				}
			}
		}

		return id;
	}

	/**
	 * The price of one unit. Order rows print both "Price per unit: 12.4k" and "Total price: 793.6k", so
	 * taking the largest number on the lore — as an earlier version did — read the total as the unit
	 * price and made every order look like it was already at the top of the book. The unit price line
	 * wins; otherwise the smallest plausible number is used.
	 */
	static double priceOf(BazaarView.SlotView slot) {
		Matcher unit = UNIT_PRICE.matcher(slot.loreText());

		if (unit.find()) {
			double parsed = Coins.parse(unit.group(1));

			if (parsed > 0.0) {
				return parsed;
			}
		}

		double smallest = 0.0;

		for (String line : slot.lore()) {
			String lower = line.toLowerCase(Locale.ROOT);
			boolean perUnit = lower.contains("per unit") || lower.contains("each");
			Matcher money = MONEY.matcher(line);

			while (money.find()) {
				double value;

				try {
					value = Double.parseDouble(money.group(1).replace(",", ""));
				} catch (NumberFormatException ignored) {
					continue;
				}

				if (value <= 0.0) {
					continue;
				}

				if (perUnit) {
					return value;
				}

				smallest = smallest <= 0.0 ? value : Math.min(smallest, value);
			}
		}

		return smallest;
	}

	private static int number(String text) {
		try {
			return Integer.parseInt(text.replace(",", "").trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
