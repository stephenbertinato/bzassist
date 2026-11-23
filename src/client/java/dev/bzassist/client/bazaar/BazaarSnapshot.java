package dev.bzassist.client.bazaar;

import java.util.Map;

/**
 * The whole bazaar at one instant. Immutable, so it can be swapped in from the network thread and read
 * from the render thread without locking.
 *
 * @param lastUpdated when Hypixel produced this data (epoch millis), not when we fetched it
 * @param fetchedAt   when we received it (epoch millis)
 */
public record BazaarSnapshot(long lastUpdated, long fetchedAt, Map<String, BazaarProduct> products) {
	public static final BazaarSnapshot EMPTY = new BazaarSnapshot(0L, 0L, Map.of());

	public BazaarSnapshot {
		products = Map.copyOf(products);
	}

	public static BazaarSnapshot empty() {
		return EMPTY;
	}

	public BazaarProduct get(String id) {
		return id == null ? null : products.get(id);
	}

	public boolean isEmpty() {
		return products.isEmpty();
	}

	public int size() {
		return products.size();
	}

	/** How long ago Hypixel produced this data. */
	public long ageMillis() {
		return lastUpdated == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - lastUpdated;
	}
}
