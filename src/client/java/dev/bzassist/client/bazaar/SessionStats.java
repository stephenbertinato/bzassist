package dev.bzassist.client.bazaar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one session actually did, for the summary screen.
 *
 * <p>Fed by the confirm screens the guide already reads: a buy order adds to a product's spend, a sell
 * offer adds to its earnings and realises the profit after tax against the average cost of the units
 * bought. Profit is also posted to {@link ProfileState#addProfit(double)}, so the HUD's session figure
 * and this summary always agree. A cumulative-profit series is sampled every five seconds to draw the
 * chart.</p>
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
		private double spent;
		private double earned;

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

		public double profit() {
			return earned - spent;
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
	private static int buys;
	private static int sells;
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
		buys = 0;
		sells = 0;
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
	 * One order the guide confirmed. {@code total} is the order value from the confirm screen; a sell is
	 * netted against the average cost of the units bought and its profit is posted to the session.
	 */
	public static void order(String id, String name, boolean buy, int units, double total) {
		if (id == null || total <= 0.0) {
			return;
		}

		Product product = PRODUCTS.computeIfAbsent(id, key -> new Product(key, name == null ? key : name));
		int quantity = Math.max(1, units);

		if (buy) {
			buys++;
			product.boughtOrders++;
			product.boughtUnits += quantity;
			product.spent += total;
			spent += total;
		} else {
			double revenue = total * (1.0 - BazaarConfig.taxFraction());
			double cost = Math.min(product.boughtUnits, quantity) * product.unitCost();
			double gain = revenue - cost;
			sells++;
			product.soldOrders++;
			product.soldUnits += quantity;
			product.earned += revenue;
			profit += gain;

			if (gain > 0.0) {
				profitableSells++;
			}

			ProfileState.addProfit(gain);
		}

		lastSample = 0L;
		sample();
	}

	/** Fills the summary with the reference figures so the screen can be checked in game. */
	public static void demo() {
		begin();
		demo = true;
		startedAt = System.currentTimeMillis() - 8_040_000L;
		endedAt = System.currentTimeMillis();
		spent = 1_918_000_000.0;
		profit = 245_700_000.0;
		buys = 184;
		sells = 186;
		profitableSells = 183;

		Product best = new Product("NECRON_HANDLE", "Necron's Blade");
		best.soldOrders = 3;
		best.soldUnits = 3;
		best.earned = 48_700_000.0;
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
		return sells > 0 ? profitableSells / (double) sells : 0.0;
	}

	public static double netMargin() {
		return spent > 0.0 ? profit / spent : 0.0;
	}

	public static double averageProfitPerSale() {
		return sells > 0 ? profit / sells : 0.0;
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
