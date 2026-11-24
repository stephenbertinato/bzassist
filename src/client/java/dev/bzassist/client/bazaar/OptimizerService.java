package dev.bzassist.client.bazaar;

import java.util.List;

import dev.bzassist.BzAssist;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Owns the cached optimizer result.
 *
 * <p>Ranking runs on the bazaar worker thread, never on the render thread. The overlay only reads the
 * immutable list published here. A pass is rerun when the API snapshot, the purse/inventory context or
 * the configured sizing window changes.</p>
 */
public final class OptimizerService {
	public static final int TOP_COUNT = 20;

	private static volatile List<Flip> top = List.of();
	private static volatile long rankedAt;
	private static volatile long rankedSnapshot = Long.MIN_VALUE;
	private static volatile OptimizerContext rankedContext;
	private static volatile String error = "Waiting for bazaar data";
	private static volatile boolean computing;
	private static volatile boolean force;
	private static volatile long lastScheduledAt;

	private OptimizerService() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	/** The current top flips. The returned list is immutable and is replaced, never mutated. */
	public static List<Flip> top() {
		return top;
	}

	public static List<Flip> top(int count) {
		int size = Math.max(0, Math.min(count, top.size()));
		return List.copyOf(top.subList(0, size));
	}

	/** When the current list was computed, in epoch millis. */
	public static long rankedAt() {
		return rankedAt;
	}

	/** Empty while a list is available; a human-readable reason otherwise. */
	public static String error() {
		return error;
	}

	/** Ask for a fresh pass even if the snapshot and context look unchanged. */
	public static void requestRefresh() {
		force = true;
		lastScheduledAt = 0L;
	}

	private static void tick() {
		if (!BazaarScreenTracker.isOpen()) {
			return;
		}

		BazaarSnapshot snapshot = BazaarApi.snapshot();
		OptimizerContext context = OptimizerContext.capture();
		boolean stale = force
				|| snapshot.lastUpdated() != rankedSnapshot
				|| !context.equals(rankedContext);

		if (!stale || computing) {
			return;
		}

		long now = System.currentTimeMillis();

		if (now - lastScheduledAt < 500L) {
			return;
		}

		lastScheduledAt = now;
		force = false;
		computing = true;

		BazaarApi.executor().execute(() -> {
			try {
				List<Flip> result = FlipEngine.rank(snapshot, context, TOP_COUNT);
				top = List.copyOf(result);
				rankedSnapshot = snapshot.lastUpdated();
				rankedContext = context;
				rankedAt = System.currentTimeMillis();
				error = result.isEmpty()
						? (snapshot.isEmpty() ? "Waiting for bazaar data" : "No positive-margin flips right now")
						: "";
			} catch (RuntimeException exception) {
				error = "Ranking failed: " + exception.getMessage();
				BzAssist.LOGGER.warn("Optimizer ranking failed", exception);
			} finally {
				computing = false;
			}
		});
	}
}
