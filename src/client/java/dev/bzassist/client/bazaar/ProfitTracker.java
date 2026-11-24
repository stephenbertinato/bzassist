package dev.bzassist.client.bazaar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.bzassist.BzAssist;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

/**
 * A cumulative, persistent record of profit actually paid out by bazaar sales.
 *
 * <p>It uses the same confirmation lines as the old session stats: a confirmed buy order adds to a
 * product's cost basis and a claim from selling realizes profit against it. A sell offer itself earns
 * nothing yet, so it changes no figure. The optimizer's prices and estimates are not involved.</p>
 *
 * <p>A sale is only counted when the units can be matched to a buy the tracker saw (or to a
 * {@code Claimed ... bought for ...} line). Coins from stock bought before the tracker existed are
 * ignored rather than being reported as pure profit. Only items still waiting to be sold are kept on
 * disk, so the total survives a restart without turning into a session-limited stat.</p>
 */
public final class ProfitTracker {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("bzassist").resolve("profit.tsv");
	private static final Pattern ORDER_SETUP = Pattern.compile(
			"(?i)\\[bazaar]\\s+(buy order|sell offer)\\s+setup!\\s+([\\d,]+)\\s*x\\s+(.+?)\\s+for\\s+"
					+ "([\\d,]+(?:\\.\\d+)?)\\s*coins?\\.?\\s*$");
	private static final Pattern CLAIM = Pattern.compile(
			"(?i)\\[bazaar]\\s+claimed\\s+([\\d,]+(?:\\.\\d+)?)\\s*coins?\\s+from\\s+selling\\s+"
					+ "([\\d,]+)\\s*x\\s+(.+?)(?:\\s+at\\s+([\\d,]+(?:\\.\\d+)?)(?:\\s*coins?)?\\s*each)?[.!]?\\s*$");
	private static final Pattern ITEMS_CLAIMED = Pattern.compile(
			"(?i)\\[bazaar]\\s+claimed\\s+([\\d,]+)\\s*x\\s+(.+?)"
					+ "(?:\\s*,?\\s+worth\\s+([\\d,]+(?:\\.\\d+)?)\\s*(?:coins?)?)?"
					+ "(?:\\s*,?\\s+bought\\s+for\\s+([\\d,]+(?:\\.\\d+)?)\\s*(?:coins?)?)?[.!]?\\s*$");

	private static final Map<String, Basis> BASIS = new LinkedHashMap<>();
	private static volatile double total;
	private static boolean loaded;

	private ProfitTracker() {
	}

	public static void register() {
		load();
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				note(text(message));
			}
		});
	}

	/** The running total, in coins. */
	public static double total() {
		return total;
	}

	/**
	 * Zeroes the displayed total but deliberately keeps the cost basis of open buys, so a sale after
	 * the reset is still priced correctly.
	 *
	 * @return the total before the reset
	 */
	public static synchronized double reset() {
		load();
		double previous = total;
		total = 0.0;
		save();
		return previous;
	}

	/** Feeds one system chat line through the bazaar confirmations. Anything else is ignored. */
	static synchronized void note(String line) {
		if (line == null || line.isBlank()) {
			return;
		}

		String text = ItemNames.strip(line).trim();
		Matcher order = ORDER_SETUP.matcher(text);

		if (order.find()) {
			boolean sellSide = order.group(1).toLowerCase(Locale.ROOT).startsWith("sell");
			int units = whole(order.group(2));
			String name = order.group(3).trim();
			double coins = Coins.parse(order.group(4));

			if (!sellSide && units > 0 && coins > 0.0 && !name.isEmpty()) {
				Basis basis = BASIS.computeIfAbsent(key(name), ignored -> new Basis());
				basis.boughtUnits += units;
				basis.spent += coins;
				save();
			}

			return;
		}

		Matcher claim = CLAIM.matcher(text);

		if (claim.find()) {
			double coins = Coins.parse(claim.group(1));
			int units = whole(claim.group(2));
			String name = claim.group(3).trim();

			if (coins > 0.0 && units > 0 && !name.isEmpty()) {
				claim(key(name), units, coins);
			}

			return;
		}

		Matcher items = ITEMS_CLAIMED.matcher(text);

		if (items.find()) {
			int units = whole(items.group(1));
			String name = items.group(2).trim();
			double paid = items.group(4) == null ? 0.0 : Coins.parse(items.group(4));

			if (units > 0 && paid > 0.0 && !name.isEmpty()) {
				claimItems(key(name), units, paid);
			}
		}
	}

	private static void claim(String key, int units, double coins) {
		Basis basis = BASIS.get(key);

		if (basis == null) {
			return;
		}

		int available = Math.max(0, basis.boughtUnits - basis.costedUnits);
		int pricedUnits = Math.min(units, available);

		if (pricedUnits <= 0) {
			prune(key, basis);
			return;
		}

		double remainingCost = Math.max(0.0, basis.spent - basis.costedSpend);
		double cost = pricedUnits >= available
				? remainingCost
				: remainingCost * pricedUnits / available;
		double gain = coins * pricedUnits / units - cost;

		if (!Double.isFinite(gain)) {
			return;
		}

		basis.costedUnits += pricedUnits;
		basis.costedSpend += cost;
		total += gain;
		prune(key, basis);
		save();
	}

	private static void claimItems(String key, int units, double paid) {
		Basis basis = BASIS.computeIfAbsent(key, ignored -> new Basis());
		int unmatched = Math.max(0, basis.boughtUnits - basis.costedUnits);
		int missing = Math.max(0, units - unmatched);

		if (missing <= 0) {
			return;
		}

		double cost = paid * missing / units;
		basis.boughtUnits += missing;
		basis.spent += cost;
		save();
	}

	private static void prune(String key, Basis basis) {
		if (basis.boughtUnits <= basis.costedUnits) {
			BASIS.remove(key);
		}
	}

	private static String key(String name) {
		return ItemNames.strip(name).trim().toLowerCase(Locale.ROOT);
	}

	private static int whole(String text) {
		try {
			return Integer.parseInt(text.replace(",", "").trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String text(Component message) {
		try {
			return message == null ? "" : message.getString();
		} catch (RuntimeException exception) {
			return "";
		}
	}

	private static synchronized void load() {
		if (loaded) {
			return;
		}

		loaded = true;

		if (!Files.exists(FILE)) {
			return;
		}

		try {
			for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
				String[] parts = line.split("\t", -1);

				if (parts.length >= 2 && parts[0].equals("total")) {
					double value = Double.parseDouble(parts[1]);

					if (Double.isFinite(value)) {
						total = value;
					}
				} else if (parts.length >= 6 && parts[0].equals("basis")) {
					int boughtUnits = Integer.parseInt(parts[2]);
					int costedUnits = Integer.parseInt(parts[4]);
					double spent = Double.parseDouble(parts[3]);
					double costedSpend = Double.parseDouble(parts[5]);

					if (boughtUnits > costedUnits && Double.isFinite(spent) && Double.isFinite(costedSpend)) {
						Basis basis = new Basis();
						basis.boughtUnits = boughtUnits;
						basis.spent = spent;
						basis.costedUnits = costedUnits;
						basis.costedSpend = costedSpend;
						BASIS.put(parts[1], basis);
					}
				}
			}
		} catch (IOException | NumberFormatException e) {
			BzAssist.LOGGER.warn("Could not read {}: {}", FILE, e.toString());
		}
	}

	private static void save() {
		StringBuilder text = new StringBuilder();
		text.append("# BzAssist realized bazaar profit and the cost basis of open buys.\n");
		text.append("total\t").append(total).append('\n');

		for (Map.Entry<String, Basis> entry : BASIS.entrySet()) {
			Basis basis = entry.getValue();

			if (basis.boughtUnits <= basis.costedUnits) {
				continue;
			}

			text.append("basis\t").append(entry.getKey().replace('\t', ' ').replace('\n', ' '))
					.append('\t').append(basis.boughtUnits)
					.append('\t').append(basis.spent)
					.append('\t').append(basis.costedUnits)
					.append('\t').append(basis.costedSpend)
					.append('\n');
		}

		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, text.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			BzAssist.LOGGER.warn("Could not write {}: {}", FILE, e.toString());
		}
	}

	private static final class Basis {
		private int boughtUnits;
		private double spent;
		private int costedUnits;
		private double costedSpend;
	}
}
