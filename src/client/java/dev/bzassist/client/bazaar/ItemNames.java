package dev.bzassist.client.bazaar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.bzassist.BzAssist;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Resolves bazaar product ids to display names.
 *
 * <p>The id is not the display name and neither is derivable from the other by rule
 * ({@code ENCHANTED_BLAZE_ROD} is "Enchanted Blaze Rod", but {@code INK_SACK:3} was "Lapis Lazuli").
 * Three layers are used, most trustworthy first:</p>
 *
 * <ol>
 *     <li><strong>Learned cache</strong> — names saved by older builds. It is read-only now.</li>
 *     <li><strong>Item list</strong> — {@code /v2/resources/skyblock/items}, cached to disk. It names
 *     1028 of the 2207 bazaar products; enchantments, shards, factions and essences are absent.</li>
 *     <li><strong>Derived</strong> — a readable guess from the id.</li>
 * </ol>
 *
 * <p>Nothing learns or writes new names any more; this class only resolves names for the overlay and
 * chat output.</p>
 */
public final class ItemNames {
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("bzassist");
	private static final Path ITEMS_CACHE = DIR.resolve("items.tsv");
	private static final Path LEARNED_FILE = DIR.resolve("learned-names.properties");
	private static final long CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;

	private static final Map<String, String> LEARNED = new ConcurrentHashMap<>();
	private static volatile Map<String, String> itemList = Map.of();
	private static volatile boolean loading;

	private ItemNames() {
	}

	/** Starts loading in the background (once). */
	public static void ensureLoaded() {
		if (loading) {
			return;
		}

		loading = true;
		loadLearned();
		BazaarApi.executor().execute(ItemNames::loadItemList);
	}

	/**
	 * The best display name known for {@code id}; never null, may be a derived guess.
	 *
	 * <p>Always stripped: Hypixel's own item list carries formatting codes in 22 of its names
	 * ({@code "§cRose Bouquet"}), and those must never reach the panel or chat.</p>
	 */
	public static String name(String id) {
		if (id == null || id.isEmpty()) {
			return "?";
		}

		String learned = LEARNED.get(id);

		if (learned != null && !isUiLabel(learned)) {
			return strip(learned);
		}

		String listed = itemList.get(id);

		if (listed != null) {
			return strip(listed);
		}

		return strip(derived(id));
	}

	/** True for a label printed on a bazaar button, preset or order row — never a product name. */
	private static boolean isUiLabel(String text) {
		String lower = strip(text).trim().toLowerCase(Locale.ROOT);

		if (lower.isEmpty() || UI_NAMES.contains(lower)) {
			return true;
		}

		return lower.startsWith("sell ") || lower.startsWith("buy ");
	}

	/** Screen and button labels that must never be mistaken for a bazaar product. */
	private static final Set<String> UI_NAMES = Set.of(
			"buy instantly", "sell instantly", "create buy order", "create sell offer",
			"buy order", "sell offer", "confirm buy order", "confirm sell offer",
			"custom amount", "custom price", "same as top order", "same as best offer",
			"top order +0.1", "best offer -0.1", "5% of spread", "10% of spread",
			"go back", "back", "close", "claim all coins", "cancel order", "manage orders",
			"order options", "view stash", "bazaar", "get me a stack");

	/** Removes legacy formatting codes and control characters. */
	public static String strip(String text) {
		if (text == null) {
			return "";
		}

		StringBuilder builder = new StringBuilder(text.length());
		boolean skip = false;

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			if (skip) {
				skip = false;
				continue;
			}

			if (c == '\u00a7') {
				skip = true;
				continue;
			}

			if (c >= 0x20 || c == '\n') {
				builder.append(c);
			}
		}

		return builder.toString();
	}

	private static String derived(String id) {
		if (id.startsWith("ENCHANTMENT_")) {
			return enchantment(id.substring("ENCHANTMENT_".length()));
		}

		if (id.startsWith("ESSENCE_")) {
			return titleCase(id.substring("ESSENCE_".length())) + " Essence";
		}

		if (id.startsWith("SHARD_")) {
			return titleCase(id.substring("SHARD_".length())) + " Shard";
		}

		if (id.startsWith("FACTION_")) {
			return titleCase(id.substring("FACTION_".length()));
		}

		return titleCase(id.replace(':', '_'));
	}

	private static String enchantment(String rest) {
		String[] parts = rest.split("_");

		if (parts.length == 0) {
			return titleCase(rest);
		}

		int last = parts.length - 1;
		StringBuilder builder = new StringBuilder();

		for (int i = 0; i < last; i++) {
			builder.append(titleCase(parts[i])).append(' ');
		}

		String level = parts[last];

		if (level.matches("\\d+")) {
			builder.append(roman(Integer.parseInt(level)));
		} else {
			builder.append(titleCase(level));
		}

		return builder.toString().trim();
	}

	private static String roman(int value) {
		if (value < 1 || value > 40) {
			return Integer.toString(value);
		}

		int[] numbers = {10, 9, 5, 4, 1};
		String[] symbols = {"X", "IX", "V", "IV", "I"};
		StringBuilder builder = new StringBuilder();
		int remaining = value;

		for (int i = 0; i < numbers.length; i++) {
			while (remaining >= numbers[i]) {
				builder.append(symbols[i]);
				remaining -= numbers[i];
			}
		}

		return builder.toString();
	}

	private static String titleCase(String text) {
		String[] words = text.split("[_\\s]+");
		StringBuilder builder = new StringBuilder();

		for (String word : words) {
			if (word.isEmpty()) {
				continue;
			}

			if (!builder.isEmpty()) {
				builder.append(' ');
			}

			builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
		}

		return builder.toString();
	}

	private static void loadItemList() {
		Map<String, String> names = new HashMap<>();
		String source = "empty";

		try {
			Files.createDirectories(DIR);

			if (Files.exists(ITEMS_CACHE)
					&& System.currentTimeMillis() - Files.getLastModifiedTime(ITEMS_CACHE).toMillis() < CACHE_MAX_AGE_MS) {
				readTsv(names);
				source = "cache";
			}

			if (names.isEmpty()) {
				downloadItems(names);
				writeTsv(names);
				source = "downloaded";
			}

			itemList = Map.copyOf(names);
			BzAssist.LOGGER.info("Item names: {} ids from Hypixel's list ({}), {} learned",
					names.size(), source, LEARNED.size());
		} catch (Exception exception) {
			BzAssist.LOGGER.warn("Could not load the item name list", exception);
		}
	}

	private static void downloadItems(Map<String, String> names) throws IOException, InterruptedException {
		String json = BazaarApi.get(BazaarApi.ITEMS_URL);
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		JsonElement items = root.get("items");

		if (items == null || !items.isJsonArray()) {
			return;
		}

		JsonArray array = items.getAsJsonArray();

		for (JsonElement element : array) {
			if (!element.isJsonObject()) {
				continue;
			}

			JsonObject item = element.getAsJsonObject();
			JsonElement id = item.get("id");
			JsonElement name = item.get("name");

			if (id != null && name != null) {
				names.put(id.getAsString(), name.getAsString());
			}
		}
	}

	private static void readTsv(Map<String, String> names) {
		try {
			for (String line : Files.readAllLines(ITEMS_CACHE)) {
				int tab = line.indexOf('\t');

				if (tab > 0) {
					names.put(line.substring(0, tab), line.substring(tab + 1));
				}
			}
		} catch (IOException e) {
			BzAssist.LOGGER.warn("Could not read {}", ITEMS_CACHE, e);
		}
	}

	private static void writeTsv(Map<String, String> names) {
		try {
			Files.createDirectories(DIR);
			StringBuilder builder = new StringBuilder(names.size() * 40);

			for (Map.Entry<String, String> entry : names.entrySet()) {
				builder.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
			}

			Files.writeString(ITEMS_CACHE, builder.toString());
		} catch (IOException e) {
			BzAssist.LOGGER.warn("Could not write {}", ITEMS_CACHE, e);
		}
	}

	private static void loadLearned() {
		if (!Files.exists(LEARNED_FILE)) {
			return;
		}

		Properties properties = new Properties();

		try {
			try (var in = Files.newInputStream(LEARNED_FILE)) {
				properties.load(in);
			}

			int dropped = 0;

			for (String key : properties.stringPropertyNames()) {
				String value = properties.getProperty(key);

				// Older builds learned whatever slot was on screen, buttons included. Purge those entries
				// rather than letting them produce a bogus display name.
				if (isUiLabel(value)) {
					dropped++;
					continue;
				}

				LEARNED.put(key, value);
			}

			if (dropped > 0) {
				BzAssist.LOGGER.warn("Dropped {} learned name(s) that were really UI labels", dropped);
				saveLearned();
			}
		} catch (IOException e) {
			BzAssist.LOGGER.warn("Could not read {}", LEARNED_FILE, e);
		}
	}

	private static synchronized void saveLearned() {
		try {
			Files.createDirectories(DIR);
			Properties properties = new Properties();

			for (Map.Entry<String, String> entry : LEARNED.entrySet()) {
				properties.setProperty(entry.getKey(), entry.getValue());
			}

			try (var out = Files.newOutputStream(LEARNED_FILE)) {
				properties.store(out, "Display names observed in the bazaar GUI");
			}
		} catch (IOException e) {
			BzAssist.LOGGER.warn("Could not write {}", LEARNED_FILE, e);
		}
	}
}
