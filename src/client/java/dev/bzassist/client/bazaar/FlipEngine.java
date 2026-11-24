package dev.bzassist.client.bazaar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns an immutable bazaar snapshot into ranked, sized flips.
 *
 * <p>This class is pure: it takes a snapshot and an {@link OptimizerContext} and returns results. It
 * does not read the API singleton, the player, or the config inside the loop, so one pass always sees
 * one consistent set of inputs.</p>
 *
 * <p><strong>Sizing.</strong> The recommendation is the largest order that still clears inside the
 * configured target fill time, subject to the purse, free inventory, a notional cap and a unit cap.
 * That is intentionally a cap, not an interior optimum: under the fill model, profit per hour rises
 * with quantity, so the useful question is "how much can I place without the order becoming slow?"</p>
 */
public final class FlipEngine {
	private static final double TICK = 0.1;

	/** Units per day below which a product is treated as untradable. */
	private static final double MIN_FLOW = 0.05;

	/** How far the top of the book may sit from the 7-day volume-weighted average. */
	private static final double MIN_PRICE_RATIO = 0.5;
	private static final double MAX_PRICE_RATIO = 2.0;

	/** Coins traded per week that must exist on each side before the market is real. */
	private static final double MIN_WEEKLY_COINS = 5.0E6;

	/** Distinct orders among the five best levels on each side. */
	private static final int MIN_TOP_ORDERS = 2;

	/** Profit per coin of daily limit spent. */
	private static final double MIN_LIMIT_EFFICIENCY = 0.04;

	/** Fastest believable full cycle; clicking through both sides takes minutes. */
	private static final double MIN_CYCLE_HOURS = 0.05;

	/** Fixed click/claim/listing cost added to every cycle. */
	private static final double CYCLE_OVERHEAD_HOURS = 0.033;

	/** A spread so absurd it cannot be real is rejected outright. */
	private static final double MAX_SPREAD = 5.0;

	/** Hard cap on units per order. */
	private static final long MAX_ORDER_UNITS = 2_240;
	private static final long MAX_ESSENCE_UNITS = 71_680;

	/** Coins of order value one order may commit. */
	private static final double MAX_ORDER_COINS = 60.0E6;

	/** Ceiling on relists per side, so a pathological book cannot overflow the count. */
	private static final double MAX_RELISTS = 200.0;

	private FlipEngine() {
	}

	/** The best flips right now, ranked by expected profit per hour. */
	public static List<Flip> rank(BazaarSnapshot snapshot, OptimizerContext context, int limit) {
		if (snapshot == null || context == null || limit <= 0) {
			return List.of();
		}

		List<Flip> flips = new ArrayList<>();

		for (BazaarProduct product : snapshot.products().values()) {
			Flip flip = evaluate(product, context);

			if (flip != null) {
				flips.add(flip);
			}
		}

		flips.sort(Comparator.comparingDouble(Flip::profitPerHour).reversed());

		return flips.size() > limit ? new ArrayList<>(flips.subList(0, limit)) : flips;
	}

	/** Everything computed for one product, or null when it is not worth placing an order on. */
	public static Flip evaluate(BazaarProduct product, OptimizerContext context) {
		if (product == null || context == null) {
			return null;
		}

		double ask = product.bestAsk();
		double bid = product.bestBid();

		if (ask <= 0.0 || bid <= 0.0 || bid < 1.0 || ask < 2.0) {
			return null;
		}

		// The top of the book has to be near the smoothed weekly price. This separates a genuinely
		// wide, thin market from a stale order.
		if (product.apiSellPrice() <= 0.0 || product.apiBuyPrice() <= 0.0) {
			return null;
		}

		if (bid < product.apiSellPrice() * MIN_PRICE_RATIO || bid > product.apiSellPrice() * MAX_PRICE_RATIO
				|| ask < product.apiBuyPrice() * MIN_PRICE_RATIO || ask > product.apiBuyPrice() * MAX_PRICE_RATIO) {
			return null;
		}

		double buyPrice = round(bid + TICK);
		double sellPrice = round(ask - TICK);

		if (sellPrice <= buyPrice) {
			return null;
		}

		double margin = sellPrice * (1.0 - context.taxFraction()) - buyPrice;

		if (margin <= 0.0) {
			return null;
		}

		if ((ask - bid) / bid > MAX_SPREAD) {
			return null;
		}

		// API moving-week values are units. Selling into bids fills our buy order; buying from asks
		// fills our sell offer.
		double flowIntoBids = Math.max(0.0, product.sellMovingWeek() / 7.0);
		double flowFromAsks = Math.max(0.0, product.buyMovingWeek() / 7.0);

		if (flowIntoBids < MIN_FLOW || flowFromAsks < MIN_FLOW) {
			return null;
		}

		double weeklyBidCoins = product.sellMovingWeek() * product.apiSellPrice();
		double weeklyAskCoins = product.buyMovingWeek() * product.apiBuyPrice();

		if (weeklyBidCoins < MIN_WEEKLY_COINS || weeklyAskCoins < MIN_WEEKLY_COINS) {
			return null;
		}

		if (ordersInTop(product.bids(), true) < MIN_TOP_ORDERS
				|| ordersInTop(product.asks(), false) < MIN_TOP_ORDERS) {
			return null;
		}

		// The purse is a hard limit when it is known.
		if (context.purseKnown() && context.purse() < buyPrice) {
			return null;
		}

		double affordable = context.budget() / buyPrice;
		double holdable = context.capacity(product.id());
		double perHourBid = flowIntoBids / 24.0;
		double perHourAsk = flowFromAsks / 24.0;
		double flowPerDay = Math.min(flowIntoBids, flowFromAsks);
		// Target the whole buy-plus-sell cycle, not each side separately.
		double targetUnits = Math.max(0.0, context.targetFillHours())
				/ (1.0 / perHourBid + 1.0 / perHourAsk);
		double coinCap = MAX_ORDER_COINS / Math.max(1.0, buyPrice + sellPrice);

		long units = (long) Math.floor(Math.min(Math.min(affordable, holdable), Math.min(targetUnits, coinCap)));

		if (units < 1L) {
			return null;
		}

		units = Math.min(units, product.id().startsWith("ESSENCE_") ? MAX_ESSENCE_UNITS : MAX_ORDER_UNITS);

		// Fill time: queue ahead plus our units at the market's rate, plus the fixed click cost.
		double queueBid = queued(product.bids(), buyPrice, true);
		double queueAsk = queued(product.asks(), sellPrice, false);
		double hoursBid = (queueBid + units) / perHourBid;
		double hoursAsk = (queueAsk + units) / perHourAsk;
		double hours = Math.max(MIN_CYCLE_HOURS, hoursBid + hoursAsk + CYCLE_OVERHEAD_HOURS);

		// An order that cannot clear inside the hard window is not a recommendation.
		if (hours > Math.max(0.0, context.maxFillHours())) {
			return null;
		}

		// Being undercut costs daily limit, not coins. Each side has its own crowd.
		double undercutBid = clamp(1.0 / (1.0 + Math.max(1, product.buyOrders()) / 8.0), 0.1, 2.0);
		double undercutAsk = clamp(1.0 / (1.0 + Math.max(1, product.sellOrders()) / 8.0), 0.1, 2.0);
		int relistBuy = (int) Math.min(MAX_RELISTS, Math.floor(hoursBid / undercutBid));
		int relistSell = (int) Math.min(MAX_RELISTS, Math.floor(hoursAsk / undercutAsk));

		double profitPerCycle = units * margin;
		double limitPerCycle = units * (buyPrice * (1 + relistBuy) + sellPrice * (1 + relistSell));
		double efficiency = limitPerCycle > 0.0 ? profitPerCycle / limitPerCycle : 0.0;

		if (efficiency < MIN_LIMIT_EFFICIENCY) {
			return null;
		}

		long dailyVolume = Math.round(flowPerDay);

		return new Flip(product.id(), buyPrice, sellPrice, margin, margin / buyPrice, (int) units,
				profitPerCycle, profitPerCycle / hours, hours, efficiency, dailyVolume);
	}

	/** Units sitting at or better than {@code price} on the side we would join. */
	private static double queued(List<OrderLevel> levels, double price, boolean bids) {
		double total = 0.0;

		for (OrderLevel level : levels) {
			boolean ahead = bids ? level.pricePerUnit() >= price - 1.0E-6 : level.pricePerUnit() <= price + 1.0E-6;

			if (ahead) {
				total += level.amount();
			}
		}

		return total;
	}

	/** Distinct orders across the five best-priced levels of one side. */
	private static int ordersInTop(List<OrderLevel> levels, boolean bids) {
		if (levels.isEmpty()) {
			return 0;
		}

		List<OrderLevel> sorted = new ArrayList<>(levels);
		sorted.sort(bids
				? Comparator.comparingDouble(OrderLevel::pricePerUnit).reversed()
				: Comparator.comparingDouble(OrderLevel::pricePerUnit));
		int total = 0;

		for (int i = 0; i < Math.min(5, sorted.size()); i++) {
			total += sorted.get(i).orders();
		}

		return total;
	}

	private static double round(double value) {
		return Math.round(value * 10.0) / 10.0;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
