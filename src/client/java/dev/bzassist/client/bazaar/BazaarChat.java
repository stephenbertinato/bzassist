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
					+ "([\\d,]+)\\s*x\\s+(.+?)(?:\\s+at\\s+([\\d,]+(?:\\.\\d+)?)(?:\\s*coins?)?\\s*each)?[.!]?\\s*$");
	/**
	 * Claiming <b>items</b> out of a buy order, as opposed to coins out of a sale:
	 * {@code [Bazaar] Claimed 6x Null Atom worth 123,456 coins bought for 120,000 coins}. Only the count
	 * and the name are required, because the two money figures are what the wording is least certain
	 * about — but when they are printed they are the real cost basis of those units, which is exactly the
	 * number the profit can never be reconstructed without.
	 */
	private static final Pattern ITEMS_CLAIMED = Pattern.compile(
			"(?i)\\[bazaar]\\s+claimed\\s+([\\d,]+)\\s*x\\s+(.+?)"
					+ "(?:\\s*,?\\s+worth\\s+([\\d,]+(?:\\.\\d+)?)\\s*(?:coins?)?)?"
					+ "(?:\\s*,?\\s+bought\\s+for\\s+([\\d,]+(?:\\.\\d+)?)\\s*(?:coins?)?)?[.!]?\\s*$");

	/** One order Hypixel confirmed: "Buy Order Setup! 64x X for 2,937.6 coins." */
	public record OrderPlaced(boolean sellSide, int units, String itemName, String itemId, double coins) {
	}

	/** One sale Hypixel paid out: "Claimed 113.8 coins from selling 64x X at 1.8 each!" */
	public record Claimed(double coins, int units, String itemName, String itemId, double unitPrice) {
	}

	/** Items taken out of a buy order: "Claimed 6x X worth …, bought for …". */
	public record ItemsClaimed(int units, String itemName, String itemId, double worth, double paid) {
	}

	private BazaarChat() {
	}

	/** Feeds one system chat line through both parsers. Anything else is ignored. */
	public static void note(String line) {
		OrderPlaced placed = orderPlaced(line);

		if (placed != null) {
			// A sell offer's figure is what the player earns after tax, but the daily limit is charged on
			// the order's value, so the gross is put back before it is counted.
			ProfileState.confirmOrder(placed.sellSide() ? grossOf(placed.coins()) : placed.coins());
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
			FlipSession.noteClaimed();
			SessionLog.event("bazaar", String.format(Locale.ROOT, "claimed %s from %sx %s at %s each",
					Coins.format(claimed.coins()), claimed.units(), claimed.itemName(),
					Coins.format(claimed.unitPrice())));
			return;
		}

		ItemsClaimed items = itemsClaimed(line);

		if (items != null) {
			SessionStats.claimItems(items.itemId(), items.itemName(), items.units(), items.paid(),
					items.worth());
			FlipSession.noteClaimed();
		}
	}

	/**
	 * Parses the line Hypixel prints when items are taken out of a buy order, or null. Checked after the
	 * coin claim, whose wording ("coins from selling") can never be mistaken for this one.
	 */
	public static ItemsClaimed itemsClaimed(String line) {
		Matcher matcher = ITEMS_CLAIMED.matcher(clean(line));

		if (!matcher.find()) {
			return null;
		}

		String name = matcher.group(2).trim();
		String lower = name.toLowerCase(Locale.ROOT);

		if (lower.contains(" coins") || lower.startsWith("coins")) {
			return null;
		}

		int units = whole(matcher.group(1));
		double worth = matcher.group(3) == null ? 0.0 : Coins.parse(matcher.group(3));
		double paid = matcher.group(4) == null ? 0.0 : Coins.parse(matcher.group(4));

		if (units <= 0 || name.isEmpty()) {
			return null;
		}

		return new ItemsClaimed(units, name, ItemNames.idFor(name), worth, paid);
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

	/** The order value behind a post-tax sell figure: {@code net / (1 - tax)}. */
	private static double grossOf(double net) {
		double keep = 1.0 - Math.max(0.0, Math.min(0.5, BazaarConfig.taxFraction()));
		return keep <= 0.0 ? net : net / keep;
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
