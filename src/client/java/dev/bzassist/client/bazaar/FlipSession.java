package dev.bzassist.client.bazaar;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;

import dev.bzassist.client.SummaryScreen;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.bzassist.BzAssist;

/**
 * The flipping session. Off by default; the keybind starts it.
 *
 * <p>One pass is: read our open orders from {@code /managebazaarorders}, relist what has gone stale,
 * sell anything that filled, then choose the next buy order. It never cancels an order just because a
 * better flip exists — limit already spent is spent, and the only reasons to cancel are that we are no
 * longer at the top of the book and the queue in front of us is too long to wait out.</p>
 */
public final class FlipSession {
	public enum Phase {
		/** Keybind not pressed. */
		OFF,
		/** Opening and reading our own orders. */
		SCAN,
		/** Cancelling and replacing the orders that need it. */
		RELIST,
		/** Listing units we already bought. */
		SELL,
		/** Collecting anything waiting in the stash. */
		STASH,
		/** Choosing and placing the next buy order. */
		PLACE
	}

	/** One row of our own order list. */
	public record OwnOrder(String id, String name, boolean sellSide, double price, int filled, int total) {
		public int open() {
			return Math.max(total - filled, 0);
		}
	}

	/**
	 * How cheap the limit has to be compared with profit when deciding to re-price. The gain from moving
	 * to the front is weighed against the limit re-spending one placement costs; at 0.25 a coin of limit
	 * has to buy four coins of extra profit before it counts, so profit dominates the decision.
	 */
	private static final double LIMIT_COST_WEIGHT = 0.25;

	/** Order slots kept free so filled buy orders always have somewhere to be sold from. */
	private static final int RESERVED_SELL_SLOTS = 5;

	/** One relist per order per minute: a re-read of the same row must not turn into a cancel loop. */
	private static final long RELIST_COOLDOWN_MS = 60_000L;

	/** When each side of each product was last sent to be relisted, keyed by id and side. */
	private static final Map<String, Long> LAST_RELISTED = new HashMap<>();

	private static final Pattern SPLIT = Pattern.compile("(\\d[\\d,]*)\\s*/\\s*(\\d[\\d,]*)");
	/** "Offer amount: 89x" as the order rows print it when nothing has filled yet. */
	private static final Pattern OFFER_AMOUNT =
			Pattern.compile("(?:offer|order)?\\s*amount\\s*:?\\s*(\\d[\\d,]*)");
	private static final Pattern FILLED = Pattern.compile("filled\\s*:?\\s*(\\d[\\d,]*)");
	private static final Pattern UNIT_PRICE = Pattern.compile("price per unit\\s*:?\\s*(\\d[\\d,]*(?:\\.\\d+)?)");
	private static final Pattern MONEY = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)");

	private static Phase lastPhase = Phase.OFF;
	private static int sampleTicks;
	/** A relist-only pass stops as soon as every order has been checked, without touching the market. */
	private static boolean relistOnly;
	private static volatile Phase phase = Phase.OFF;
	private static volatile String note = "off";
	private static final List<OwnOrder> own = new ArrayList<>();
	private static int cursor;
	private static int stashStage;
	private static OwnOrder cancelling;

	private FlipSession() {
	}

	public static Phase phase() {
		return phase;
	}

	public static String note() {
		return note;
	}

	public static boolean active() {
		return phase != Phase.OFF;
	}

	/** How many of our own orders the last scan found. */
	public static int ownOrderCount() {
		return own.size();
	}

	public static void toggle() {
		if (phase == Phase.OFF) {
			start();
		} else {
			stop();
		}
	}

	public static void start() {
		start(false);
	}

	/**
	 * Reads the orders and relists whatever is behind the book, then stops. Nothing is placed, nothing is
	 * sold, and the previous session's summary is left alone: this is the narrow test of one feature.
	 */
	public static void startRelistOnly() {
		start(true);
	}

	private static void start(boolean relistOnly) {
		own.clear();
		cursor = 0;
		stashStage = 0;
		lastPhase = Phase.OFF;
		sampleTicks = 0;
		FlipSession.relistOnly = relistOnly;
		ChatWatch.clear();
		SessionLog.begin();

		if (!relistOnly) {
			SessionStats.begin();
		}

		phase = Phase.SCAN;
		note = relistOnly ? "relist pass: reading your orders" : "reading your orders";
		SessionLog.event("phase", "OFF -> SCAN" + (relistOnly ? " (relist only)" : ""));
		BzAssist.LOGGER.info("Flip session started{}", relistOnly ? " (relist only)" : "");
	}

	public static void stop() {
		SessionLog.event("phase", phase + " -> OFF");

		if (!relistOnly) {
			SessionStats.finish();
		}
		SessionLog.finish();
		ChatWatch.clear();
		boolean wasRelistOnly = relistOnly;
		relistOnly = false;
		phase = Phase.OFF;
		lastPhase = Phase.OFF;
		note = "off";
		own.clear();
		cursor = 0;
		BzAssist.LOGGER.info("Flip session stopped");

		if (!wasRelistOnly) {
			GuideEngine.clear();
			showSummaryIfProfitable();
		}
	}

	/** The summary only opens when the session actually made money; a losing session stays quiet. */
	private static void showSummaryIfProfitable() {
		if (SessionStats.profit() <= 0.0) {
			return;
		}

		try {
			Minecraft.getInstance().setScreenAndShow(new SummaryScreen());
		} catch (RuntimeException exception) {
			SessionLog.failure("summary open", exception);
		}
	}

	public static void tick() {
		if (phase == Phase.OFF) {
			return;
		}

		if (ProfileState.limitExhausted()) {
			note = "daily limit reached";
			SessionLog.event("limit", "limit exhausted · stopping the session");
			BzAssist.LOGGER.warn("Daily bazaar limit reached; stopping the session and clearing the plan");
			GuideEngine.clear();
			stop();
			return;
		}

		if (phase != lastPhase) {
			SessionLog.event("phase", lastPhase + " -> " + phase + " (" + note + ")");
			lastPhase = phase;
		}

		if (++sampleTicks >= 100) {
			sampleTicks = 0;
			SessionLog.state(phase.name(), note);
		}

		BazaarView view = BazaarScreenReader.view();

		switch (phase) {
			case SCAN -> scan(view);
			case RELIST -> relist(view);
			case STASH -> stash(view);
			case SELL -> sell(view);
			case PLACE -> place(view);
			default -> {
			}
		}
	}

	private static void scan(BazaarView view) {
		if (view.kind() != BazaarView.Kind.ORDERS) {
			push(escAware(view, "Open your bazaar orders", "press T — chat is filled in",
					"/managebazaarorders"));
			return;
		}

		read(view);

		if (own.isEmpty()) {
			if (relistOnly) {
				note = "no orders to relist";
				SessionLog.event("relist", "no orders found in the list");
				stop();
				return;
			}

			phase = Phase.STASH;
			stashStage = 0;
			note = "checking the stash";
			ChatWatch.clear();
			return;
		}

		cursor = 0;
		phase = Phase.RELIST;
		note = own.size() + " orders open";
	}

	private static void read(BazaarView view) {
		own.clear();

		for (BazaarView.SlotView slot : view.slots()) {
			String name = slot.name();
			String lower = name.toLowerCase(Locale.ROOT);
			String lore = slot.loreText();
			// Order rows are titled "SELL Broken Radar" / "BUY Null Atom"; the side is the prefix.
			boolean sellSide = lower.startsWith("sell ") || lore.contains("sell offer");
			boolean buySide = lower.startsWith("buy ") || lore.contains("buy order");

			if (!sellSide && !buySide) {
				continue;
			}

			String id = idOfOrder(slot);

			if (id == null) {
				SessionLog.event("orders", "unreadable row: '" + name + "' | "
						+ (slot.lore().isEmpty() ? "no lore" : slot.lore().get(0)));
				continue;
			}

			int total = Math.max(1, slot.count());
			int filled = 0;
			Matcher split = SPLIT.matcher(lore);
			Matcher amount = OFFER_AMOUNT.matcher(lore);
			Matcher filledLine = FILLED.matcher(lore);

			if (split.find()) {
				filled = number(split.group(1));
				total = Math.max(filled, number(split.group(2)));
			} else if (amount.find()) {
				total = Math.max(1, number(amount.group(1)));
			}

			if (filledLine.find()) {
				filled = Math.max(filled, number(filledLine.group(1)));
			}

			double price = priceOf(slot);
			own.add(new OwnOrder(id, ItemNames.name(id), sellSide, price, filled, total));
			SessionLog.event("orders", String.format(Locale.ROOT, "%s %s @%.1f %d/%d",
					sellSide ? "SELL" : "BUY", ItemNames.name(id), price, filled, total));
		}
	}

	/**
	 * The product an order row is about. The game prints the SkyBlock id on the item itself
	 * ({@code NULL_ATOM}), which is also the bazaar id, so that is tried first; the name and then the lore
	 * are the fallbacks.
	 */
	private static String idOfOrder(BazaarView.SlotView slot) {
		String itemId = slot.itemId();

		if (itemId != null && !itemId.isBlank() && BazaarApi.snapshot().get(itemId) != null) {
			return itemId;
		}

		String id = ItemNames.idForScreenName(slot.name());

		if (id == null) {
			id = ItemNames.idFor(slot.name());
		}

		if (id == null) {
			for (String line : slot.lore()) {
				id = ItemNames.idFor(line);

				if (id != null) {
					break;
				}
			}
		}

		return id;
	}

	/**
	 * The price of one unit. Order rows print both "Price per unit: 12.4k" and "Total price: 793.6k", so
	 * taking the largest number on the lore — as this used to — read the total as the unit price and made
	 * every order look like it was already at the top of the book. The unit price line wins; otherwise the
	 * smallest plausible number is used.
	 */
	private static double priceOf(BazaarView.SlotView slot) {
		// "Price Per Unit: 749,986.6 coins" — the exact line, whatever the capitalisation.
		Matcher unit = UNIT_PRICE.matcher(slot.loreText());

		if (unit.find()) {
			double parsed = Coins.parse(unit.group(1));

			if (parsed > 0.0) {
				return parsed;
			}
		}

		double smallest = 0.0;

		for (String line : slot.lore()) {
			String lower = line.toLowerCase(Locale.ROOT);
			boolean perUnit = lower.contains("per unit") || lower.contains("each");

			Matcher money = MONEY.matcher(line);

			while (money.find()) {
				double value;

				try {
					value = Double.parseDouble(money.group(1).replace(",", ""));
				} catch (NumberFormatException ignored) {
					continue;
				}

				if (value <= 0.0) {
					continue;
				}

				if (perUnit) {
					return value;
				}

				smallest = smallest <= 0.0 ? value : Math.min(smallest, value);
			}
		}

		return smallest;
	}

	private static void relist(BazaarView view) {
		while (cursor < own.size()) {
			OwnOrder order = own.get(cursor);

			if (!needsRelist(order)) {
				cursor++;
				continue;
			}

			// Cancelling a buy order hands the filled units back to us, so there has to be room for them.
			if (!order.sellSide() && order.filled() > 0
					&& order.filled() > (long) ProfileState.freeSlots() * 64L) {
				phase = Phase.SELL;
				note = "making inventory room before cancelling " + order.name();
				return;
			}

			if (view.kind() != BazaarView.Kind.ORDERS && view.kind() != BazaarView.Kind.ORDER_DETAIL) {
				push(escAware(view, "Back to your orders", "open /managebazaarorders again",
						"/managebazaarorders"));
				return;
			}

			// Cancelling is two clicks: the order row, then the Cancel button on the order screen.
			BazaarView.SlotView cancelButton = findCancel(view);

			if (cancelButton != null) {
				if (cancelling != null) {
					own.remove(cancelling);
					cancelling = null;
				}

				note = "confirming the cancel";
				push(new GuideEngine.Step("Click Cancel", order.name() + " · " + worthWaitingNote(order),
						cancelButton.index(), false, ""));
				return;
			}

			BazaarView.SlotView row = rowFor(view, order);

			if (row == null) {
				cursor++;
				continue;
			}

			cancelling = order;
			note = order.name() + " · " + (order.sellSide() ? "undercut" : "overbid");
			push(new GuideEngine.Step("Click " + order.name(), "then click Cancel · " + worthWaitingNote(order),
					row.index(), false, ""));
			return;
		}

		if (relistOnly) {
			note = "relist pass done";
			SessionLog.event("relist", "pass finished with " + own.size() + " order(s) checked");
			stop();
			return;
		}

		phase = Phase.STASH;
		stashStage = 0;
		note = "orders up to date";
	}

	/**
	 * Anything Hypixel pushed into the stash after a cancel or a fill has to come back before it can be
	 * sold. Only collect what fits: taking 3,000 units with two free slots just drops the rest again.
	 */
	private static void stash(BazaarView view) {
		String title = view.title().toLowerCase(Locale.ROOT);

		if (!title.contains("stash")) {
			// The stash command answers with a container when there is something to collect and with a
			// chat line when there is not ("Your item stash is empty!"). Without reading that line the
			// session sat on a screen that was never going to open.
			String which = stashStage == 0 ? "item" : "material";

			if (saysEmpty(which)) {
				SessionLog.event("stash", which + " stash empty (chat)");
				advanceStash();
				return;
			}

			String command = "/viewstash " + which;
			push(escAware(view, "Check your " + which + " stash", "run " + command, command));
			return;
		}

		for (BazaarView.SlotView slot : view.slots()) {
			String id = ItemNames.idForScreenName(slot.name());

			if (id == null) {
				continue;
			}

			int units = Math.max(1, slot.count());

			if (units > ProfileState.freeSlots() * 64) {
				continue;
			}

			note = "collecting " + ItemNames.name(id);
			push(new GuideEngine.Step("Click to collect " + ItemNames.name(id),
					"then press Esc", slot.index(), false, ""));
			return;
		}

		advanceStash();
	}

	/** True when Hypixel has just said this half of the stash is empty. */
	private static boolean saysEmpty(String which) {
		return ChatWatch.seen(20_000L, which, "stash", "empty")
				|| ChatWatch.seen(20_000L, which, "stash", "nothing");
	}

	/** Item stash checked: ask about materials next, then move on. */
	private static void advanceStash() {
		if (stashStage == 0) {
			stashStage = 1;
			// The item answer must not be taken as the answer to the material question.
			ChatWatch.clear();
			return;
		}

		stashStage = 0;
		note = "stash checked";
		phase = Phase.SELL;
		SessionLog.event("phase", "STASH -> SELL");
	}

	/** A command step that asks for Esc first when a container is open. */
	private static GuideEngine.Step escAware(BazaarView view, String action, String detail, String command) {
		if (view.isOpen()) {
			return new GuideEngine.Step("Press Esc", "close the menu, then " + command, -1, false, "");
		}

		return new GuideEngine.Step(action, detail, -1, false, command);
	}

	/**
	 * True when re-pricing earns more than the limit it re-spends. It is not a fixed wait: the decision
	 * compares the profit per hour of sitting behind the queue with the profit per hour of being at the
	 * front, over a day, against the limit one more placement costs.
	 */
	/**
	 * True when this order — however it got there, bought this session or ten hours ago — is no longer at
	 * the front of the book and should be re-priced.
	 *
	 * <p>The old rule only relisted when an hourly-profit formula beat the limit it re-spent, which meant a
	 * long-standing order that had been undercut into irrelevance stayed exactly where it was. The rule now
	 * is the one a player uses: if you are not at the top, move. What still gates it is the cap (do not
	 * spend limit you do not have) and a one-minute cooldown per order (never cancel-loop).</p>
	 */
	private static boolean needsRelist(OwnOrder order) {
		return relistReason(order) == null;
	}

	/**
	 * Why this order should not be relisted, or {@code null} when it should. Every branch is reported, so
	 * `/bzassist orders` and the session report can answer "why didn't it move my order?" without a guess.
	 */
	private static String relistReason(OwnOrder order) {
		BazaarProduct product = BazaarApi.snapshot().get(order.id());

		if (product == null) {
			return "no live data for " + order.id() + ", cannot judge";
		}

		if (order.price() <= 0.0) {
			return "price unreadable on the row";
		}

		if (order.open() <= 0) {
			return "nothing left open (fully filled)";
		}

		double best = order.sellSide() ? product.bestAsk() : product.bestBid();

		if (best <= 0.0) {
			return "the book has no " + (order.sellSide() ? "ask" : "bid") + " price";
		}

		boolean behind = order.sellSide() ? order.price() > best : order.price() < best;

		if (!behind) {
			return String.format(Locale.ROOT, "already at the front (%.1f vs %.1f)", order.price(), best);
		}

		String key = order.id() + (order.sellSide() ? "|sell" : "|buy");
		Long last = LAST_RELISTED.get(key);

		if (last != null && System.currentTimeMillis() - last < RELIST_COOLDOWN_MS) {
			return "relisted less than a minute ago";
		}

		double cost = order.open() * Math.max(1.0, order.price());

		if (ProfileState.dailyLimitRemaining() < cost) {
			return "not enough daily limit left to move it";
		}

		LAST_RELISTED.put(key, System.currentTimeMillis());
		SessionLog.event("relist", String.format(Locale.ROOT, "%s %s %.1f vs book %.1f · %d open",
				order.name(), order.sellSide() ? "undercut" : "overbid", order.price(), best, order.open()));
		return null;
	}

	/** Everything the order reader parsed, plus the relist verdict for each row. Used by `/bzassist orders`. */
	public static List<String> describeOrders() {
		List<String> lines = new ArrayList<>();
		BazaarView view = BazaarScreenReader.view();
		lines.add("screen: " + view.kind() + " '" + view.title() + "' with " + view.slots().size() + " slots");
		lines.add("parsed: " + own.size() + " order(s)");

		if (own.isEmpty()) {
			int shown = 0;

			for (BazaarView.SlotView slot : view.slots()) {
				if (shown++ >= 4) {
					break;
				}

				lines.add("row: '" + slot.name() + "' -> " + ItemNames.idForScreenName(slot.name()));
			}
		}

		for (OwnOrder order : own) {
			String reason = relistReason(order);
			lines.add(String.format(Locale.ROOT, "%s %s @%.1f %d/%d -> %s",
					order.sellSide() ? "SELL" : "BUY", order.name(), order.price(), order.filled(), order.total(),
					reason == null ? "RELIST" : reason));
		}

		return lines;
	}

	private static String worthWaitingNote(OwnOrder order) {
		return order.filled() > 0 ? order.filled() + " of " + order.total() + " filled" : "nothing filled yet";
	}

	private static double unitsAtOrBelow(List<OrderLevel> levels, double price) {
		double total = 0.0;

		for (OrderLevel level : levels) {
			if (level.pricePerUnit() <= price) {
				total += level.amount();
			}
		}

		return total;
	}

	private static double unitsAtOrAbove(List<OrderLevel> levels, double price) {
		double total = 0.0;

		for (OrderLevel level : levels) {
			if (level.pricePerUnit() >= price) {
				total += level.amount();
			}
		}

		return total;
	}

	/** The Cancel button on the order screen; Hypixel usually draws it as terracotta. */
	private static BazaarView.SlotView findCancel(BazaarView view) {
		for (BazaarView.SlotView slot : view.slots()) {
			String text = (slot.name() + " " + slot.loreText()).toLowerCase(Locale.ROOT);

			if (text.contains("cancel")) {
				return slot;
			}
		}

		return null;
	}

	private static BazaarView.SlotView rowFor(BazaarView view, OwnOrder order) {
		for (BazaarView.SlotView slot : view.slots()) {
			if (order.id().equals(slot.itemId()) || order.id().equals(ItemNames.idForScreenName(slot.name()))) {
				return slot;
			}
		}

		return null;
	}

	private static void sell(BazaarView view) {
		String holding = firstHolding();

		if (holding == null) {
			phase = Phase.PLACE;
			return;
		}

		BazaarProduct product = BazaarApi.snapshot().get(holding);
		Flip flip = product == null ? null : FlipEngine.evaluate(product);

		if (flip == null) {
			phase = Phase.PLACE;
			note = "holding " + ItemNames.name(holding) + " but no flip to price it from";
			return;
		}

		GuideEngine.plan(flip);
		phase = Phase.PLACE;
		note = "listing " + ItemNames.name(holding);
	}

	private static String firstHolding() {
		for (BazaarProduct product : BazaarApi.snapshot().products().values()) {
			if (ProfileState.unitsHeld(product.id()) > 0) {
				return product.id();
			}
		}

		return null;
	}

	private static void place(BazaarView view) {
		if (ProfileState.limitExhausted()) {
			push(new GuideEngine.Step("Daily limit reached", "resets at 7pm EST · /bzassist daily", -1, false, ""));
			return;
		}

		int openBuys = 0;

		for (OwnOrder order : own) {
			if (!order.sellSide() && order.open() > 0) {
				openBuys++;
			}
		}

		int openSells = 0;

		for (OwnOrder order : own) {
			if (order.sellSide()) {
				openSells++;
			}
		}

		// Dynamic, not a fixed reserve: every sell offer we actually have takes a slot, and two more are
		// held back so a fill always has somewhere to go. Holding stock in the bag costs nothing.
		int budget = Math.max(1, BazaarConfig.orderSlots() - openSells - 2);

		if (openBuys >= budget) {
			note = openBuys + "/" + budget + " buy slots in use, waiting for fills";
			push(new GuideEngine.Step("Wait", note, -1, false, ""));
			return;
		}

		if (GuideEngine.targetId() != null) {
			// The guide already owns an item: let it drive the click-by-click steps.
			return;
		}

		for (Flip flip : FlipEngine.rank(12)) {
			if (ordered(flip.id())) {
				continue;
			}

			// Never propose an order the purse cannot pay for.
			if (ProfileState.purseKnown()
					&& flip.quantity() * flip.buyPrice() > ProfileState.purse()) {
				SessionLog.event("place", flip.name() + ": " + Coins.compact(flip.quantity() * flip.buyPrice())
						+ " is more than the purse");
				continue;
			}

			GuideEngine.plan(flip);
			note = "next: " + flip.name();
			return;
		}

		if (ProfileState.purseKnown() && ProfileState.purse() < 1_000_000L) {
			push(new GuideEngine.Step("Not enough coins",
					"purse " + Coins.compact(ProfileState.purse()) + " · nothing affordable", -1, false, ""));
			return;
		}

		push(new GuideEngine.Step("Wait", "no flip worth placing right now", -1, false, ""));
	}

	private static boolean ordered(String id) {
		for (OwnOrder order : own) {
			if (order.id().equals(id)) {
				return true;
			}
		}

		return false;
	}

	private static void push(GuideEngine.Step step) {
		GuideEngine.overrideStep(step);
		SessionLog.step(step);
	}

	private static int number(String text) {
		try {
			return Integer.parseInt(text.replace(",", "").trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
