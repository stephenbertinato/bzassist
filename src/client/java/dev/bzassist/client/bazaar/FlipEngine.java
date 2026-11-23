package dev.bzassist.client.bazaar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the order book into ranked, sized flips.
 *
 * <h2>Units, and a bug worth remembering</h2>
 *
 * <p>Hypixel's documentation is explicit: {@code sellVolume}/{@code buyVolume} are "the sum of item
 * amounts in all orders" and {@code movingWeek} is "the historic transacted volume", i.e. <b>units,
 * not coins</b>. An earlier version divided {@code movingWeek} by the average price, which did nothing
 * to a 2-coin item but divided a million-coin item's flow by a million. Every expensive product looked
 * dead and only cheap churn survived. Flow is now taken straight from the API.</p>
 *
 * <h2>What is ranked</h2>
 *
 * <p>Profit per hour, and nothing else. The daily order-value limit only breaks ties. Running the cap
 * dry in two hours is fine; spending it on a flip that returns almost nothing is not, and that is
 * enforced by {@link #MIN_LIMIT_EFFICIENCY} rather than by demoting good flips.</p>
 *
 * <h2>There is no margin cap</h2>
 *
 * <p>A previous version rejected spreads wider than 300%. That was wrong: measured live,
 * {@code SHARD_BAMBULEAF} pays 227% and trades thousands of units a day, and enchantments regularly
 * show several hundred percent while filling. Wide spreads are only rejected when the top of the book
 * disagrees with its own 7-day average, which is what actually marks a stale order.</p>
 *
 * <h2>Sizing</h2>
 *
 * <p>Fill rate belongs to the market, not to the order, so a huge order just sits there being undercut.
 * Quantity is the smallest of: what the purse affords, what the inventory can hold, what the market
 * will clear within {@code max_fill_hours}, and half of the visible book depth — ordering more than
 * the book shows means queuing behind everyone already standing there.</p>
 */
public final class FlipEngine {
	private static final double TICK = 0.1;

	/** Units per day below which a product is treated as untradable. Deliberately very low. */
	private static final double MIN_FLOW = 0.05;

	/**
	 * How far the top of the book may sit from the 7-day volume-weighted average before it is treated as
	 * a stale order rather than a real price.
	 */
	private static final double MIN_PRICE_RATIO = 0.5;
	private static final double MAX_PRICE_RATIO = 2.0;

	/** Coins traded per week that must exist on each side before the market is real rather than listed. */
	private static final double MIN_WEEKLY_COINS = 5.0E6;

	/** Distinct orders among the five best levels on each side. One order is a listing, not a market. */
	private static final int MIN_TOP_ORDERS = 2;

	/**
	 * Profit per coin of daily limit spent. This is the guard against "1m profit on a 50m item": that
	 * flip returns about 2% of the limit it burns, so it is dropped, while a 30% margin on a cheap item
	 * returns 13% and stays. It replaces the old margin cap, which banned the wrong thing.
	 */
	private static final double MIN_LIMIT_EFFICIENCY = 0.04;

	/** Multiple of the units standing at the best price on each side that one order may cover. */
	private static final double TOP_LEVEL_SHARE = 2.0;

	/**
	 * Fastest believable full cycle. Clicking through a buy order, claiming and listing a sell offer
	 * takes minutes; without this floor a high-volume product reports fractions of a minute and its
	 * profit-per-hour explodes into fantasy.
	 */
	private static final double MIN_CYCLE_HOURS = 0.05;

	/** Profit per hour that scores 0.5 on the hourly term. */
	private static final double HOURLY_TARGET = 5.0E6;

	/**
	 * Weight of the competition discount. Crowding is {@code sellOrders / unitsSoldPerDay} — the offers
	 * standing between us and the buyers per unit that actually changes hands each day. At a weight of
	 * 10, a book with one offer per unit sold keeps about a tenth of its raw profit per hour, and a book
	 * with one offer per hundred units keeps almost all of it.
	 */
	private static final double CROWDING_WEIGHT = 10.0;

	/**
	 * Return on limit spent that scores full marks. Below it the ranking is scaled down proportionally,
	 * above it there is nothing extra to gain — otherwise a slow, ultra-efficient book would outrank
	 * everything just for burning almost no limit.
	 */
	private static final double EFFICIENCY_TARGET = 0.15;

	/**
	 * Fixed cost of a cycle that has nothing to do with the market: walking the guide, claiming, listing.
	 * Without it a tiny order looks free and the ranking over-credits one-unit flips.
	 */
	private static final double CYCLE_OVERHEAD_HOURS = 0.033;

	/**
	 * Spread that costs nothing, and how hard the excess is punished. A wide spread means the two fills
	 * are far apart in price, so the round trip holds capital while the market can move; 57% on Red Gift
	 * against 17% on Gemstone Mixture is not the same risk for the same nominal margin.
	 */
	private static final double MAX_SPREAD = 5.0;

	/**
	 * Hard cap on units per order. An order that is easy to replace costs little limit when it gets
	 * undercut; 2,240 is far more than a short fill slice needs while keeping the worst case bounded.
	 * Essence is exempt because it stacks to 10 million.
	 */
	private static final long MAX_ORDER_UNITS = 2_240;

	/**
	 * Coins of order value one order may commit. Expensive items reach a big limit far too easily:
	 * 30 Wither Catalysts is 170m of limit for a book that will not absorb it, so no single order goes
	 * past this regardless of the flow slice.
	 */
	private static final double MAX_ORDER_COINS = 60.0E6;

	/** A wide margin is worth waiting longer for, so its slice of flow grows with the margin. */
	private static final double SLICE_MARGIN_SCALE = 0.10;
	private static final double MAX_SLICE_MULTIPLIER = 5.0;

	/** Floor for a thin, high-margin book: one book of a 15% spread is not worth the clicks. */
	private static final int MIN_UNITS_HIGH_MARGIN = 2;
	private static final long MAX_ESSENCE_UNITS = 71_680;

	/**
	 * Ceiling on relists per side. It only exists so a pathological book cannot overflow the count; it
	 * used to be 20, which silently threw away the real cost of the busiest books — precisely the ones
	 * where an order is undercut over and over without ever filling. 200 relists over one fill window
	 * is already absurd, and anything at that level is priced out by the efficiency gate anyway.
	 */
	private static final double MAX_RELISTS = 200.0;

	private FlipEngine() {
	}

	/** The best flips right now, ranked by profit per hour. */
	public static List<Flip> rank(int limit) {
		BazaarSnapshot snapshot = BazaarApi.snapshot();

		if (snapshot.isEmpty()) {
			return List.of();
		}

		List<Flip> flips = new ArrayList<>();

		for (BazaarProduct product : snapshot.products().values()) {
			Flip flip = evaluate(product);

			if (flip != null) {
				flips.add(flip);
			}
		}

		// Ranked on profit per hour after the competition discount. The daily limit only breaks ties:
		// when two flips pay the same, prefer the one that spends less of the cap.
		flips.sort(Comparator.comparingDouble(Flip::adjustedPerHour).reversed()
				.thenComparingDouble(Flip::limitPerDay));

		return flips.size() > limit ? new ArrayList<>(flips.subList(0, limit)) : flips;
	}

	/** Position of a product in the full ranking, 1-based, or 0 when it is not tradable. */
	public static int rankOf(String id) {
		List<Flip> all = rank(Integer.MAX_VALUE);

		for (int i = 0; i < all.size(); i++) {
			if (all.get(i).id().equals(id)) {
				return i + 1;
			}
		}

		return 0;
	}

	public static int candidateCount() {
		return rank(Integer.MAX_VALUE).size();
	}

	/** Everything computed for one product, or null when it is not worth placing an order on. */
	public static Flip evaluate(BazaarProduct product) {
		if (product == null) {
			return null;
		}

		double ask = product.bestAsk();
		double bid = product.bestBid();

		if (ask <= 0.0 || bid <= 0.0) {
			return null;
		}

		// A book whose best bid is a coin or two is empty rather than wide: nobody fills those, so the
		// "margin" is a number that never happens.
		if (bid < 1.0 || ask < 2.0) {
			return null;
		}

		// No 7-day average means the product does not actually trade.
		if (product.apiSellPrice() <= 0.0 || product.apiBuyPrice() <= 0.0) {
			return null;
		}

		// The top of the book has to be near the volume-weighted average of the last week. This is what
		// separates a genuinely wide, thin market (worth trading slowly) from a stale rogue order.
		if (bid < product.apiSellPrice() * MIN_PRICE_RATIO || bid > product.apiSellPrice() * MAX_PRICE_RATIO
				|| ask < product.apiBuyPrice() * MIN_PRICE_RATIO || ask > product.apiBuyPrice() * MAX_PRICE_RATIO) {
			return null;
		}

		// Sit one tick in front of the book on both sides.
		double buyPrice = round(bid + TICK);
		double sellPrice = round(ask - TICK);

		if (sellPrice <= buyPrice) {
			return null;
		}

		double tax = BazaarConfig.taxFraction();
		double margin = sellPrice * (1.0 - tax) - buyPrice;

		if (margin <= 0.0) {
			return null;
		}

		// Already units per week in the API; units per day here. Selling into bids is what fills our buy
		// order, buying from asks is what fills our sell offer.
		double flowIntoBids = Math.max(0.0, product.sellMovingWeek() / 7.0);
		double flowFromAsks = Math.max(0.0, product.buyMovingWeek() / 7.0);

		if (flowIntoBids < MIN_FLOW || flowFromAsks < MIN_FLOW) {
			return null;
		}

		// Units moving is not the same as money moving: a product can trade a few items worth almost
		// nothing, or show volume that never converts into coins.
		double weeklyBidCoins = product.sellMovingWeek() * product.apiSellPrice();
		double weeklyAskCoins = product.buyMovingWeek() * product.apiBuyPrice();

		if (weeklyBidCoins < MIN_WEEKLY_COINS || weeklyAskCoins < MIN_WEEKLY_COINS) {
			return null;
		}

		// One order on each side is a listing somebody can walk away from, not a market.
		if (ordersInTop(product.bids(), true) < MIN_TOP_ORDERS
				|| ordersInTop(product.asks(), false) < MIN_TOP_ORDERS) {
			return null;
		}

		double purse = ProfileState.purseKnown() ? ProfileState.purse() : 1_000_000.0;
		double maxFillDays = Math.max(1.0 / 24.0, BazaarConfig.maxFillHours() / 24.0);

		// How often rivals re-price against us: the more orders on a side, the shorter the spell we hold
		// the front, so the more relists one fill takes.
		double undercutBid = clamp(1.0 / (1.0 + Math.max(1, product.buyOrders()) / 8.0), 0.1, 2.0);
		double undercutAsk = clamp(1.0 / (1.0 + Math.max(1, product.sellOrders()) / 8.0), 0.1, 2.0);

		// One order covers five minutes of market flow. Bigger orders on a busy item do not fill faster,
		// they just sit there being undercut while a bigger chunk of limit is committed; five minutes
		// fills before the price moves and is cheap to replace. The 2,240 cap still applies on top.
		double affordable = purse / buyPrice;
		double sliceHours = (5.0 / 60.0) * clamp(1.0 + margin / buyPrice / SLICE_MARGIN_SCALE, 1.0, MAX_SLICE_MULTIPLIER);
		double fiveMinutes = Math.min(flowIntoBids, flowFromAsks) * sliceHours / 24.0;
		double coinCap = MAX_ORDER_COINS / Math.max(1.0, buyPrice + sellPrice);
		double clearable = Math.min(fiveMinutes, Math.min(flowIntoBids, flowFromAsks) * maxFillDays);
		double holdable = ProfileState.capacity(product.id());
		// Order no more than twice what stands at the best price on each side. Half the whole thirty-level
		// book is thousands of units that will never clear without relisting forever.
		double bookCap = Math.max(1.0,
				Math.min(product.quantityAtBid(), product.quantityAtAsk()) * TOP_LEVEL_SHARE);

		// At least one unit: a slow mover with a wide margin is still worth one order, and dropping it
		// entirely is how high-value enchantments used to go missing.
		long units = Math.max(1L, (long) Math.floor(
				Math.min(Math.min(affordable, holdable), Math.min(clearable, Math.min(bookCap, coinCap)))));

		if (units < MIN_UNITS_HIGH_MARGIN && margin / buyPrice >= 0.10
				&& Math.min(flowIntoBids, flowFromAsks) >= MIN_UNITS_HIGH_MARGIN) {
			units = MIN_UNITS_HIGH_MARGIN;
		}

		units = Math.min(units, product.id().startsWith("ESSENCE_") ? MAX_ESSENCE_UNITS : MAX_ORDER_UNITS);

		// Fill time is the queue ahead plus our units at the rate the market trades, plus the fixed cost
		// of clicking the cycle. Q units need Q/rate hours — nothing is halved.
		double perHourBid = flowIntoBids / 24.0;
		double perHourAsk = flowFromAsks / 24.0;
		double queueBid = queued(product.bids(), buyPrice, true);
		double queueAsk = queued(product.asks(), sellPrice, false);
		double hoursBid = (queueBid + units) / perHourBid;
		double hoursAsk = (queueAsk + units) / perHourAsk;
		double hours = Math.max(MIN_CYCLE_HOURS, hoursBid + hoursAsk + CYCLE_OVERHEAD_HOURS);

		// Being undercut costs daily limit, not coins, so it never changes which flip is best. Each side
		// has its own crowd: buy orders compete with our bid, sell offers with our ask. These are full
		// counts now, not a capped estimate, because every one of them is a fresh charge to the limit.
		int relistBuy = (int) Math.min(MAX_RELISTS, Math.floor(hoursBid / undercutBid));
		int relistSell = (int) Math.min(MAX_RELISTS, Math.floor(hoursAsk / undercutAsk));

		double profitPerCycle = units * margin;
		double cyclesPerDay = 24.0 / hours;
		double profitPerDay = profitPerCycle * cyclesPerDay;
		double profitPerHour = profitPerCycle / hours;
		// Every placement is charged: the buy order, each of its relists, the sell offer, each of its
		// relists. All of that is paid again on the next cycle, for the same item, all day.
		double limitPerCycle = units * (buyPrice * (1 + relistBuy) + sellPrice * (1 + relistSell));
		double limitPerDay = limitPerCycle * cyclesPerDay;
		double efficiency = limitPerCycle > 0.0 ? profitPerCycle / limitPerCycle : 0.0;

		// Every coin of limit has to pay for itself. This is what removes the "50m book, 1m profit" flips:
		// they return under 2% of the cap they consume, however healthy the spread looks.
		if (efficiency < MIN_LIMIT_EFFICIENCY) {
			return null;
		}

		long dailyVolume = Math.round(Math.min(flowIntoBids, flowFromAsks));

		// Competition on the exit side: how many offers stand between us and the buyers, per unit that
		// actually sells per day. Ultimate Chimera I has 229 offers for 855 units/day and gets undercut
		// constantly; Hunk of Blue Ice has 43 offers for 13,904 units/day and holds the front.
		// Competition is already inside the fill time: hours = (queueAhead + units) / flow. Multiplying by
		// an order-count discount as well counted it twice and buried good books like Perfect Topaz.
		double confidence = 1.0;

		// The cap is not a penalty. Burning 15B in three hours and stopping is the point: a flip that
		// would exhaust the day in 40B simply gets you there sooner. What is reported instead is how many
		// hours the cap lasts at this rate (see Flip.capHours), so the session can be planned around it.

		// Ranking is profit per hour discounted by one thing only: competition, i.e. whether the order
		// will actually fill. Limit usage is not a multiplier, and neither is the spread: wide books like
		// Smoldering VI (204%) are exactly the ones worth taking. A spread so absurd it cannot be real is
		// rejected outright instead of being quietly discounted.
		if ((ask - bid) / bid > MAX_SPREAD) {
			return null;
		}

		double realisation = confidence;
		double worth = worth(profitPerHour * realisation, efficiency);

		return new Flip(product.id(), ItemNames.name(product.id()), buyPrice, sellPrice, margin,
				margin / buyPrice, (int) units, profitPerCycle, profitPerHour, profitPerDay,
				limitPerCycle, limitPerDay, efficiency, hours, dailyVolume, Math.max(1, product.sellOrders()),
				relistBuy, relistSell, realisation, worth);
	}

	/**
	 * Displayed quality score, 0..1. Profit per hour carries 90% of it; the limit efficiency that gates
	 * the list carries the rest, so the number drops for flips that earn the same while spending more
	 * of the cap. Ranking does not use this — it sorts on profit per hour directly.
	 */
	private static double worth(double profitPerHour, double efficiency) {
		double hourly = saturate(profitPerHour / HOURLY_TARGET);
		double efficient = saturate(efficiency / 0.10);
		return 0.90 * hourly + 0.10 * efficient;
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

	/** Units offered across every level of one side of the book. */
	private static double bookDepth(List<OrderLevel> levels) {
		double total = 0.0;

		for (OrderLevel level : levels) {
			total += level.amount();
		}

		return total;
	}

	private static double saturate(double value) {
		return value <= 0.0 ? 0.0 : value / (1.0 + value);
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
