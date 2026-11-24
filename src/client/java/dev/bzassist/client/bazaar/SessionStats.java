package dev.bzassist.client.bazaar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * What one session actually did, for the summary screen.
 *
 * <p>Fed by the chat lines Hypixel prints when an order is really created ({@code Buy Order Setup!} /
 * {@code Sell Offer Setup!}) and when a sale is claimed ({@code Claimed … from selling …}): a buy order
 * adds to a product's spend, a claim realises the profit after tax against the average cost of the
 * units bought. A confirm screen that was opened and closed is not counted. Profit is also posted to
 * {@link ProfileState#addProfit(double)}, so the HUD's session figure and this summary agree. A claim
 * whose units cannot be matched to bought units (stock bought before the session, or no buy order at
 * all) is kept as revenue with no cost basis: it still counts in Total Revenue and is reported, but it
 * never adds to profit. A cumulative-profit series is sampled every five seconds to draw the chart.</p>
 */
public final class SessionStats {
	private static final long SAMPLE_MS = 5_000L;

	public record Point(long elapsedMs, double profit) {
	}

	/** One traded product: what it cost, what it earned, and how many orders went each way. */
	public static final class Product {
		private final String id;
		private final String name;
		private int boughtOrders;
		private int soldOrders;
		private int boughtUnits;
		private int soldUnits;
		/** Bought units whose cost has already been matched to a claim, and the cost of those units. */
		private int costedUnits;
		private double costedSpend;
		private double spent;
		/** Total coins claimed for this product, and how much of that had a known cost basis. */
		private double earned;
		private double pricedRevenue;
		/** Claimed coins with no cost basis in this session; visible, but never profit. */
		private double unpricedRevenue;

		private Product(String id, String name) {
			this.id = id;
			this.name = name;
		}

		public String id() {
			return id;
		}

		public String name() {
			return name;
		}

		public int boughtOrders() {
			return boughtOrders;
		}

		public int soldOrders() {
			return soldOrders;
		}

		/** Realised profit: only revenue with a known cost basis counts. */
		public double profit() {
			return pricedRevenue - spent;
		}

		/** Total coins claimed for this product, priced or not. */
		public double revenue() {
			return earned;
		}

		/** Claimed coins that had no cost basis in this session. */
		public double unpricedRevenue() {
			return unpricedRevenue;
		}

		public double unitCost() {
			return boughtUnits > 0 ? spent / boughtUnits : 0.0;
		}
	}

	private static final Map<String, Product> PRODUCTS = new LinkedHashMap<>();
	private static final List<Point> SERIES = new ArrayList<>();
	private static long startedAt;
	private static long endedAt;
	private static long lastSample;
	private static double profit;
	private static double spent;
	private static double revenue;
	private static double unpricedRevenue;
	private static int buys;
	private static int sells;
	private static int pricedSells;
	private static int profitableSells;
	private static boolean demo;

	private SessionStats() {
	}

	public static void begin() {
		PRODUCTS.clear();
		SERIES.clear();
		startedAt = System.currentTimeMillis();
		endedAt = 0L;
		lastSample = startedAt;
		profit = 0.0;
		spent = 0.0;
		revenue = 0.0;
		unpricedRevenue = 0.0;
		buys = 0;
		sells = 0;
		pricedSells = 0;
		profitableSells = 0;
		demo = false;
		SERIES.add(new Point(0L, 0.0));
	}

	public static void finish() {
		if (endedAt > 0L) {
			return;
		}

		endedAt = System.currentTimeMillis();
		SERIES.add(new Point(elapsed(), profit));
	}

	/** Called on the session's five-second tick. */
	public static void sample() {
		if (startedAt <= 0L) {
			return;
		}

		long now = System.currentTimeMillis();

		if (now - lastSample < SAMPLE_MS) {
			return;
		}

		lastSample = now;
		SERIES.add(new Point(elapsed(), profit));
	}

	/**
	 * One order Hypixel confirmed in chat. {@code total} is the figure from that line; a sell is netted
	 * against the average cost of the units bought and its profit is posted when the coins are claimed.
	 */
	public static void order(String id, String name, boolean buy, int units, double total) {
		if (total <= 0.0 || startedAt <= 0L || endedAt > 0L) {
			return;
		}

		String key = id != null && !id.isBlank() ? id : name;

		if (key == null || key.isBlank()) {
			return;
		}

		Product product = PRODUCTS.computeIfAbsent(key, ignored -> new Product(key, name == null ? key : name));
		int quantity = Math.max(1, units);

		if (buy) {
			buys++;
			product.boughtOrders++;
			product.boughtUnits += quantity;
			product.spent += total;
			spent += total;
		} else {
			// Listing earns nothing yet. The money, and therefore the profit, arrives when it is claimed.
			product.soldOrders++;
		}

		lastSample = 0L;
		sample();
	}

	/**
	 * A sale that actually happened: the chat line that says coins arrived
	 * ({@code Claimed 113.8 coins from selling 64x Cobblestone at 1.8 each!}).
	 *
	 * <p>The revenue is split by units into the part that has a cost basis this session (bought units
	 * not already matched to an earlier claim) and the part that does not (bought before the session, or
	 * no buy order recorded at all). Only the priced part can produce profit; the unpriced part is added
	 * to {@link #unpricedRevenue()} and shown in the report and the summary, never as a gain. Counting
	 * the whole claim as profit was the old behaviour and made a sale from existing stock look like pure
	 * earnings.</p>
	 *
	 * <p>The product is found by id first and by the printed name second, so a listing made in an earlier
	 * session still lands on the right row.</p>
	 */
	public static void claim(String id, String name, int units, double coins) {
		if (coins <= 0.0 || startedAt <= 0L || endedAt > 0L) {
			return;
		}

		Product product = product(id, name);
		int claimed = Math.max(0, units);
		int pricedUnits = 0;
		double pricedRevenue = 0.0;
		double cost = 0.0;

		if (product != null && claimed > 0) {
			int available = Math.max(0, product.boughtUnits - product.costedUnits);
			pricedUnits = Math.min(claimed, available);
			// Use the cost of the units still unmatched, not the average of every unit ever bought:
			// a later, more expensive buy must not re-price stock that was already accounted for.
			double remainingCost = Math.max(0.0, product.spent - product.costedSpend);
			double unitCost = available > 0 ? remainingCost / available : 0.0;
			pricedRevenue = coins * pricedUnits / claimed;
			cost = pricedUnits * unitCost;
			product.costedUnits += pricedUnits;
			product.costedSpend += cost;
		}

		double unpriced = coins - pricedRevenue;
		double gain = pricedRevenue - cost;

		if (product != null) {
			product.soldUnits += claimed;
			product.earned += coins;
			product.pricedRevenue += pricedRevenue;
			product.unpricedRevenue += unpriced;
		}

		sells++;
		revenue += coins;
		unpricedRevenue += unpriced;
		profit += gain;

		if (pricedUnits > 0) {
			pricedSells++;
		}

		if (gain > 0.0) {
			profitableSells++;
		}

		ProfileState.addProfit(gain);
		SessionLog.event("claim", String.format(Locale.ROOT, "%s%s for %s → profit %s%s",
				units > 0 ? units + "x " : "",
				product == null ? (name == null ? "item" : name) : product.name(),
				Coins.compact(coins), Coins.compact(gain),
				unpriced > 0.0 ? " · " + Coins.compact(unpriced) + " revenue with no cost basis" : ""));
		lastSample = 0L;
		sample();
	}

	/** The traded product a claim is about: by id first, then by the display name in the chat line. */
	private static Product product(String id, String name) {
		if (id != null) {
			Product byId = PRODUCTS.get(id);

			if (byId != null) {
				return byId;
			}
		}

		if (name == null || name.isBlank()) {
			return null;
		}

		String key = name.toLowerCase(Locale.ROOT);

		for (Product product : PRODUCTS.values()) {
			if (!product.name().isBlank() && product.name().toLowerCase(Locale.ROOT).contains(key)) {
				return product;
			}
		}

		for (Product product : PRODUCTS.values()) {
			if (!product.name().isBlank() && key.contains(product.name().toLowerCase(Locale.ROOT))) {
				return product;
			}
		}

		return null;
	}

	/** Fills the summary with the reference figures so the screen can be checked in game. */
	public static void demo() {
		begin();
		demo = true;
		startedAt = System.currentTimeMillis() - 8_040_000L;
		endedAt = System.currentTimeMillis();
		spent = 1_918_000_000.0;
		profit = 245_700_000.0;
		revenue = 2_163_700_000.0;
		buys = 184;
		sells = 186;
		pricedSells = 186;
		profitableSells = 183;

		Product best = new Product("NECRON_HANDLE", "Necron's Blade");
		best.soldOrders = 3;
		best.soldUnits = 3;
		best.earned = 48_700_000.0;
		best.pricedRevenue = 48_700_000.0;
		best.spent = 0.0;
		PRODUCTS.put(best.id(), best);

		Product worst = new Product("ENCHANTMENT_ULTIMATE_WISE_5", "Enchanted Book");
		worst.boughtOrders = 7;
		worst.boughtUnits = 7;
		worst.spent = 12_100_000.0;
		worst.earned = 0.0;
		PRODUCTS.put(worst.id(), worst);

		SERIES.clear();
		SERIES.add(new Point(0L, 0.0));
		double[] shape = {0, 6, 18, 26, 44, 62, 74, 96, 88, 104, 122, 148, 132, 118, 138, 156, 172, 164, 152,
				170, 188, 182, 196, 214, 208, 200, 218, 232, 226, 238, 232, 246, 240, 252, 244, 238, 250, 262,
				255, 245.7};
		long step = 8_040_000L / (shape.length - 1);

		for (int i = 0; i < shape.length; i++) {
			SERIES.add(new Point(step * i, shape[i] * 1_000_000.0));
		}
	}

	public static boolean demoActive() {
		return demo;
	}

	public static boolean hasData() {
		return buys + sells > 0;
	}

	public static long elapsed() {
		long end = endedAt > 0L ? endedAt : System.currentTimeMillis();
		return Math.max(0L, end - startedAt);
	}

	public static long elapsedMs() {
		long end = endedAt > 0L ? endedAt : System.currentTimeMillis();
		return Math.max(1L, end - startedAt);
	}

	public static double profit() {
		return profit;
	}

	/** All coins claimed in the session, including sales with no cost basis. */
	public static double revenue() {
		return revenue;
	}

	/** Claimed coins that could not be matched to a cost basis; never part of {@link #profit()}. */
	public static double unpricedRevenue() {
		return unpricedRevenue;
	}

	public static double spent() {
		return spent;
	}

	public static int buys() {
		return buys;
	}

	public static int sells() {
		return sells;
	}

	public static int flips() {
		return buys + sells;
	}

	public static double successRate() {
		return pricedSells > 0 ? profitableSells / (double) pricedSells : 0.0;
	}

	public static double netMargin() {
		return spent > 0.0 ? profit / spent : 0.0;
	}

	public static double averageProfitPerSale() {
		return pricedSells > 0 ? profit / pricedSells : 0.0;
	}

	public static List<Point> series() {
		return SERIES;
	}

	public static List<Product> products() {
		return new ArrayList<>(PRODUCTS.values());
	}

	/** The product that made the most, or null. */
	public static Product best() {
		Product best = null;

		for (Product product : PRODUCTS.values()) {
			if (product.profit() > 0.0 && (best == null || product.profit() > best.profit())) {
				best = product;
			}
		}

		return best;
	}

	/** The product that lost the most, or null. */
	public static Product worst() {
		Product worst = null;

		for (Product product : PRODUCTS.values()) {
			if (product.profit() < 0.0 && (worst == null || product.profit() < worst.profit())) {
				worst = product;
			}
		}

		return worst;
	}
}
