package dev.bzassist.client.bazaar;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.bzassist.BzAssist;

/**
 * Decides the one thing the player should do next, and which single slot to outline.
 *
 * <p>It holds a plan (item, position size, prices) and reconciles it against the market, what the player
 * holds, and what is already listed:</p>
 *
 * <ul>
 *     <li>Never a second sell offer for a product: it would undercut your own first.</li>
 *     <li>Only buy {@code target - held - listed - open buy orders}, so units already bought or
 *     already ordered are never bought twice.</li>
 *     <li>Prices use the bazaar's own presets (Top Order +0.1, Best Offer -0.1), which are exactly the
 *     prices the flip engine computed, so no price typing is needed.</li>
 * </ul>
 */
public final class GuideEngine {
	/**
	 * One instruction.
	 *
	 * @param action    short label of what to do
	 * @param detail    why, and any numbers the player needs
	 * @param slotIndex container slot to outline, or -1 for none
	 * @param rightClick whether the highlighted slot needs a right click
	 * @param command   chat command that gets the player to the right place, or ""
	 */
	public record Step(String action, String detail, int slotIndex, boolean rightClick, String command) {
		public static final Step NONE = new Step("", "", -1, false, "");

		public boolean highlights() {
			return slotIndex >= 0;
		}
	}

	private static final Pattern AMOUNT = Pattern.compile("(\\d[\\d,]*)\\s*x");
	private static final Pattern DIGITS = Pattern.compile("(\\d[\\d,]*)");
	/** "32/64" or "32 / 64" on an order row: how much of the order has filled. */
	private static final Pattern PROGRESS = Pattern.compile("(\\d[\\d,]*)\\s*/\\s*(\\d[\\d,]*)");

	private static volatile String targetId;
	private static volatile int targetQuantity;
	private static volatile double targetBuyPrice;
	private static volatile double targetSellPrice;
	private static volatile int sellOfferQuantity;
	private static volatile int buyOrderQuantity;
	/** Units still waiting inside our buy order, and units filled but not yet claimed. */
	private static volatile int buyOpenUnits;
	private static volatile int buyClaimableUnits;
	private static volatile Step step = Step.NONE;
	private static String lastConfirm = "";

	private GuideEngine() {
	}

	public static void register() {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				tick();
			} catch (RuntimeException exception) {
				// A guide bug must never take the game down with it: log, drop the plan, carry on.
				SessionLog.failure("guide tick", exception);
				BzAssist.LOGGER.error("Guide tick failed; clearing the plan", exception);
				clear();
			}
		});
	}

	public static Step step() {
		return step;
	}

	/** Lets the session replace the step the guide computed for the current screen. */
	public static void overrideStep(Step replacement) {
		step = sanitise(replacement);
	}

	public static String targetId() {
		return targetId;
	}

	public static String targetName() {
		return targetId == null ? "" : ItemNames.name(targetId);
	}

	public static int targetQuantity() {
		return targetQuantity;
	}

	public static int sellOfferQuantity() {
		return sellOfferQuantity;
	}

	/** Units still to buy: plan minus held, minus listed for sale, minus open buy orders. */
	public static int needBuy() {
		if (targetId == null) {
			return 0;
		}

		return Math.max(0, targetQuantity - ProfileState.unitsHeld(targetId) - sellOfferQuantity - buyOrderQuantity);
	}

	/**
	 * The text for the sign that is open (or was opened from the last bazaar page), or null when no
	 * sign is expected. Read from the last container, because the sign replaces that screen.
	 */
	public static String signValue() {
		if (targetId == null) {
			return null;
		}

		BazaarView view = BazaarScreenReader.lastContainer();

		if (view.kind() == BazaarView.Kind.QUANTITY) {
			return Integer.toString(Math.max(1, needBuy()));
		}

		if (view.kind() == BazaarView.Kind.PRICE) {
			double price = needBuy() > 0 ? targetBuyPrice : targetSellPrice;
			return price > 0.0 ? trimPrice(price) : null;
		}

		return null;
	}

	public static void plan(Flip flip) {
		targetId = flip.id();
		targetQuantity = Math.max(1, flip.quantity());
		targetBuyPrice = flip.buyPrice();
		targetSellPrice = flip.sellPrice();
		sellOfferQuantity = 0;
		buyOrderQuantity = 0;
		buyOpenUnits = 0;
		buyClaimableUnits = 0;
		lastConfirm = "";
		step = Step.NONE;
	}

	public static void clear() {
		targetId = null;
		targetQuantity = 0;
		targetBuyPrice = 0.0;
		targetSellPrice = 0.0;
		sellOfferQuantity = 0;
		buyOrderQuantity = 0;
		buyOpenUnits = 0;
		buyClaimableUnits = 0;
		lastConfirm = "";
		step = Step.NONE;
	}

	public static void tick() {
		ProfileState.tick();
		BazaarScreenReader.tick();

		if (BazaarScreenReader.signOpen()) {
			String value = signValue();
			step = new Step("Click Done", value == null ? "" : "filled in: " + value, -1, false, "");
			SessionLog.step(step);
			return;
		}

		BazaarView view = BazaarScreenReader.view();

		if (view.kind() == BazaarView.Kind.ORDERS) {
			readOwnOrders(view);
		}

		if (view.kind() == BazaarView.Kind.CONFIRM) {
			noteOrderPlaced(view);
		}

		step = sanitise(decide(view));
		SessionLog.step(step);
		FlipSession.tick();
	}

	/** Reads our listed buy orders and sell offers for the target from the order list. */
	private static void readOwnOrders(BazaarView view) {
		String id = targetId;

		if (id == null) {
			return;
		}

		int open = 0;
		int claimable = 0;
		int listed = 0;

		for (BazaarView.SlotView slot : view.slots()) {
			// Order rows are matched by the name printed on them. The slot's Minecraft item id is shared
			// by every book or shard, so comparing it with a bazaar product id never matched anything.
			if (!isTargetRow(id, slot)) {
				continue;
			}

			String text = (slot.name() + " " + slot.loreText()).toLowerCase(Locale.ROOT);
			boolean sellSide = text.contains("sell offer");
			boolean buySide = text.contains("buy order");

			if (!sellSide && !buySide) {
				continue;
			}

			int total = slot.count();
			int filled = 0;
			Matcher progress = PROGRESS.matcher(slot.loreText());

			if (progress.find()) {
				filled = parse(progress.group(1));
				total = Math.max(filled, parse(progress.group(2)));
			}

			if (sellSide) {
				listed += Math.max(total - filled, 0);
			} else {
				open += Math.max(total - filled, 0);
				claimable += filled;
			}
		}

		sellOfferQuantity = listed;
		buyOpenUnits = open;
		buyClaimableUnits = claimable;
		buyOrderQuantity = open + claimable;
	}

	/** True when an order row is about the product we are guiding. */
	private static boolean isTargetRow(String id, BazaarView.SlotView slot) {
		if (id.equals(ItemNames.idForScreenName(slot.name()))) {
			return true;
		}

		String name = ItemNames.name(id);

		if (name.isEmpty() || "?".equals(name)) {
			return false;
		}

		String normalised = ItemNames.canonical(slot.name());
		return !normalised.isEmpty() && normalised.equals(ItemNames.canonical(name));
	}

	/**
	 * Hypixel charges the daily order-value limit when an order is created, so the confirm screen is the
	 * one place the value being spent is printed. Counted once per distinct confirm screen.
	 */
	private static void noteOrderPlaced(BazaarView view) {
		StringBuilder signature = new StringBuilder(view.title()).append('|').append(view.slots().size());

		for (BazaarView.SlotView slot : view.slots()) {
			signature.append(slot.name()).append(slot.count());
		}

		if (signature.toString().equals(lastConfirm)) {
			return;
		}

		lastConfirm = signature.toString();

		for (BazaarView.SlotView slot : view.slots()) {
			for (String line : slot.lore()) {
				String lower = line.toLowerCase(Locale.ROOT);

				if (!lower.contains("total price") && !lower.contains("you earn")) {
					continue;
				}

				int colon = line.indexOf(':');

				if (colon >= 0) {
					double value = Coins.parse(line.substring(colon + 1));

					if (value > 0.0) {
						ProfileState.noteOrderScreen(value);
						boolean buying = needBuy() > 0;
						int units = buying ? Math.max(1, needBuy())
								: Math.max(1, ProfileState.unitsHeld(targetId));
						SessionStats.order(targetId, ItemNames.name(targetId), buying, units, value);
						return;
					}
				}
			}
		}
	}

	private static Step decide(BazaarView view) {
		// A product page that is not the plan is never adopted: highlight only what the plan asked for,
		// and tell the player how to get back to it.
		if (targetId != null && view.kind() == BazaarView.Kind.PRODUCT) {
			String open = ItemNames.idForScreenName(BazaarView.productName(view.title()));

			if (open != null && !open.equals(targetId)) {
				return new Step("Wrong product open",
						"press Esc, then " + openCommand(targetId, ItemNames.name(targetId)),
						-1, false, "");
			}
		}

		if (ProfileState.limitExhausted()) {
			return new Step("Daily limit reached", "resets at 7pm EST · /bzassist daily", -1, false, "");
		}

		String id = targetId;

		if (id == null) {
			// Silent until the player starts a session or plans an item: no idle card, no chat box that
			// fills itself with a suggestion nobody asked for.
			return FlipSession.active()
					? new Step("Pick a flip", "run /bzassist best in chat", -1, false, "/bzassist best")
					: Step.NONE;
		}

		String name = ItemNames.name(id);
		String command = openCommand(id, name);
		int need = needBuy();
		int held = ProfileState.unitsHeld(id);

		return switch (view.kind()) {
			case NONE, OTHER -> commandStep(view, "Open the bazaar",
					"type " + command + " in chat, then Enter", command);
			case ROOT, CATEGORY -> listing(view, id, name, command);
			case PRODUCT -> product(view, name, held, need);
			case QUANTITY -> quantity(view, name, need);
			case PRICE -> price(view, name, need);
			case CONFIRM -> confirm(view, need);
			case ORDERS -> orders(view, name, command);
		};
	}

	/**
	 * Chat commands cannot be typed while a container is open, so any step that needs one asks for Esc
	 * first. That is also the recovery when the player has wandered onto the wrong screen.
	 */
	private static Step commandStep(BazaarView view, String action, String detail, String command) {
		if (view.isOpen()) {
			return new Step("Press Esc", "close the menu first — then " + command, -1, false, "");
		}

		return new Step(action, detail, -1, false, command);
	}

	/**
	 * Only four things may ever be typed by the guide: {@code /bz <term>}, bare {@code /bz},
	 * {@code /managebazaarorders}, and {@code /viewstash item|material}. Anything else is dropped, so a
	 * slot label can never leak into chat as a "command".
	 */
	private static String safeCommand(String command) {
		if (command == null) {
			return "";
		}

		String text = command.trim();

		if (text.equals("/bz") || text.startsWith("/bz ")) {
			return text;
		}

		if (text.equals("/managebazaarorders")) {
			return text;
		}

		if (text.equals("/viewstash item") || text.equals("/viewstash material")) {
			return text;
		}

		return "";
	}

	private static Step sanitise(Step step) {
		String command = safeCommand(step.command());
		return command.equals(step.command()) ? step
				: new Step(step.action(), step.detail(), step.slotIndex(), step.rightClick(), command);
	}

	/**
	 * The chat command that opens this product: {@code /bz <term>} when the term actually names something
	 * in the bazaar, otherwise the display name, and bare {@code /bz} as the last resort — never a
	 * misspelled fragment a derived name can produce.
	 */
	private static String openCommand(String id, String name) {
		String term = ItemNames.searchTerm(id);

		if (!term.isEmpty() && names(term, id)) {
			return "/bz " + term;
		}

		String full = ItemNames.commandSafe(name);

		if (!full.isEmpty() && names(full, id)) {
			return "/bz " + full;
		}

		String fromId = id.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
		return fromId.isEmpty() ? "/bz" : "/bz " + fromId;
	}

	/** True when the term resolves to this product (or to any product at all, for a partial name). */
	private static boolean names(String term, String id) {
		String resolved = ItemNames.idFor(term);
		return resolved != null && (resolved.equals(id) || term.length() >= 4);
	}

	private static double roundTick(double value) {
		return Math.round(value * 10.0) / 10.0;
	}

	/** Slot showing the product, matched by the name printed on it rather than by the item id. */
	private static BazaarView.SlotView slotFor(BazaarView view, String id) {
		for (BazaarView.SlotView slot : view.slots()) {
			if (id.equals(ItemNames.idForScreenName(slot.name()))) {
				return slot;
			}
		}

		return null;
	}

	/** On a search or category page, outline the product itself, or the search command if it is absent. */
	private static Step listing(BazaarView view, String id, String name, String command) {
		BazaarView.SlotView slot = view.withItemId(id);

		if (slot == null) {
			slot = slotFor(view, id);
		}

		if (slot == null) {
			slot = find(view, name);
		}

		if (slot != null) {
			return new Step("Open " + name, "click it", slot.index(), false, "");
		}

		return commandStep(view, "Search " + name, "type " + command + " in chat", command);
	}

	private static Step product(BazaarView view, String name, int held, int need) {
		if (need > 0) {
			double cost = need * targetBuyPrice;
			double purse = ProfileState.purseKnown() ? ProfileState.purse() : -1.0;

			if (purse >= 0.0 && cost > purse) {
				return new Step("Not enough coins",
						"this order is " + Coins.compact(cost) + ", you have " + Coins.compact(purse)
								+ " · /bzassist best",
						-1, false, "");
			}

			return slot(view, "Create Buy Order", "buy " + need + " " + name);
		}

		// Claim whatever has filled and list it straight away. Waiting for the whole buy order is worse:
		// the sell offer often completes before another unit fills, and coins sitting in claims earn
		// nothing. More filling later just means another offer.
		if (buyClaimableUnits > 0) {
			return new Step("Claim " + buyClaimableUnits, name + " · open Bazaar Orders and take them",
					-1, false, "/managebazaarorders");
		}

		if (held > 0 && sellOfferQuantity == 0) {
			return slot(view, "Create Sell Offer", "list " + held + " " + name);
		}

		if (sellOfferQuantity > 0) {
			return new Step("Wait", name + " · " + sellOfferQuantity + " listed for sale", -1, false, "");
		}

		if (buyOpenUnits > 0) {
			return new Step("Wait", name + " · buy order filling", -1, false, "");
		}

		return new Step("Wait", name + " · waiting for a fill", -1, false, "");
	}

	/** Preset quantities where they exist; otherwise the custom amount sign, which is filled in for you. */
	/**
	 * Quantity presets differ per product — books offer "Get me a stack", essence offers "Buy 50!",
	 * "Buy 100!", "Buy 500!" — so the exact number is matched against whatever the screen actually has,
	 * and anything else goes through the custom amount sign, which is filled in for you.
	 */
	private static Step quantity(BazaarView view, String name, int need) {
		for (BazaarView.SlotView slot : view.slots()) {
			Matcher digits = DIGITS.matcher(slot.name());

			if (digits.find() && parse(digits.group(1)) == need) {
				return new Step(slot.name(), "buy " + need + " " + name, slot.index(), false, "");
			}
		}

		return slotOr(view, "Custom Amount", "Custom", "amount " + need + " is typed in for you");
	}

	private static Step price(BazaarView view, String name, int need) {
		if (need > 0) {
			return slotOr(view, "Top Order +0.1", "Custom Price",
					"buy order at " + Coins.format(targetBuyPrice) + " each");
		}

		return slotOr(view, "Best Offer -0.1", "Custom Price",
				"sell offer at " + Coins.format(targetSellPrice) + " each");
	}

	private static Step confirm(BazaarView view, int need) {
		return slot(view, need > 0 ? "Buy Order" : "Sell Offer", "confirm");
	}

	private static Step orders(BazaarView view, String name, String command) {
		boolean claimable = false;

		for (BazaarView.SlotView slot : view.slots()) {
			if (targetId != null && isTargetRow(targetId, slot) && slot.loreContains("claim")) {
				claimable = true;
			}
		}

		// "Claim All Coins" collects every finished order in one click.
		BazaarView.SlotView claimAll = claimable ? find(view, "Claim All Coins") : null;

		if (claimAll != null) {
			return new Step("Claim All Coins", name + " · every finished order at once", claimAll.index(),
					false, "");
		}

		for (BazaarView.SlotView slot : view.slots()) {
			if (targetId == null || !isTargetRow(targetId, slot)) {
				continue;
			}

			// Claim first: a finished order is dead weight, and claiming it is what lets the sell offer go
			// up for the whole quantity at once.
			if (slot.loreContains("claim") || (!slot.loreContains("cancel") && buyOpenUnits == 0)) {
				return new Step("Left-click to claim", name + " · filled", slot.index(), false, "");
			}

			return new Step("Right-click to cancel", name + " · outbid or undercut", slot.index(), true, "");
		}

		return new Step("Nothing listed for " + name, "place a buy order from the product page", -1, false, command);
	}

	private static Step slot(BazaarView view, String label, String detail) {
		return slotOr(view, label, null, detail);
	}

	private static Step slotOr(BazaarView view, String label, String fallback, String detail) {
		BazaarView.SlotView slot = find(view, label);

		if (slot == null && fallback != null) {
			slot = find(view, fallback);
		}

		if (slot == null) {
			return new Step("Looking for " + label, detail, -1, false, "");
		}

		return new Step(slot.name().isBlank() ? label : slot.name(), detail, slot.index(), false, "");
	}

	/** Exact name first, then the first slot whose name contains the label. */
	private static BazaarView.SlotView find(BazaarView view, String label) {
		for (BazaarView.SlotView slot : view.slots()) {
			if (slot.name().equalsIgnoreCase(label)) {
				return slot;
			}
		}

		return view.named(label);
	}

	private static String trimPrice(double price) {
		return price == Math.floor(price)
				? Long.toString((long) price)
				: String.format(Locale.ROOT, "%.1f", price);
	}

	private static int parse(String text) {
		try {
			return Integer.parseInt(text.replace(",", "").replace(".", "").trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
