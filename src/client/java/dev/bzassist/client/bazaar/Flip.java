package dev.bzassist.client.bazaar;

import java.util.Locale;

/**
 * One evaluated flip: what to place, how much, what it should earn, and what it costs against the
 * daily order-value limit once relisting is taken into account.
 *
 * @param buyPrice          buy order price (best bid + 0.1, so we sit at the top)
 * @param sellPrice         sell offer price (best ask - 0.1)
 * @param quantity          units per cycle after every constraint is applied
 * @param profitPerCycle    coins per completed cycle, after tax
 * @param profitPerHour     coins per hour while the cycle runs — the thing being maximised
 * @param limitPerCycle     daily-limit coins consumed by one cycle, relists included
 * @param limitPerDay       projected daily-limit consumption if run continuously
 * @param limitEfficiency   profit returned per limit coin spent; below the floor a flip is rejected
 * @param fillHours         expected time to fill the buy order and then the sell offer
 * @param dailyVolume       units per day that actually change hands through the book
 * @param competitors       rival orders on the side we would join
 * @param worth             0..1 display score, dominated by profit per hour
 */
public record Flip(
		String id,
		String name,
		double buyPrice,
		double sellPrice,
		double marginPerUnit,
		double marginFraction,
		int quantity,
		double profitPerCycle,
		double profitPerHour,
		double profitPerDay,
		double limitPerCycle,
		double limitPerDay,
		double limitEfficiency,
		double fillHours,
		long dailyVolume,
		int competitors,
		int relistBuy,
		int relistSell,
		double realisation,
		double worth) {

	/**
	 * Profit per hour after everything the market and the limit cap actually take away: competition on
	 * the exit side, whether the 15B daily cap can sustain the rate at all, and how much each limit
	 * coin returns. This is the number the list is sorted on and what the chat line shows.
	 */
	public double adjustedPerHour() {
		return profitPerHour * realisation;
	}

	/**
	 * Limit coins this flip burns per hour while it cycles. Every placement is charged when it is
	 * created and every relist is charged again, so this is
	 * {@code limitPerCycle / fillHours}: one cycle's buy order, its relists, the sell offer and its
	 * relists, all paid again on the next cycle. It is the rate at which the 15B daily cap drains.
	 */
	public double limitPerHour() {
		return fillHours > 0.0 ? limitPerCycle / fillHours : 0.0;
	}

	/** Hours of flipping this one flip can run before it alone has spent the whole 15B cap. */
	public double capHours() {
		double rate = limitPerHour();
		return rate > 0.0 ? ProfileState.DAILY_LIMIT_COINS / rate : Double.POSITIVE_INFINITY;
	}

	/** {@link #capHours()} as a short column, e.g. {@code 6.2h}. */
	public String capText() {
		double hours = capHours();

		if (Double.isInfinite(hours)) {
			return "never";
		}

		return hours < 10.0
				? String.format(Locale.ROOT, "%.1fh", hours)
				: String.format(Locale.ROOT, "%.0fh", hours);
	}

	/**
	 * One dense line for chat: profit per hour first because that decides the rank, then what one
	 * cycle pays, then the two costs that decide how long you can keep going — limit coins spent per
	 * hour and how many hours of that the 15B cap affords — and finally the return per limit coin.
	 */
	public String line(int index) {
		return String.format(Locale.ROOT, "%2d %-22s %10s %9s %10s %6s %6.2f%%",
				index,
				trim(name, 22),
				Coins.compact(adjustedPerHour()) + "/h",
				"+" + Coins.compact(profitPerCycle),
				Coins.compact(limitPerHour()) + "/h",
				capText(),
				limitEfficiency * 100.0);
	}

	private static String trim(String text, int width) {
		return text.length() <= width ? text : text.substring(0, width - 1) + "…";
	}
}
