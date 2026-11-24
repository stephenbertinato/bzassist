package dev.bzassist.client.bazaar;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.bzassist.BzAssist;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * What the optimizer needs to know about the player: the purse and how much free inventory there is.
 *
 * <p>Everything is read on the client thread from the scoreboard and the player's inventory. The
 * optimizer itself only sees the immutable {@link OptimizerContext} captured from these values.</p>
 */
public final class PlayerState {
	private static final Pattern PURSE_LINE = Pattern.compile(
			"(?i)purse[^0-9]{0,14}([0-9][0-9,._ ]*)\\s*([kmbt])?");
	private static final Pattern COIN_LINE = Pattern.compile(
			"[⏣⛃\\u058f]\\s*([0-9][0-9,._ ]*)\\s*([kmbt])?");

	private static volatile long purse = -1L;
	private static volatile int freeSlots = 36;
	private static volatile int usedSlots;
	private static volatile boolean skyblockSidebar;
	private static volatile boolean hypixelServer;
	private static volatile int tickCounter;

	private PlayerState() {
	}

	public static long purse() {
		return purse;
	}

	public static boolean purseKnown() {
		return purse >= 0L;
	}

	public static int freeSlots() {
		return freeSlots;
	}

	public static int usedSlots() {
		return usedSlots;
	}

	/** True when the client is on Hypixel and the sidebar or purse identifies SkyBlock. */
	public static boolean onSkyblock() {
		return skyblockSidebar || hypixelServer;
	}

	private static boolean isHypixelServer() {
		ServerData server = Minecraft.getInstance().getCurrentServer();
		return server != null && server.ip != null
				&& server.ip.toLowerCase(Locale.ROOT).contains("hypixel.net");
	}

	/** How many units of {@code id} the player could take delivery of. */
	public static long capacity(String id) {
		if (id != null && id.startsWith("ESSENCE_")) {
			return 10_000_000L;
		}

		return (long) Math.max(0, freeSlots) * 64L;
	}

	/** Refreshed every few ticks from the client thread. */
	public static void tick() {
		if (++tickCounter % 4 != 0) {
			return;
		}

		refresh();
	}

	/** Force a read when the Bazaar opens, instead of waiting for the tick cadence. */
	public static void refresh() {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;

		if (player != null) {
			readFreeSlots(player);
			readPurse(minecraft);
			hypixelServer = isHypixelServer();
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

	/**
	 * The purse from the scoreboard or the SkyBlock menu item. Every display slot and objective is
	 * inspected, and the team prefix/suffix is included because Hypixel puts most of the line there.
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

		readPurseFromInventory(minecraft.player);
	}

	private static boolean readPurseFromObjective(Scoreboard scoreboard, Objective objective) {
		String title = ItemNames.strip(objective.getDisplayName().getString()).toLowerCase(Locale.ROOT);
		boolean skyblock = title.contains("skyblock");

		if (skyblock) {
			skyblockSidebar = true;
		}

		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
			String text = entryLine(scoreboard, entry);
			long value = parsePurse(text);

			if (value >= 0) {
				applyPurse(value);
				return true;
			}

			Matcher coin = COIN_LINE.matcher(text);

			if (skyblock && coin.find()) {
				long glyph = parseNumber(coin.group(1), coin.group(2));

				if (glyph >= 0) {
					applyPurse(glyph);
					return true;
				}
			}
		}

		return false;
	}

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
				applyPurse(value);
				return true;
			}
		}

		return false;
	}

	private static void applyPurse(long value) {
		if (value < 0L) {
			return;
		}

		if (value != purse) {
			purse = value;
			BzAssist.LOGGER.debug("Purse read: {}", value);
		}
	}

	/** One sidebar row exactly as it is rendered: team prefix + display name + team suffix. */
	private static String entryLine(Scoreboard scoreboard, PlayerScoreEntry entry) {
		StringBuilder text = new StringBuilder();
		PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());

		if (team != null && team.getPlayerPrefix() != null) {
			text.append(team.getPlayerPrefix().getString());
		}

		Component display = entry.display();
		text.append(display != null ? display.getString() : entry.owner());

		if (team != null && team.getPlayerSuffix() != null) {
			text.append(team.getPlayerSuffix().getString());
		}

		return ItemNames.strip(text.toString());
	}

	private static long parsePurse(String text) {
		if (text == null || text.isBlank()) {
			return -1L;
		}

		Matcher matcher = PURSE_LINE.matcher(text);
		return matcher.find() ? parseNumber(matcher.group(1), matcher.group(2)) : -1L;
	}

	/** "666,666,666", "1.2M", "3 000 000" and similar to coins, or -1 when it is not sane. */
	private static long parseNumber(String digits, String suffix) {
		if (digits == null) {
			return -1L;
		}

		String clean = digits.replace(",", "").replace("_", "").replace(" ", "").trim();

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
}
