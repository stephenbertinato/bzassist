package dev.bzassist.client.bazaar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import dev.bzassist.client.bazaar.BazaarApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Resolves bazaar product ids to the names players actually see.
 *
 * <p>The id is not the display name and neither is derivable from the other by rule
 * ({@code ENCHANTED_BLAZE_ROD} is "Enchanted Blaze Rod", but {@code INK_SACK:3} was "Lapis Lazuli").
 * Three layers are used, most trustworthy first:</p>
 *
 * <ol>
 *     <li><strong>Learned</strong> — the display name read off the real {@code ItemStack} in a bazaar
 *     screen. This is ground truth, and it is what makes the tool self-correcting for the many
 *     products Hypixel does not list.</li>
 *     <li><strong>Item list</strong> — {@code /v2/resources/skyblock/items}, cached to disk. It names
 *     1028 of the 2207 bazaar products; enchantments, shards, factions and essences are absent.</li>
 *     <li><strong>Derived</strong> — a readable guess from the id, flagged as such.</li>
 * </ol>
 */
public final class ItemNames {
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("bzassist");
	private static final Path ITEMS_CACHE = DIR.resolve("items.tsv");
	private static final Path LEARNED_FILE = DIR.resolve("learned-names.properties");
	private static final long CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;

	private static final Map<String, String> LEARNED = new ConcurrentHashMap<>();
	private static volatile Map<String, String> itemList = Map.of();
	private static volatile Map<String, String> byName = Map.of();
	private static volatile boolean loading;
	private static volatile int namedFromList;
	private static volatile String status = "not loaded";

	private ItemNames() {
	}

	/** Ids Hypixel's item list can name. */
	public static int listCoverage() {
		return namedFromList;
	}

	public static String status() {
		return status;
	}

	public static int learnedCount() {
		return LEARNED.size();
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
	 * ({@code "§cRose Bouquet"}), and those must never reach chat or a command.</p>
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

	/** A name that is safe to put inside a chat command: no formatting codes, no odd punctuation. */
	public static String commandSafe(String name) {
		String clean = strip(name);
		StringBuilder builder = new StringBuilder(clean.length());

		for (int i = 0; i < clean.length(); i++) {
			char c = clean.charAt(i);

			if (Character.isLetterOrDigit(c) || c == ' ' || c == '\'' || c == '-' || c == '.') {
				builder.append(c);
			} else if (c == '&') {
				builder.append("and");
			}
		}

		return builder.toString().trim().replaceAll("\\s+", " ");
	}

	/** Records the display name seen on a real item stack. Persisted, so it survives restarts. */
	public static void learn(String id, String displayName) {
		if (id == null || id.isEmpty() || displayName == null) {
			return;
		}

		String clean = strip(displayName).trim();

		// A button label is not an item name. Learning "Sell Offer" or "SELL Cobblestone" as a product
		// name used to turn the guide's search term into /bz Sell Offer, an item that does not exist.
		if (clean.isEmpty() || clean.equals(derived(id)) || isUiLabel(clean)) {
			return;
		}

		if (clean.equals(LEARNED.put(id, clean))) {
			return;
		}

		saveLearned();
	}

	/**
	 * True for a label printed on a bazaar button, preset or order row — never a product name. The order
	 * rows are titled {@code SELL <item>} / {@code BUY <item>}, so their prefixes are excluded too.
	 */
	public static boolean isUiLabel(String text) {
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

	/** Finds a bazaar product id from something the player typed: an id or any part of the name. */
	public static String idFor(String query) {
		if (query == null || query.isBlank()) {
			return null;
		}

		String key = normalise(query);

		if (key.isEmpty()) {
			return null;
		}

		BazaarSnapshot snapshot = BazaarApi.snapshot();

		if (snapshot.isEmpty()) {
			return null;
		}

		// Names as the game writes them come first: "Ultimate Chimera I" has to find
		// ENCHANTMENT_ULTIMATE_CHIMERA_1 even though neither the words nor the numeral match the id.
		String exact = idForScreenName(query);

		if (exact != null) {
			return exact;
		}

		if (snapshot.get(query) != null) {
			return query;
		}

		String upper = query.trim().toUpperCase(Locale.ROOT).replace(' ', '_');

		if (snapshot.get(upper) != null) {
			return upper;
		}

		for (String id : snapshot.products().keySet()) {
			if (normalise(name(id)).equals(key)) {
				return id;
			}
		}

		for (String id : snapshot.products().keySet()) {
			if (normalise(id).equals(key)) {
				return id;
			}
		}

		for (String id : snapshot.products().keySet()) {
			if (normalise(name(id)).contains(key)) {
				return id;
			}
		}

		return null;
	}

	/** Lowercase letters and digits only, so "Enchanted Blaze Rod" equals "enchanted_blaze_rod". */
	public static String normalise(String text) {
		StringBuilder builder = new StringBuilder(text.length());

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			if (Character.isLetterOrDigit(c)) {
				builder.append(Character.toLowerCase(c));
			}
		}

		return builder.toString();
	}

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

	/**
	 * The shortest search text that still finds this product in the bazaar: {@code Chimera 1} rather
	 * than {@code Ultimate Chimera I}.
	 *
	 * <p>The bazaar search is a substring filter over item names, so dropping the words every
	 * enchantment shares and writing the level as a digit leaves the fewest keystrokes that still land
	 * on exactly one item.</p>
	 */
	public static String searchTerm(String id) {
		List<String> terms = searchTerms(id);

		return terms.isEmpty() ? "" : terms.get(0);
	}

	/**
	 * Search candidates, best first. The bazaar search box matches a substring of the display name, so
	 * typing the tier ("Smoldering 6", "Smoldering VI") usually matches nothing — the first candidate is
	 * therefore the base name with the tier stripped: "Smoldering".
	 */
	public static List<String> searchTerms(String id) {
		List<String> terms = new ArrayList<>(4);
		String display = commandSafe(name(id));

		if (display.isEmpty()) {
			return terms;
		}

		String compact = compact(id, display);
		addTerm(terms, withoutTier(compact));
		addTerm(terms, compact);
		addTerm(terms, display);
		addTerm(terms, firstWord(display));
		return terms;
	}

	/** "Smoldering VI" to "Smoldering": drops a trailing roman numeral or number. */
	private static String withoutTier(String text) {
		int space = text.lastIndexOf(' ');

		if (space <= 0) {
			return text;
		}

		String tail = text.substring(space + 1);

		if (!tail.matches("(?i)^[ivxlcdm]{1,6}$") && !tail.matches("\\d{1,3}")) {
			return text;
		}

		String base = text.substring(0, space).trim();
		return base.isEmpty() ? text : base;
	}

	/**
	 * Finds a product from a name printed on a screen. Uses the same folding as {@link #idFor}, but
	 * against a cached index, so a screen with dozens of slots can be matched cheaply.
	 */
	public static String idForScreenName(String shown) {
		if (shown == null || shown.isBlank()) {
			return null;
		}

		// Order rows are titled "SELL Broken Radar" / "BUY Null Atom"; the prefix is the side, not the name.
		String text = shown.trim();
		String lower = text.toLowerCase(java.util.Locale.ROOT);

		if (lower.startsWith("sell ") || lower.startsWith("buy ")) {
			text = text.substring(5).trim();
		}

		BazaarSnapshot snapshot = BazaarApi.snapshot();

		if (snapshot.isEmpty()) {
			return null;
		}

		if (indexedSnapshot != snapshot) {
			rebuildIndex(snapshot);
		}

		return canonicalIndex.get(canonical(text));
	}

	/**
	 * Matching key for a name as written in game: case, punctuation and the generic enchantment words
	 * are ignored, and "Chimera I" folds to the same key as "Chimera 1".
	 */
	public static String canonical(String text) {
		if (text == null) {
			return "";
		}

		String key = normalise(romanToDigit(strip(text)));

		for (String noise : CANONICAL_NOISE) {
			key = key.replace(noise, "");
		}

		return key;
	}

	private static final String[] GENERIC_PREFIXES =
			{"Enchantment", "Enchanted Book", "Book of", "Book", "Ultimate"};

	/** Words that carry no identity in a product name, dropped when matching. */
	private static final String[] CANONICAL_NOISE = {"enchantment", "enchantedbook", "bookof", "book", "ultimate"};

	private static volatile Map<String, String> canonicalIndex = Map.of();
	private static volatile Object indexedSnapshot;

	private static synchronized void rebuildIndex(BazaarSnapshot snapshot) {
		if (indexedSnapshot == snapshot) {
			return;
		}

		Map<String, String> index = new HashMap<>();

		for (String id : snapshot.products().keySet()) {
			index.putIfAbsent(canonical(name(id)), id);
			index.putIfAbsent(canonical(id), id);
		}

		canonicalIndex = index;
		indexedSnapshot = snapshot;
	}

	private static void addTerm(List<String> terms, String term) {
		if (term == null) {
			return;
		}

		String trimmed = term.trim();

		if (!trimmed.isEmpty() && !terms.contains(trimmed)) {
			terms.add(trimmed);
		}
	}

	private static String firstWord(String text) {
		int space = text.indexOf(' ');
		return space < 0 ? text : text.substring(0, space);
	}

	/** Trims the words every enchantment shares. Only enchantments: elsewhere the first words matter. */
	private static String compact(String id, String display) {
		if (id == null || !id.startsWith("ENCHANTMENT_")) {
			return display;
		}

		String text = display;
		boolean trimmed = true;

		while (trimmed) {
			trimmed = false;

			for (String prefix : GENERIC_PREFIXES) {
				if (text.regionMatches(true, 0, prefix + " ", 0, prefix.length() + 1)) {
					text = text.substring(prefix.length() + 1).trim();
					trimmed = true;
				}
			}
		}

		return text;
	}

	/**
	 * "Chimera I" becomes "Chimera 1". Only a space-separated trailing numeral is folded, so words that
	 * merely end in i, v or x are left alone.
	 */
	private static String romanToDigit(String text) {
		int space = text.lastIndexOf(' ');

		if (space < 0) {
			return text;
		}

		String tail = text.substring(space + 1).trim().toUpperCase(Locale.ROOT);
		int value = switch (tail) {
			case "I" -> 1;
			case "II" -> 2;
			case "III" -> 3;
			case "IV" -> 4;
			case "V" -> 5;
			case "VI" -> 6;
			case "VII" -> 7;
			case "VIII" -> 8;
			case "IX" -> 9;
			case "X" -> 10;
			default -> 0;
		};

		return value == 0 ? text : text.substring(0, space + 1) + value;
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
		try {
			Files.createDirectories(DIR);
			Map<String, String> names = new HashMap<>();

			if (Files.exists(ITEMS_CACHE)
					&& System.currentTimeMillis() - Files.getLastModifiedTime(ITEMS_CACHE).toMillis() < CACHE_MAX_AGE_MS) {
				readTsv(names);
				status = "cache";
			}

			if (names.isEmpty()) {
				downloadItems(names);
				writeTsv(names);
				status = "downloaded";
			}

			itemList = Map.copyOf(names);
			namedFromList = names.size();

			Map<String, String> byNameNames = new HashMap<>();

			for (Map.Entry<String, String> entry : names.entrySet()) {
				byNameNames.putIfAbsent(normalise(entry.getValue()), entry.getKey());
			}

			byName = Map.copyOf(byNameNames);
			BzAssist.LOGGER.info("Item names: {} ids from Hypixel's list ({}), {} learned",
					namedFromList, status, LEARNED.size());
		} catch (Exception exception) {
			status = "failed: " + exception;
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
				// rather than letting them produce a search command for an item that does not exist.
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
