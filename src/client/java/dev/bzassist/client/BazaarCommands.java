package dev.bzassist.client;

import java.util.List;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import dev.bzassist.client.bazaar.BazaarApi;
import dev.bzassist.client.bazaar.BazaarConfig;
import dev.bzassist.client.bazaar.Coins;
import dev.bzassist.client.bazaar.Flip;
import dev.bzassist.client.bazaar.OptimizerService;
import dev.bzassist.client.bazaar.ProfitTracker;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** {@code /bzassist} — a chat fallback for the panel plus refresh and visibility. */
public final class BazaarCommands {
	private BazaarCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
				ClientCommands.literal("bzassist")
						.executes(context -> help(context.getSource()))
						.then(ClientCommands.literal("best")
								.executes(context -> best(context.getSource(), OptimizerService.TOP_COUNT))
								.then(ClientCommands.argument("count",
												IntegerArgumentType.integer(1, OptimizerService.TOP_COUNT))
										.executes(context -> best(context.getSource(),
												IntegerArgumentType.getInteger(context, "count")))))
						.then(ClientCommands.literal("refresh")
								.executes(context -> refresh(context.getSource())))
						.then(ClientCommands.literal("profit")
								.executes(context -> profit(context.getSource()))
								.then(ClientCommands.literal("reset")
										.executes(context -> resetProfit(context.getSource()))))
						.then(ClientCommands.literal("off")
								.executes(context -> off(context.getSource())))));
	}

	private static int help(FabricClientCommandSource source) {
		header(source, "BzAssist optimizer");
		send(source, "/bzassist best [n]", "top flips (default 20)");
		send(source, "/bzassist refresh", "fetch the bazaar now");
		send(source, "/bzassist profit", "show cumulative realized profit");
		send(source, "/bzassist profit reset", "reset the total to zero");
		send(source, "/bzassist off", "toggle the panel");
		return 1;
	}

	private static int best(FabricClientCommandSource source, int count) {
		List<Flip> flips = OptimizerService.top(count);

		if (flips.isEmpty()) {
			error(source, "Open the Bazaar and wait for the book to load.");
			String error = OptimizerService.error();

			if (!error.isEmpty()) {
				error(source, error);
			}

			return 0;
		}

		header(source, "Top " + flips.size() + " — buy / sell / qty / profit/h / fill");

		for (int i = 0; i < flips.size(); i++) {
			source.sendFeedback(Component.literal(flips.get(i).line(i + 1)).withStyle(ChatFormatting.WHITE));
		}

		return 1;
	}

	private static int refresh(FabricClientCommandSource source) {
		BazaarApi.refreshNow();
		OptimizerService.requestRefresh();
		source.sendFeedback(Component.literal("Refreshing the bazaar…").withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int profit(FabricClientCommandSource source) {
		double total = ProfitTracker.total();
		String amount = (total > 0.0 ? "+" : "") + Coins.format(total);
		ChatFormatting color = total > 0.0 ? ChatFormatting.GREEN
				: total < 0.0 ? ChatFormatting.RED : ChatFormatting.GRAY;
		source.sendFeedback(Component.literal("Total profit: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(amount + " coins").withStyle(color)));
		source.sendFeedback(Component.literal("Counted when a bazaar sale is claimed.")
				.withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	private static int resetProfit(FabricClientCommandSource source) {
		double previous = ProfitTracker.reset();
		source.sendFeedback(Component.literal("Total profit reset to 0")
				.withStyle(ChatFormatting.GREEN));

		if (previous != 0.0) {
			source.sendFeedback(Component.literal("Previous total: "
					+ (previous > 0.0 ? "+" : "") + Coins.format(previous) + " coins")
					.withStyle(ChatFormatting.GRAY));
		}

		return 1;
	}

	private static int off(FabricClientCommandSource source) {
		boolean enabled = !BazaarConfig.overlay();
		BazaarConfig.setOverlay(enabled);
		source.sendFeedback(Component.literal("BzAssist panel " + (enabled ? "on" : "off"))
				.withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY));
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
