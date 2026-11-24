package dev.bzassist.client.bazaar;

/**
 * Everything the pure optimizer needs from the player and settings, captured once per ranking pass.
 *
 * <p>The engine never reads static client state. That makes a pass internally consistent and lets a
 * snapshot be ranked exactly the same way in a test.</p>
 */
public record OptimizerContext(
		double taxFraction,
		boolean purseKnown,
		long purse,
		int freeSlots,
		double targetFillHours,
		double maxFillHours) {

	/** Captures the current client settings and player state. */
	public static OptimizerContext capture() {
		return new OptimizerContext(
				BazaarConfig.taxFraction(),
				PlayerState.purseKnown(),
				PlayerState.purse(),
				PlayerState.freeSlots(),
				BazaarConfig.targetFillHours(),
				BazaarConfig.maxFillHours());
	}

	/** The purse used for sizing. An unknown purse falls back to a deliberately small budget. */
	public double budget() {
		return purseKnown ? Math.max(0L, purse) : 1_000_000.0;
	}

	/** Units the player can take delivery of. Essence always lands; everything else needs a slot. */
	public long capacity(String id) {
		if (id != null && id.startsWith("ESSENCE_")) {
			return 10_000_000L;
		}

		return (long) Math.max(0, freeSlots) * 64L;
	}
}
