package dev.bzassist.client.bazaar;

import java.util.Locale;

/**
 * One ranked bazaar flip: the two prices to place, the recommended quantity, and what the order is
 * expected to earn if it clears within the modelled fill time.
 *
 * @param id               bazaar product id; the display name is resolved by the UI
 * @param buyPrice         buy-order price, one tick above the best bid
 * @param sellPrice        sell-offer price, one tick below the best ask
 * @param quantity         recommended units for this order
 * @param profitPerCycle   coins per completed cycle, after tax
 * @param profitPerHour    expected coins per hour while the cycle runs
 * @param fillHours        expected buy-fill plus sell-fill plus click overhead
 * @param limitEfficiency  profit returned per daily-limit coin spent
 * @param dailyVolume      units per day that actually change hands through the book
 */
public record Flip(
		String id,
		double buyPrice,
		double sellPrice,
		double marginPerUnit,
		double marginFraction,
		int quantity,
		double profitPerCycle,
		double profitPerHour,
		double fillHours,
		double limitEfficiency,
		long dailyVolume) {

	/** Coins committed to the buy order at the recommended quantity. */
	public double orderCost() {
		return quantity * buyPrice;
	}

	/** Fill time as a short table cell: {@code 6m}, {@code 1.4h}. */
	public String fillText() {
		if (fillHours <= 0.0 || !Double.isFinite(fillHours)) {
			return "-";
		}

		if (fillHours < 1.0) {
			return Math.max(1L, Math.round(fillHours * 60.0)) + "m";
		}

		return String.format(Locale.ROOT, "%.1fh", fillHours);
	}

	/** One chat line: rank, item, the two prices, quantity, profit/h, expected fill. */
	public String line(int index) {
		return String.format(Locale.ROOT, "%2d %-24s %8s %8s %5d %9s %6s",
				index,
				trim(ItemNames.name(id), 24),
				Coins.compact(buyPrice),
				Coins.compact(sellPrice),
				quantity,
				Coins.compact(profitPerHour) + "/h",
				fillText());
	}

	private static String trim(String text, int width) {
		return text.length() <= width ? text : text.substring(0, width - 1) + "…";
	}
}
