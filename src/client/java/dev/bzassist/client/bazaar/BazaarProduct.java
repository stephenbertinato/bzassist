package dev.bzassist.client.bazaar;

import java.util.List;

/**
 * One bazaar product, as the Hypixel API describes it.
 *
 * <p><strong>The naming trap.</strong> Hypixel's two summary arrays are named from the point of view
 * of the player who <em>placed</em> the order, which is the opposite of what a buyer sees:</p>
 *
 * <ul>
 *     <li>{@code buy_summary} holds <strong>sell offers</strong> (players offering to sell). The cheapest
 *     of them is what you pay to buy instantly. We expose it as {@link #asks()}.</li>
 *     <li>{@code sell_summary} holds <strong>buy orders</strong> (players offering to buy). The highest of
 *     them is what you receive when you sell instantly. We expose it as {@link #bids()}.</li>
 * </ul>
 *
 * <p>{@code quick_status.buyPrice} / {@code sellPrice} are not the top of the book: they are smoothed
 * averages, and for a thin product they can sit outside the current spread. {@link #bestAsk()} and
 * {@link #bestBid()} read the book instead and are what trade planning must use.</p>
 *
 * <p>Both arrays are capped at 30 levels by Hypixel and are <em>not</em> reliably sorted (stale orders
 * show up out of sequence), so the best price is found by scanning for the minimum / maximum rather
 * than by taking index 0.</p>
 */
public record BazaarProduct(
		String id,
		double apiBuyPrice,
		double apiSellPrice,
		long buyVolume,
		long sellVolume,
		long buyMovingWeek,
		long sellMovingWeek,
		int buyOrders,
		int sellOrders,
		List<OrderLevel> asks,
		List<OrderLevel> bids) {

	public BazaarProduct {
		asks = List.copyOf(asks);
		bids = List.copyOf(bids);
	}

	/** Cheapest sell offer: the price of buying one unit instantly. 0 when the book is empty. */
	public double bestAsk() {
		double best = Double.POSITIVE_INFINITY;

		for (OrderLevel level : asks) {
			best = Math.min(best, level.pricePerUnit());
		}

		return Double.isFinite(best) ? best : 0.0;
	}

	/** Highest buy order: what selling one unit instantly is paid, before tax. 0 when the book is empty. */
	public double bestBid() {
		double best = 0.0;

		for (OrderLevel level : bids) {
			best = Math.max(best, level.pricePerUnit());
		}

		return best;
	}

	/** How many units are on offer at the best ask. */
	public long quantityAtAsk() {
		return quantityAt(bestAsk(), asks);
	}

	/** How many units are wanted at the best bid. */
	public long quantityAtBid() {
		return quantityAt(bestBid(), bids);
	}

	/** The most units an instant buy-then-sell of one unit can move: min of the two top levels. */
	public long fillableQuantity() {
		return Math.min(quantityAtAsk(), quantityAtBid());
	}

	/**
	 * Units standing in front of one of our orders at {@code price}: every unit priced better on the same
	 * side, plus the other units at that exact price. The API book already contains our own order, so
	 * {@code ownOpenUnits} is subtracted from the level at our price; without that an order alone at the
	 * front would report itself as its own queue.
	 *
	 * <p>The API exposes 30 levels per side, so an order priced below the visible book sees only the
	 * units inside those levels — an undercount, never an overcount.</p>
	 *
	 * @param sellSide    true for a sell offer (asks), false for a buy order (bids)
	 * @param price       our price per unit
	 * @param ownOpenUnits how much of our order is still open
	 */
	public long unitsAhead(boolean sellSide, double price, long ownOpenUnits) {
		return unitsAhead(sellSide ? asks : bids, price, sellSide, Math.max(0L, ownOpenUnits));
	}

	private static long unitsAhead(List<OrderLevel> levels, double price, boolean sellSide, long ownOpenUnits) {
		long better = 0L;
		long atPrice = 0L;

		for (OrderLevel level : levels) {
			if (Math.abs(level.pricePerUnit() - price) < 1.0E-4) {
				atPrice += level.amount();
			} else if (sellSide ? level.pricePerUnit() < price : level.pricePerUnit() > price) {
				better += level.amount();
			}
		}

		return better + Math.max(0L, atPrice - ownOpenUnits);
	}

	/** Coins per unit from buying at the best ask and selling into the best bid, after bazaar tax. */
	public double flipMargin(double taxFraction) {
		double ask = bestAsk();
		double bid = bestBid();

		if (ask <= 0.0 || bid <= 0.0) {
			return 0.0;
		}

		return bid * (1.0 - taxFraction) - ask;
	}

	/** {@link #flipMargin} as a fraction of what you spend per unit. */
	public double flipMarginFraction(double taxFraction) {
		double ask = bestAsk();
		return ask <= 0.0 ? 0.0 : flipMargin(taxFraction) / ask;
	}

	/** The smaller of the two weekly traded volumes: how much this product actually trades. */
	public long weeklyLiquidity() {
		return Math.min(buyMovingWeek, sellMovingWeek);
	}

	private static long quantityAt(double price, List<OrderLevel> levels) {
		long total = 0L;

		for (OrderLevel level : levels) {
			if (Math.abs(level.pricePerUnit() - price) < 1.0E-4) {
				total += level.amount();
			}
		}

		return total;
	}
}
