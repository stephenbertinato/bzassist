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
 * <p>The optimizer has no live GUI to read tax from any more, so the tax rate is a setting. Target
 * fill time is the main sizing knob; the hard maximum only rejects orders that the book makes
 * implausibly slow.</p>
 */
public final class BazaarConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bzassist.properties");
	private static boolean loaded;
	private static int pollIntervalMs = 20_000;
	private static double taxPercent = 1.25;
	private static double targetFillHours = 1.0;
	private static double maxFillHours = 6.0;
	private static boolean overlay = true;
	private static int panelX = -1;
	private static int panelY = -1;

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

	/** Target fill time for the whole buy-plus-sell cycle, in hours. */
	public static double targetFillHours() {
		load();
		return targetFillHours;
	}

	/** Orders expected to take longer than this are rejected instead of recommended. */
	public static double maxFillHours() {
		load();
		return maxFillHours;
	}

	public static boolean overlay() {
		load();
		return overlay;
	}

	public static void setOverlay(boolean enabled) {
		load();
		overlay = enabled;
		save();
	}

	/** Saved panel left edge in GUI pixels, or -1 for the automatic position. */
	public static int panelX() {
		load();
		return panelX;
	}

	/** Saved panel top edge in GUI pixels, or -1 for the automatic position. */
	public static int panelY() {
		load();
		return panelY;
	}

	public static void setPanelPosition(float x, float y) {
		load();
		panelX = Math.round(x);
		panelY = Math.round(y);
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
		properties.setProperty("target_fill_hours", Double.toString(targetFillHours));
		properties.setProperty("max_fill_hours", Double.toString(maxFillHours));
		properties.setProperty("overlay", Boolean.toString(overlay));
		properties.setProperty("panel_x", Integer.toString(panelX));
		properties.setProperty("panel_y", Integer.toString(panelY));
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

		pollIntervalMs = (int) clamp(number(properties, "poll_interval_ms", pollIntervalMs), 5_000.0, 600_000.0);
		taxPercent = clamp(number(properties, "tax_percent", taxPercent), 0.0, 50.0);
		targetFillHours = clamp(number(properties, "target_fill_hours", targetFillHours), 0.25, 72.0);
		maxFillHours = clamp(number(properties, "max_fill_hours", maxFillHours), 0.25, 720.0);
		maxFillHours = Math.max(maxFillHours, targetFillHours);
		overlay = Boolean.parseBoolean(properties.getProperty("overlay", Boolean.toString(overlay)));
		panelX = (int) Math.round(clamp(number(properties, "panel_x", panelX), -1.0, 100_000.0));
		panelY = (int) Math.round(clamp(number(properties, "panel_y", panelY), -1.0, 100_000.0));
		save();
	}

	private static void write(Properties properties) {
		try {
			Files.createDirectories(FILE.getParent());
			try (OutputStream out = Files.newOutputStream(FILE)) {
				properties.store(out, """
						BzAssist optimizer settings.
						poll_interval_ms     how often to fetch while the Bazaar is open (API changes ~20s).
						tax_percent          bazaar sell tax: 1.25 default, 1.125 at Bazaar Flipper I, 1.0 at II.
						target_fill_hours    expected fill time for the whole buy-plus-sell cycle.
						max_fill_hours       reject orders expected to take longer than this.
						overlay              show the top-20 panel while the Bazaar is open.
						panel_x, panel_y     remembered panel top-left in GUI pixels (-1 = automatic).
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
