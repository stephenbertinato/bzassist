package dev.bzassist.client.bazaar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import dev.bzassist.BzAssist;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Settings in {@code config/bzassist.properties}.
 *
 * <p>Several mirror things the game knows but never exposes to the API, so they are read from the GUI
 * while it is open (see {@code BazaarScreenReader}); the values here are the fallbacks. A fresh install
 * therefore still produces sane numbers before the player has opened the bazaar.</p>
 */
public final class BazaarConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bzassist.properties");
	private static boolean loaded;
	private static int pollIntervalMs = 5000;
	private static double taxPercent = 1.25;
	private static int orderSlots = 14;
	private static double maxFillHours = 72.0;
	private static boolean cacheBuster;
	private static boolean overlay = true;
	private static boolean debug;

	private BazaarConfig() {
	}

	public static int pollIntervalMs() {
		load();
		return pollIntervalMs;
	}

	/** Bazaar sales tax in percent: 1.25 without Bazaar Flipper, 1.125 at I, 1.0 at II. */
	public static double taxPercent() {
		load();
		return taxPercent;
	}

	public static double taxFraction() {
		return taxPercent() / 100.0;
	}

	/** Simultaneous buy/sell orders: 14, 21 (Flipper I) or 28 (Flipper II). */
	public static int orderSlots() {
		load();
		return orderSlots;
	}

	/** Orders expected to take longer than this to fill are not sized up into. */
	public static double maxFillHours() {
		load();
		return maxFillHours;
	}

	public static boolean cacheBuster() {
		load();
		return cacheBuster;
	}

	public static boolean overlay() {
		load();
		return overlay;
	}

	public static boolean debug() {
		load();
		return debug;
	}

	public static void setOverlay(boolean enabled) {
		load();
		overlay = enabled;
		save();
	}

	public static void setDebug(boolean enabled) {
		load();
		debug = enabled;
		save();
	}

	/** Called by the screen reader when the GUI prints the real tax rate. */
	public static void setTaxPercent(double percent) {
		load();

		if (percent <= 0.0 || Math.abs(percent - taxPercent) < 1.0E-4) {
			return;
		}

		taxPercent = percent;
		BzAssist.LOGGER.info("Bazaar tax read from the GUI: {}%", percent);
		save();
	}

	/** Called by the screen reader when the order list prints "used/max". */
	public static void setOrderSlots(int slots) {
		load();

		if (slots <= 0 || slots > 64 || slots == orderSlots) {
			return;
		}

		orderSlots = slots;
		BzAssist.LOGGER.info("Bazaar order slots read from the GUI: {}", slots);
		save();
	}

	public static synchronized void reload() {
		loaded = false;
		load();
	}

	private static void save() {
		Properties properties = new Properties();
		properties.setProperty("poll_interval_ms", Integer.toString(pollIntervalMs));
		properties.setProperty("tax_percent", Double.toString(taxPercent));
		properties.setProperty("order_slots", Integer.toString(orderSlots));
		properties.setProperty("max_fill_hours", Double.toString(maxFillHours));
		properties.setProperty("cache_buster", Boolean.toString(cacheBuster));
		properties.setProperty("overlay", Boolean.toString(overlay));
		properties.setProperty("debug", Boolean.toString(debug));
		write(properties);
	}

	private static synchronized void load() {
		if (loaded) {
			return;
		}

		loaded = true;
		Properties properties = new Properties();

		if (Files.exists(FILE)) {
			try (InputStream in = Files.newInputStream(FILE)) {
				properties.load(in);
			} catch (IOException e) {
				BzAssist.LOGGER.warn("Could not read {}: {}", FILE, e.toString());
			}
		}

		pollIntervalMs = (int) clamp(number(properties, "poll_interval_ms", pollIntervalMs), 1_000.0, 600_000.0);
		taxPercent = clamp(number(properties, "tax_percent", taxPercent), 0.0, 100.0);
		orderSlots = (int) clamp(number(properties, "order_slots", orderSlots), 1.0, 64.0);
		maxFillHours = clamp(number(properties, "max_fill_hours", maxFillHours), 0.25, 720.0);
		cacheBuster = Boolean.parseBoolean(properties.getProperty("cache_buster", Boolean.toString(cacheBuster)));
		overlay = Boolean.parseBoolean(properties.getProperty("overlay", Boolean.toString(overlay)));
		debug = Boolean.parseBoolean(properties.getProperty("debug", Boolean.toString(debug)));
		save();
	}

	private static void write(Properties properties) {
		try {
			Files.createDirectories(FILE.getParent());
			try (OutputStream out = Files.newOutputStream(FILE)) {
				properties.store(out, """
						BzAssist settings.
						poll_interval_ms     how often to fetch the bazaar; the API itself changes every ~20s.
						tax_percent          bazaar sell tax: 1.25 default, 1.125 at Bazaar Flipper I, 1.0 at II.
						order_slots          simultaneous buy/sell orders: 14 default, 21 at Flipper I, 28 at II.
						max_fill_hours       orders expected to fill slower than this are not sized into.
						cache_buster         add a changing query parameter to defeat CDN caching.
						overlay              show the assist HUD and the single-button highlight.
						debug                write every bazaar screen you open to config/bzassist/debug.jsonl.
						""");
			}
		} catch (IOException e) {
			BzAssist.LOGGER.warn("Could not write {}: {}", FILE, e.toString());
		}
	}

	private static double number(Properties properties, String key, double fallback) {
		try {
			return Double.parseDouble(properties.getProperty(key, Double.toString(fallback)).trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
