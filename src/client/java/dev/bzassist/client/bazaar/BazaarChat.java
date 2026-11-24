package dev.bzassist.client.bazaar;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chat lines Hypixel prints when a bazaar order is really created or a sale is really claimed.
 *
 * <p>These are the only confirmation the server gives: a confirm screen can be opened and closed
 * without clicking anything, so neither the daily limit nor the session stats may be charged from the
 * screen alone. Confirmed against the live game:</p>
 *
 * <pre>
 * [Bazaar] Buy Order Setup! 64x Magical Mushroom Soup for 2,937.6 coins.
 * [Bazaar] Sell Offer Setup! 64x Cobblestone for 113.8 coins.
 * [Bazaar] Claimed 113.8 coins from selling 64x Cobblestone at 1.8 each!
 * </pre>
 *
 * <p>A sell offer's figure is what the player earns after tax (the confirm screen's {@code You earn}),
 * which is the number the limit and the stats have always used; a buy order's figure is the total
 * paid.</p>
 */
public final class BazaarChat {
	private static final Pattern ORDER_SETUP = Pattern.compile(
			"(?i)\\[bazaar]\\s+(buy order|sell offer)\\s+setup!\\s+([\\d,]+)\\s*x\\s+(.+?)\\s+for\\s+"
					+ "([\\d,]+(?:\\.\\d+)?)\\s*coins?\\.?\\s*$");
	private static final Pattern CLAIM = Pattern.compile(
			"(?i)\\[bazaar]\\s+claimed\\s+([\\d,]+(?:\\.\\d+)?)\\s*coins?\\s+from\\s+selling\\s+"
					+ "([\\d,]+)\\s*x\\s+(.+?)(?:\\s+at\\s+([\\d,]+(?:\\.\\d+)?)\\s*each)?[.!]?\\s*$");

	/** One order Hypixel confirmed: "Buy Order Setup! 64x X for 2,937.6 coins." */
	public record OrderPlaced(boolean sellSide, int units, String itemName, String itemId, double coins) {
	}

	/** One sale Hypixel paid out: "Claimed 113.8 coins from selling 64x X at 1.8 each!" */
	public record Claimed(double coins, int units, String itemName, String itemId, double unitPrice) {
	}

	private BazaarChat() {
	}

	/** Feeds one system chat line through both parsers. Anything else is ignored. */
	public static void note(String line) {
		OrderPlaced placed = orderPlaced(line);

		if (placed != null) {
			ProfileState.confirmOrder(placed.coins());
			SessionStats.order(placed.itemId(), placed.itemName(), !placed.sellSide(), placed.units(),
					placed.coins());
			SessionLog.event("bazaar", String.format(Locale.ROOT, "%s %sx %s for %s",
					placed.sellSide() ? "sell offer" : "buy order", placed.units(), placed.itemName(),
					Coins.format(placed.coins())));
			return;
		}

		Claimed claimed = claim(line);

		if (claimed != null) {
			SessionStats.claim(claimed.itemId(), claimed.itemName(), claimed.units(), claimed.coins());
			SessionLog.event("bazaar", String.format(Locale.ROOT, "claimed %s from %sx %s at %s each",
					Coins.format(claimed.coins()), claimed.units(), claimed.itemName(),
					Coins.format(claimed.unitPrice())));
		}
	}

	/** Parses a buy/sell order setup line, or null when the line is something else. */
	public static OrderPlaced orderPlaced(String line) {
		Matcher matcher = ORDER_SETUP.matcher(clean(line));

		if (!matcher.find()) {
			return null;
		}

		boolean sellSide = matcher.group(1).toLowerCase(Locale.ROOT).startsWith("sell");
		int units = whole(matcher.group(2));
		String name = matcher.group(3).trim();
		double coins = Coins.parse(matcher.group(4));

		if (units <= 0 || name.isEmpty() || coins <= 0.0) {
			return null;
		}

		return new OrderPlaced(sellSide, units, name, ItemNames.idFor(name), coins);
	}

	/** Parses a claim line from a completed sale, or null when the line is something else. */
	public static Claimed claim(String line) {
		Matcher matcher = CLAIM.matcher(clean(line));

		if (!matcher.find()) {
			return null;
		}

		double coins = Coins.parse(matcher.group(1));
		int units = whole(matcher.group(2));
		String name = matcher.group(3).trim();
		double unitPrice = matcher.group(4) == null ? 0.0 : Coins.parse(matcher.group(4));

		if (coins <= 0.0 || units <= 0 || name.isEmpty()) {
			return null;
		}

		return new Claimed(coins, units, name, ItemNames.idFor(name), unitPrice);
	}

	private static String clean(String line) {
		return line == null ? "" : line.trim();
	}

	private static int whole(String text) {
		try {
			return Integer.parseInt(text.replace(",", "").trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
