package dev.bzassist.client;

import java.util.List;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import dev.bzassist.client.bazaar.BazaarApi;
import dev.bzassist.client.bazaar.BazaarConfig;
import dev.bzassist.client.bazaar.BazaarScreenReader;
import dev.bzassist.client.bazaar.Coins;
import dev.bzassist.client.bazaar.DebugLog;
import dev.bzassist.client.bazaar.Flip;
import dev.bzassist.client.bazaar.FlipEngine;
import dev.bzassist.client.bazaar.FlipSession;
import dev.bzassist.client.bazaar.GuideEngine;
import dev.bzassist.client.bazaar.ItemNames;
import dev.bzassist.client.bazaar.ProfileState;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** {@code /bzassist} — the console for everything the overlay shows. */
public final class BazaarCommands {
	private BazaarCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
			dispatcher.register(
					ClientCommands.literal("bzassist")
							.executes(context -> help(context.getSource()))
							.then(ClientCommands.literal("best")
									.executes(context -> best(context.getSource(), 10))
									.then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 50))
											.executes(context -> best(context.getSource(),
													IntegerArgumentType.getInteger(context, "count")))))
							.then(ClientCommands.literal("plan")
									.then(ClientCommands.argument("item", StringArgumentType.greedyString())
											.suggests(BazaarCommands::suggestItems)
											.executes(context -> plan(context.getSource(),
													StringArgumentType.getString(context, "item")))))
							.then(ClientCommands.literal("price")
									.then(ClientCommands.argument("item", StringArgumentType.greedyString())
											.suggests(BazaarCommands::suggestItems)
											.executes(context -> price(context.getSource(),
													StringArgumentType.getString(context, "item")))))
							.then(ClientCommands.literal("profile").executes(context -> profile(context.getSource())))
							.then(ClientCommands.literal("pursedebug")
									.executes(context -> purseDebug(context.getSource())))
							.then(ClientCommands.literal("session").executes(context -> session(context.getSource())))
							.then(ClientCommands.literal("orders").executes(context -> orders(context.getSource())))
							.then(ClientCommands.literal("relist").executes(context -> relist(context.getSource())))
							.then(ClientCommands.literal("summary").executes(context -> summary(context.getSource())))
							.then(ClientCommands.literal("dev")
									.then(ClientCommands.literal("summary")
											.executes(context -> devSummary(context.getSource()))))
							.then(ClientCommands.literal("daily")
									.executes(context -> daily(context.getSource(), false))
									.then(ClientCommands.literal("reset")
											.executes(context -> daily(context.getSource(), true))))
							.then(ClientCommands.literal("selftest").executes(context -> selfTest(context.getSource())))
							.then(ClientCommands.literal("dump").executes(context -> dump(context.getSource())))
							.then(ClientCommands.literal("refresh").executes(context -> refresh(context.getSource())))
							.then(ClientCommands.literal("debug").executes(context -> debug(context.getSource())))
							.then(ClientCommands.literal("off").executes(context -> overlay(context.getSource())))
							.then(ClientCommands.literal("start").executes(context -> {
								FlipSession.start();
								return 1;
							}))
							.then(ClientCommands.literal("end").executes(context -> {
								FlipSession.stop();
								return 1;
							}))
							.then(ClientCommands.literal("stop").executes(context -> stop(context.getSource()))));

			// Short form with item-name tab completion: /bza ench<TAB>
			dispatcher.register(
					ClientCommands.literal("bza")
							.then(ClientCommands.argument("item", StringArgumentType.greedyString())
									.suggests(BazaarCommands::suggestItems)
									.executes(context -> plan(context.getSource(),
											StringArgumentType.getString(context, "item")))));
		});
	}

	/** Tab completion over every bazaar product name we can resolve. */
	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestItems(
			com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context,
			com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		String remaining = builder.getRemainingLowerCase();

		for (String id : BazaarApi.snapshot().products().keySet()) {
			String name = ItemNames.name(id);

			if (name.toLowerCase(java.util.Locale.ROOT).contains(remaining)) {
				builder.suggest(name);
			}
		}

		return builder.buildFuture();
	}

	private static int help(FabricClientCommandSource source) {
		header(source, "BzAssist");
		send(source, "/bzassist best [n]", "top flips, ranked");
		send(source, "/bzassist plan <item>", "guide this item");
		send(source, "/bzassist price <item>", "one product in detail");
		send(source, "/bzassist profile", "purse, slots, tax, limit");
		send(source, "/bzassist selftest", "check the parsing and formatting code");
		send(source, "/bzassist session", "where the last session report was written");
		send(source, "/bzassist daily [reset]", "daily order-value limit left; reset it by hand");
		send(source, "/bzassist orders", "what the order reader parsed, and why each is or is not relisted");
		send(source, "/bzassist relist", "read your orders and relist what is behind the book, then stop");
		send(source, "/bzassist summary", "the last session's summary");
		send(source, "/bzassist dev summary", "show an example summary");
		send(source, "/bzassist dump", "what the scanner sees now");
		send(source, "/bzassist debug", "log screens to config/bzassist/debug.jsonl");
		send(source, "/bzassist refresh", "fetch the bazaar now");
		send(source, "/bzassist off", "toggle the overlay");
		send(source, "/bzassist stop", "drop the current plan");
		return 1;
	}

	private static int best(FabricClientCommandSource source, int count) {
		int slots = Math.max(1, BazaarConfig.orderSlots());
		List<Flip> flips = FlipEngine.rank(Math.max(count, slots));

		if (flips.isEmpty()) {
			if (ProfileState.purseKnown() && ProfileState.purse() < 1_000_000L) {
				error(source, "Purse is " + Coins.compact(ProfileState.purse())
						+ " — nothing on the bazaar is affordable at that size.");
				return 0;
			}

			error(source, "No flips yet — the bazaar data has not loaded.");
			error(source, BazaarApi.snapshot().isEmpty() ? "fetch: " + BazaarApi.lastError() : "no positive margin right now");
			return 0;
		}

		header(source, "Top " + Math.min(count, flips.size()) + "  profit/h · profit/cycle · limit/h · cap lasts · return");

		for (int i = 0; i < Math.min(count, flips.size()); i++) {
			send(source, flips.get(i).line(i + 1), "");
		}

		portfolio(source, flips, slots);
		send(source, "plan one with /bzassist plan <name>", "");
		return 1;
	}

	/**
	 * What the cap does to a full set of slots. Each listed flip spends its own order value every time
	 * it cycles and every time it relists, so their rates add up: this is the only honest answer to
	 * "how long can I keep doing this before the 15B is gone".
	 */
	private static void portfolio(FabricClientCommandSource source, List<Flip> flips, int slots) {
		int used = Math.min(slots, flips.size());
		double profitPerHour = 0.0;
		double limitPerHour = 0.0;

		for (int i = 0; i < used; i++) {
			profitPerHour += flips.get(i).adjustedPerHour();
			limitPerHour += flips.get(i).limitPerHour();
		}

		header(source, used + " slots running at once");
		send(source, "earns", Coins.compact(profitPerHour) + "/h while the limit lasts");
		send(source, "spends", Coins.compact(limitPerHour) + "/h of limit  ("
				+ String.format("%.2f", limitPerHour > 0.0 ? profitPerHour / limitPerHour * 100.0 : 0.0)
				+ "% back per limit coin)");

		if (limitPerHour <= 0.0) {
			return;
		}

		double hours = ProfileState.dailyLimitRemaining() / limitPerHour;
		send(source, "cap lasts", (hours < 10.0 ? String.format("%.1f", hours) : String.format("%.0f", hours))
				+ "h → " + Coins.compact(profitPerHour * hours) + " before it is gone");
	}

	private static int plan(FabricClientCommandSource source, String query) {
		String id = ItemNames.idFor(query);

		if (id == null) {
			error(source, "No bazaar product matches \"" + query + "\".");
			return 0;
		}

		Flip flip = FlipEngine.evaluate(BazaarApi.snapshot().get(id));

		if (flip == null) {
			error(source, ItemNames.name(id) + " has no tradable margin right now.");
			return 0;
		}

		GuideEngine.plan(flip);
		header(source, "Plan: " + flip.name());
		send(source, "buy " + flip.quantity() + " @" + Coins.format(flip.buyPrice())
				+ "  sell @" + Coins.format(flip.sellPrice()), "");
		send(source, "margin " + Coins.format(flip.marginPerUnit()) + "/unit"
				+ "  ~" + Math.round(flip.fillHours() * 60.0) + "min  returns "
				+ String.format("%.2f", flip.limitEfficiency() * 100.0) + "% of limit spent", "");
		send(source, "limit " + Coins.compact(flip.limitPerCycle()) + "/cycle · "
				+ Coins.compact(flip.limitPerHour()) + "/h · cap lasts " + flip.capText(), "");
		send(source, "next", "press T — the chat box is filled with /bz "
				+ ItemNames.searchTerm(flip.id()));
		return 1;
	}

	private static int price(FabricClientCommandSource source, String query) {
		String id = ItemNames.idFor(query);

		if (id == null) {
			error(source, "No bazaar product matches \"" + query + "\".");
			return 0;
		}

		var product = BazaarApi.snapshot().get(id);

		if (product == null) {
			error(source, "No live data for " + id + ".");
			return 0;
		}

		header(source, ItemNames.name(id) + "  (" + id + ")");
		send(source, "instantly buy", Coins.format(product.bestAsk()) + "  qty " + product.quantityAtAsk());
		send(source, "instantly sell", Coins.format(product.bestBid()) + "  qty " + product.quantityAtBid());
		send(source, "week", "buy " + Coins.compact(product.buyMovingWeek())
				+ "  sell " + Coins.compact(product.sellMovingWeek()));
		send(source, "orders", product.buyOrders() + " bids / " + product.sellOrders() + " offers");

		Flip flip = FlipEngine.evaluate(product);

		if (flip != null) {
			send(source, "flip", "+" + Coins.format(flip.profitPerCycle()) + "/cycle · "
					+ Coins.compact(flip.profitPerHour()) + "/h · "
					+ Math.round(flip.fillHours() * 60.0) + "min fill");
			send(source, "limit", Coins.compact(flip.limitPerCycle()) + "/cycle ("
					+ flip.quantity() + " × " + Coins.compact(flip.buyPrice()) + " ×" + (1 + flip.relistBuy())
					+ " buy, " + Coins.compact(flip.sellPrice()) + " ×" + (1 + flip.relistSell())
					+ " sell) · " + Coins.compact(flip.limitPerHour()) + "/h · cap lasts " + flip.capText());
			send(source, "returns", String.format("%.2f", flip.limitEfficiency() * 100.0)
					+ "% of the limit it spends");
			int rank = FlipEngine.rankOf(id);
			send(source, "rank", rank > 0
					? "#" + rank + " of " + FlipEngine.candidateCount() + " tradable products"
					: "not ranked");
		} else {
			send(source, "flip", "no profitable flip right now");
		}

		return 1;
	}

	private static int profile(FabricClientCommandSource source) {
		header(source, "Profile");
		send(source, "purse", ProfileState.purseKnown()
				? Coins.format(ProfileState.purse()) + "  (read from "
						+ ProfileState.purseSource().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ")"
				: "not detected — keep the SkyBlock sidebar visible");
		send(source, "inventory", ProfileState.usedSlots() + " used, " + ProfileState.freeSlots() + " free");
		send(source, "tax", BazaarConfig.taxPercent() + "%");
		send(source, "order slots", Integer.toString(BazaarConfig.orderSlots()));
		send(source, "daily limit left", Coins.compact(ProfileState.dailyLimitRemaining()) + " of "
				+ Coins.compact(ProfileState.DAILY_LIMIT_COINS));
		send(source, "bazaar", BazaarApi.snapshot().size() + " products, "
				+ (BazaarApi.snapshot().ageMillis() / 1000L) + "s old");
		send(source, "names", ItemNames.listCoverage() + " from list (" + ItemNames.status() + "), "
				+ ItemNames.learnedCount() + " learned");
		return 1;
	}

	/**
	 * Checks the pure code that everything else is built on: coin parsing, formatting, and the id
	 * normalisation that decides which product a name refers to. Cheap, offline, and the first thing to
	 * ask for when a number in the guide looks wrong.
	 */
	private static int selfTest(FabricClientCommandSource source) {
		int failures = 0;
		header(source, "Self-test");
		failures += check(source, "parse 1,234.5 coins", Coins.parse("1,234.5 coins"), 1234.5);
		failures += check(source, "parse 12.5m", Coins.parse("12.5m"), 12_500_000.0);
		failures += check(source, "parse 1.2b", Coins.parse("1.2b"), 1_200_000_000.0);
		failures += check(source, "parse no number", Coins.parse("no coins here"), 0.0);
		failures += check(source, "format 1234.5", Coins.format(1234.5).equals("1,234.5"), true);
		failures += check(source, "compact 1.5b", Coins.compact(1.5E9).equals("1.50b"), true);
		failures += check(source, "compact 12.3m", Coins.compact(12_345_678.0).equals("12.35m"), true);
		send(source, failures == 0 ? "result" : "FAILED", failures == 0 ? "all checks passed" : failures + " check(s) failed");
		return failures == 0 ? 1 : 0;
	}

	private static int check(FabricClientCommandSource source, String label, double actual, double expected) {
		return check(source, label, Math.abs(actual - expected) < 1.0E-6, true);
	}

	private static int check(FabricClientCommandSource source, String label, boolean actual, boolean expected) {
		boolean ok = actual == expected;

		if (!ok) {
			error(source, label + " -> got " + actual + ", wanted " + expected);
		}

		return ok ? 0 : 1;
	}

	/** A relist-only pass: read the orders, move what is behind the book, stop. Nothing is placed or sold. */
	private static int relist(FabricClientCommandSource source) {
		FlipSession.startRelistOnly();
		send(source, "relist", "armed — it reads your orders and relists only what is behind the book");
		return 1;
	}

	/** Every sidebar row as the purse reader sees it: the fastest way to explain a wrong reading. */
	private static int purseDebug(FabricClientCommandSource source) {
		header(source, "Sidebar as read");

		for (String line : ProfileState.describeSidebar()) {
			send(source, line, "");
		}

		return 1;
	}

	/** Everything the order reader sees, and its relist verdict for each row. */
	private static int orders(FabricClientCommandSource source) {
		header(source, "Bazaar orders as read");

		for (String line : FlipSession.describeOrders()) {
			send(source, line, "");
		}

		return 1;
	}

	/** The last session's summary, or a word about there not being one. */
	private static int summary(FabricClientCommandSource source) {
		if (!dev.bzassist.client.bazaar.SessionStats.hasData()) {
			error(source, "No session recorded yet — finish one with F6 first.");
			return 0;
		}

		Minecraft.getInstance().setScreenAndShow(new SummaryScreen());
		return 1;
	}

	/** An example summary, filled with the reference figures, for checking the screen in game. */
	private static int devSummary(FabricClientCommandSource source) {
		dev.bzassist.client.bazaar.SessionStats.demo();
		Minecraft.getInstance().setScreenAndShow(new SummaryScreen());
		send(source, "summary", "showing the example summary");
		return 1;
	}

	/** The daily order-value limit: spent, left, and when it rolls over. */
	private static int daily(FabricClientCommandSource source, boolean reset) {
		if (reset) {
			ProfileState.resetDailyLimit();
			send(source, "daily limit", "reset to 0 of " + Coins.compact(ProfileState.DAILY_LIMIT_COINS));
			return 1;
		}

		header(source, "Daily limit");
		send(source, "spent", Coins.compact(ProfileState.dailyLimitSpent()) + " of "
				+ Coins.compact(ProfileState.DAILY_LIMIT_COINS));
		send(source, "left", Coins.compact(ProfileState.dailyLimitRemaining()));
		send(source, "state", ProfileState.limitExhausted() ? "exhausted — the session is stopped" : "usable");
		send(source, "resets", ProfileState.nextReset()
				.atZone(java.time.ZoneId.systemDefault())
				.format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm z")));
		return 1;
	}

	/** Where the last session's full report went, and whether one is being written right now. */
	private static int session(FabricClientCommandSource source) {
		java.nio.file.Path file = dev.bzassist.client.bazaar.SessionLog.file();
		header(source, "Session report");
		send(source, "file", file == null ? "none yet — start a session with F6" : file.toString());
		send(source, "state", dev.bzassist.client.bazaar.SessionLog.active() ? "recording" : "not recording");
		return 1;
	}

	private static int dump(FabricClientCommandSource source) {
		List<String> lines = BazaarScreenReader.describe();
		header(source, "Screen");

		for (String line : lines) {
			send(source, line, "");
		}

		return 1;
	}

	private static int refresh(FabricClientCommandSource source) {
		BazaarApi.refreshNow();
		send(source, "Fetching the bazaar…", "");
		return 1;
	}

	private static int debug(FabricClientCommandSource source) {
		BazaarConfig.setDebug(!BazaarConfig.debug());
		send(source, "Debug logging " + (BazaarConfig.debug() ? "on" : "off") + " → " + DebugLog.file(), "");
		return 1;
	}

	private static int overlay(FabricClientCommandSource source) {
		BazaarConfig.setOverlay(!BazaarConfig.overlay());
		send(source, "Overlay " + (BazaarConfig.overlay() ? "on" : "off"), "");
		return 1;
	}

	private static int stop(FabricClientCommandSource source) {
		GuideEngine.clear();
		send(source, "Plan cleared.", "");
		return 1;
	}

	private static void header(FabricClientCommandSource source, String title) {
		source.sendFeedback(Component.literal(title).withStyle(ChatFormatting.GOLD));
	}

	private static void send(FabricClientCommandSource source, String label, String value) {
		Component line = Component.literal(label).withStyle(ChatFormatting.GRAY);

		if (!value.isEmpty()) {
			line = line.copy().append(Component.literal("  " + value).withStyle(ChatFormatting.WHITE));
		}

		source.sendFeedback(line);
	}

	private static void error(FabricClientCommandSource source, String message) {
		source.sendError(Component.literal(message).withStyle(ChatFormatting.RED));
	}
}
