package dev.bzassist.client.bazaar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import dev.bzassist.BzAssist;
import net.fabricmc.loader.api.FabricLoader;

/**
 * What the player has, as opposed to what the market has.
 *
 * <p>Sources, in order of trust: the in-game scoreboard (purse, and the fact that we are on SkyBlock at
 * all), the player's inventory (free slots, units held) and the bazaar GUI (tax tier, order slots).
 * The daily bazaar limit is not exposed by the game, so it is tracked here and resets at 00:00 UTC.</p>
 */
public final class ProfileState {
	/** Hypixel's daily cap on order value, counted at placement. */
	public static final double DAILY_LIMIT_COINS = 15.0E9;

	private static volatile long purse = -1L;
	private static volatile PurseSource purseSource = PurseSource.UNKNOWN;
	private static volatile long spentToday;
	private static volatile boolean limitExhausted;
	private static volatile boolean limitLoaded;
	private static volatile double pendingValue;
	private static volatile long pendingAt;
	private static volatile LocalDate limitDay = LocalDate.now(ZoneOffset.UTC);
	private static volatile int freeSlots = 36;
	private static volatile int usedSlots;
	private static volatile boolean skyblockSidebar;
	private static volatile long sessionStart = System.currentTimeMillis();
	private static volatile double sessionProfit;
	private static volatile int tickCounter;

	private ProfileState() {
	}

	public static long purse() {
		return purse;
	}

	public static boolean purseKnown() {
		return purse >= 0L;
	}

	/**
	 * True only when the HUD has any business being on screen: connected to Hypixel and either the
	 * sidebar shows SkyBlock or a purse is visible. This is what keeps the overlay off every other
	 * server and out of singleplayer.
	 */
	public static boolean onSkyblock() {
		if (skyblockSidebar) {
			return true;
		}

		ServerData server = Minecraft.getInstance().getCurrentServer();

		if (server == null || server.ip == null) {
			return false;
		}

		String ip = server.ip.toLowerCase(Locale.ROOT);
		return ip.contains("hypixel.net");
	}

	public static int freeSlots() {
		return freeSlots;
	}

	public static int usedSlots() {
		return usedSlots;
	}

	public static long sessionStart() {
		return sessionStart;
	}

	public static double sessionProfit() {
		return sessionProfit;
	}

	public static void addProfit(double coins) {
		sessionProfit += coins;
	}

	/** How much of today's order-value limit is still unspent. */
	public static double dailyLimitRemaining() {
		rollDay();
		return Math.max(0.0, DAILY_LIMIT_COINS - spentToday);
	}

	public static double dailyLimitSpent() {
		rollDay();
		return spentToday;
	}

	/** True when the cap is gone today: either our own arithmetic or Hypixel saying so. */
	public static boolean limitExhausted() {
		rollDay();
		return limitExhausted || spentToday >= DAILY_LIMIT_COINS;
	}

	/**
	 * A confirm screen is open showing this order value. It is <b>not</b> spent yet: a click that the
	 * server refuses (no coins, cap reached, screen closed) must not burn the cap. It is only added once
	 * Hypixel confirms the order in chat, which is what {@link #confirmPendingOrder()} does.
	 */
	public static synchronized void noteOrderScreen(double orderValue) {
		rollDay();
		pendingValue = Math.max(0.0, orderValue);
		pendingAt = System.currentTimeMillis();
		SessionLog.event("limit", "pending order value " + Math.round(pendingValue));
	}

	/** Hypixel confirmed an order in chat: the pending value is really spent. */
	public static synchronized void confirmPendingOrder() {
		rollDay();

		if (pendingValue <= 0.0) {
			return;
		}

		spentToday += (long) Math.round(pendingValue);
		SessionLog.event("limit", "confirmed " + Math.round(pendingValue) + " · spent today " + spentToday);
		pendingValue = 0.0;
		pendingAt = 0L;
		saveLimit();
	}

	public static synchronized void discardPendingOrder(String why) {
		if (pendingValue <= 0.0) {
			return;
		}

		SessionLog.event("limit", "discarded " + Math.round(pendingValue) + " (" + why + ")");
		pendingValue = 0.0;
		pendingAt = 0L;
	}

	/** Hypixel said the daily cap is gone. Its word beats our arithmetic. */
	public static synchronized void exhaustLimit(String message) {
		rollDay();
		spentToday = (long) DAILY_LIMIT_COINS;
		limitExhausted = true;
		pendingValue = 0.0;
		pendingAt = 0L;
		saveLimit();
		BzAssist.LOGGER.warn("Daily bazaar limit reported by the server: {}", message);
		SessionLog.event("limit", "server said the limit is gone: " + message);
	}

	/** {@code /bzassist daily reset}. */
	public static synchronized void resetDailyLimit() {
		limitDay = LocalDate.now(ZoneOffset.UTC);
		spentToday = 0L;
		limitExhausted = false;
		pendingValue = 0.0;
		pendingAt = 0L;
		saveLimit();
		SessionLog.event("limit", "reset by command");
	}

	/**
	 * Reads a chat line for the two things only chat knows: an order that went through, and the server
	 * saying the daily cap is gone.
	 */
	public static void noteChat(String text) {
		if (text == null || text.isBlank()) {
			return;
		}

		String lower = text.toLowerCase(Locale.ROOT);
		boolean mentionsLimit = lower.contains("daily limit")
				|| (lower.contains("bazaar") && lower.contains("limit"))
				|| (lower.contains("order") && lower.contains("limit"));

		if (mentionsLimit && (lower.contains("reach") || lower.contains("max") || lower.contains("exceed")
				|| lower.contains("hit") || lower.contains("no more") || lower.contains("used")
				|| lower.contains("spent") || lower.contains("left") || lower.contains("remaining"))) {
			exhaustLimit(text.trim());
			return;
		}

		boolean created = (lower.contains("created") || lower.contains("placed") || lower.contains("success"))
				&& (lower.contains("buy order") || lower.contains("sell offer"));

		if (created) {
			confirmPendingOrder();
			return;
		}

		boolean refused = lower.contains("cannot afford") || lower.contains("can't afford")
				|| lower.contains("not enough coins") || lower.contains("don't have enough")
				|| lower.contains("not enough money");

		if (refused) {
			discardPendingOrder("refused by the server");
		}
	}

	private static void rollDay() {
		ensureLimitLoaded();
		LocalDate today = LocalDate.now(ZoneOffset.UTC);

		if (!today.equals(limitDay)) {
			limitDay = today;
			spentToday = 0L;
			limitExhausted = false;
			pendingValue = 0.0;
			pendingAt = 0L;
			SessionLog.event("limit", "new UTC day; counter reset");
			saveLimit();
		}

		// A confirm screen that never became an order is not a purchase.
		if (pendingAt > 0L && System.currentTimeMillis() - pendingAt > 30_000L) {
			pendingValue = 0.0;
			pendingAt = 0L;
		}
	}

	private static synchronized void ensureLimitLoaded() {
		if (limitLoaded) {
			return;
		}

		limitLoaded = true;
		Properties properties = new Properties();

		try {
			Path file = limitFile();

			if (!Files.exists(file)) {
				return;
			}

			try (InputStream in = Files.newInputStream(file)) {
				properties.load(in);
			}

			String day = properties.getProperty("day", "");
			LocalDate stored = day.isEmpty() ? null : LocalDate.parse(day);

			if (stored != null && stored.equals(LocalDate.now(ZoneOffset.UTC))) {
				spentToday = Math.max(0L, Math.round(number(properties.getProperty("spent"), 0.0)));
				limitExhausted = Boolean.parseBoolean(properties.getProperty("exhausted", "false"));
				BzAssist.LOGGER.info("Daily limit restored: {} of {} spent", spentToday, (long) DAILY_LIMIT_COINS);
			}
		} catch (RuntimeException | IOException exception) {
			BzAssist.LOGGER.warn("Could not read the saved daily limit: {}", exception.toString());
		}
	}

	private static synchronized void saveLimit() {
		try {
			Path file = limitFile();
			Files.createDirectories(file.getParent());
			Properties properties = new Properties();
			properties.setProperty("day", limitDay.toString());
			properties.setProperty("spent", Long.toString(spentToday));
			properties.setProperty("exhausted", Boolean.toString(limitExhausted));

			try (OutputStream out = Files.newOutputStream(file)) {
				properties.store(out, "BzAssist daily bazaar limit for this account. Resets 00:00 UTC (7pm EST).");
			}
		} catch (RuntimeException | IOException exception) {
			BzAssist.LOGGER.warn("Could not save the daily limit: {}", exception.toString());
		}
	}

	/** One file per Minecraft account, so two accounts on this machine never share a cap. */
	private static Path limitFile() {
		String account = "unknown";

		try {
			var user = Minecraft.getInstance().getUser();

			if (user != null && user.getProfileId() != null) {
				account = user.getProfileId().toString();
			}
		} catch (RuntimeException ignored) {
			// Before the client is ready there is no account; the fallback file is still usable.
		}

		return FabricLoader.getInstance().getConfigDir().resolve("bzassist/limit-" + account + ".properties");
	}

	private static double number(String text, double fallback) {
		try {
			return text == null ? fallback : Double.parseDouble(text.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** How many units of {@code id} the player could take delivery of. Essence is exempt (it always lands). */
	public static long capacity(String id) {
		if (id != null && id.startsWith("ESSENCE_")) {
			return 10_000_000L;
		}

		return Math.max(1L, freeSlots) * 64L;
	}

	/** Units of a product sitting in the player's inventory (i.e. bought but not yet listed). */
	public static int unitsHeld(String id) {
		LocalPlayer player = Minecraft.getInstance().player;

		if (player == null || id == null) {
			return 0;
		}

		Inventory inventory = player.getInventory();
		int total = 0;

		for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
			ItemStack stack = inventory.getItem(i);

			if (!stack.isEmpty() && id.equals(BazaarScreenReader.skyblockId(stack))) {
				total += stack.getCount();
			}
		}

		return total;
	}

	/** Refreshed every few ticks from the client thread. */
	public static void tick() {
		if (++tickCounter % 4 != 0) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;

		if (player != null) {
			readFreeSlots(player);
			readPurse(minecraft);
		}
	}

	private static void readFreeSlots(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		int used = 0;

		for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
			if (!inventory.getItem(i).isEmpty()) {
				used++;
			}
		}

		usedSlots = used;
		freeSlots = Math.max(0, 36 - used);
	}

	/** Where the purse figure came from, for `/bzassist profile` and the session report. */
	public enum PurseSource {
		UNKNOWN,
		SIDEBAR,
		SCOREBOARD,
		MENU_ITEM,
		CONTAINER
	}

	/** "Purse: 666,666,666", "Purse 1.2M", also with a coin glyph instead of the word. */
	private static final java.util.regex.Pattern PURSE_LINE = java.util.regex.Pattern
			.compile("(?i)purse[^0-9]{0,14}([0-9][0-9,._ ]*)\\s*([kmbt])?");
	private static final java.util.regex.Pattern COIN_LINE = java.util.regex.Pattern
			.compile("[⏣⛃\\u058f]\\s*([0-9][0-9,._ ]*)\\s*([kmbt])?");

	public static PurseSource purseSource() {
		return purseSource;
	}

	/**
	 * The purse, from four independent places, in order of how fresh they are: the scoreboard (every
	 * display slot and every objective, not just SIDEBAR), the SkyBlock Menu item in the inventory, and
	 * the lore of whatever container is open. Each candidate is parsed with the same tolerant parser and
	 * sanity-checked before it is accepted, and a failure falls through to the next source instead of
	 * clearing a value that was already right.
	 */
	private static void readPurse(Minecraft minecraft) {
		ClientLevel level = minecraft.level;

		if (level != null) {
			Scoreboard scoreboard = level.getScoreboard();
			Set<Objective> objectives = new LinkedHashSet<>();

			for (DisplaySlot slot : DisplaySlot.values()) {
				Objective displayed = scoreboard.getDisplayObjective(slot);

				if (displayed != null) {
					objectives.add(displayed);
				}
			}

			objectives.addAll(scoreboard.getObjectives());

			for (Objective objective : objectives) {
				if (readPurseFromObjective(scoreboard, objective)) {
					return;
				}
			}
		}

		if (readPurseFromInventory(minecraft.player)) {
			return;
		}

		readPurseFromContainer();
	}

	/** Reads the purse out of one objective. Returns true when it found a figure. */
	private static boolean readPurseFromObjective(Scoreboard scoreboard, Objective objective) {
		String title = ItemNames.strip(objective.getDisplayName().getString()).toLowerCase(Locale.ROOT);
		boolean skyblock = title.contains("skyblock");

		if (skyblock) {
			skyblockSidebar = true;
		}

		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
			Component display = entry.display();
			String text = ItemNames.strip(display != null ? display.getString() : entry.owner());
			long value = parsePurse(text);

			if (value >= 0) {
				applyPurse(value, skyblock ? PurseSource.SIDEBAR : PurseSource.SCOREBOARD);
				return true;
			}

			// Some layouts print the balance as a coin glyph with no "Purse" label.
			java.util.regex.Matcher coin = COIN_LINE.matcher(text);

			if (skyblock && coin.find()) {
				long glyph = parseNumber(coin.group(1), coin.group(2));

				if (glyph >= 0) {
					applyPurse(glyph, PurseSource.SIDEBAR);
					return true;
				}
			}
		}

		return false;
	}

	/** The SkyBlock Menu item carries the purse in its lore. */
	private static boolean readPurseFromInventory(LocalPlayer player) {
		if (player == null) {
			return false;
		}

		Inventory inventory = player.getInventory();

		for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
			ItemStack stack = inventory.getItem(i);

			if (stack.isEmpty()) {
				continue;
			}

			String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);

			if (!name.contains("skyblock menu") && !name.contains("skyblock hub")) {
				continue;
			}

			long value = parsePurse(stack.getHoverName().getString());
			ItemLore lore = stack.get(DataComponents.LORE);

			if (value < 0 && lore != null) {
				for (Component line : lore.lines()) {
					value = parsePurse(line.getString());

					if (value >= 0) {
						break;
					}
				}
			}

			if (value >= 0) {
				skyblockSidebar = true;
				applyPurse(value, PurseSource.MENU_ITEM);
				return true;
			}
		}

		return false;
	}

	/** Whatever container is open may print the purse in an info item. */
	private static boolean readPurseFromContainer() {
		BazaarView view = BazaarScreenReader.lastContainer();

		for (BazaarView.SlotView slot : view.slots()) {
			long value = parsePurse(slot.name());

			if (value < 0) {
				for (String line : slot.lore()) {
					value = parsePurse(line);

					if (value >= 0) {
						break;
					}
				}
			}

			if (value >= 0) {
				applyPurse(value, PurseSource.CONTAINER);
				return true;
			}
		}

		return false;
	}

	private static void applyPurse(long value, PurseSource source) {
		if (value < 0L) {
			return;
		}

		purseSource = source;

		if (value != purse) {
			purse = value;
			SessionLog.event("purse", value + " from " + source.name().toLowerCase(Locale.ROOT));
		}
	}

	/** The figure in a line, or -1 when the line has no purse reading. */
	private static long parsePurse(String text) {
		if (text == null || text.isBlank()) {
			return -1L;
		}

		java.util.regex.Matcher matcher = PURSE_LINE.matcher(text);

		if (!matcher.find()) {
			return -1L;
		}

		return parseNumber(matcher.group(1), matcher.group(2));
	}

	/** "666,666,666", "1.2M", "3 000 000" and similar to coins, or -1 when it is not a sane amount. */
	private static long parseNumber(String digits, String suffix) {
		if (digits == null) {
			return -1L;
		}

		String clean = digits.replace(",", "").replace("_", "").replace(" ", "").trim();

		// "1.234.567" uses dots as separators; a single dot is a decimal point.
		if (clean.chars().filter(c -> c == '.').count() > 1) {
			clean = clean.replace(".", "");
		}

		double value;

		try {
			value = Double.parseDouble(clean);
		} catch (NumberFormatException exception) {
			return -1L;
		}

		if (suffix != null) {
			value *= switch (suffix.toLowerCase(Locale.ROOT)) {
				case "k" -> 1.0E3;
				case "m" -> 1.0E6;
				case "b" -> 1.0E9;
				case "t" -> 1.0E12;
				default -> 1.0;
			};
		}

		long coins = Math.round(value);
		return coins >= 0L && coins <= 10_000_000_000_000L ? coins : -1L;
	}

	/** UTC instant of the next daily reset, for the HUD. */
	public static Instant nextReset() {
		return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
	}

	public static void resetSession() {
		sessionStart = System.currentTimeMillis();
		sessionProfit = 0.0;
		BzAssist.LOGGER.info("Session reset");
	}
}
