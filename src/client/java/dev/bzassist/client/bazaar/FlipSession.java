package dev.bzassist.client.bazaar;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

	/**
	 * Ticks spent asking for the same stash stack before moving on. Half a minute is long enough to click
	 * and short enough that a stack which cannot be collected never traps the player on one slot.
	 */
	private static final int STASH_PATIENCE_TICKS = 600;

	/** When each side of each product was last sent to be relisted, keyed by id and side. */
	private static final Map<String, Long> LAST_RELISTED = new HashMap<>();

	/**
	 * Products this session has already placed a buy order for. The order list is only re-read every
	 * couple of minutes, so without this the very next ranking could pick the same item again and stack
	 * two identical orders instead of filling another slot with something else.
	 */
	private static final java.util.Set<String> PLACED = new java.util.HashSet<>();

	/** How long the session keeps placing before it goes back to re-read the order list. */
	private static final long SCAN_INTERVAL_MS = 120_000L;

	/**
	 * The smallest gap to the top of the book that counts as being behind it. Bazaar prices move in
	 * 0.1-coin ticks, so a rival who merely matches your price has not undercut you; one tick has.
	 */
	private static final double PRICE_TICK = 0.1;
	private static final double PRICE_EPSILON = 1.0E-4;

	private static Phase lastPhase = Phase.OFF;
	private static int sampleTicks;
	/** A relist-only pass stops as soon as every order has been checked, without touching the market. */
	private static boolean relistOnly;
	private static volatile Phase phase = Phase.OFF;
	private static volatile String note = "off";
	private static final List<OrderRow> own = new ArrayList<>();
	private static int cursor;
	private static int stashStage;
	private static int stashSlot = -1;
	private static int stashTicks;
	/** Orders placed at the moment the current plan was made, so the session can tell it went through. */
	private static int placedAtPlan;
	/** When the order list was last read, and whether a claim has arrived since. */
	private static long lastScanAt;
	private static boolean claimsSeen;
	private static OrderRow cancelling;

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

	/** True while this session is in the middle of cancelling one of its own orders. */
	public static boolean wantsCancel() {
		return phase == Phase.RELIST && cancelling != null;
	}

	/**
	 * Hypixel paid out a claim: items or coins. The next pass collects and lists them, so the session
	 * stops placing long enough to do the housekeeping instead of drifting on one product.
	 */
	static void noteClaimed() {
		if (phase != Phase.OFF) {
			claimsSeen = true;
		}
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
		PLACED.clear();
		claimsSeen = false;
		lastScanAt = System.currentTimeMillis();
		cancelling = null;
		cursor = 0;
		stashStage = 0;
		stashSlot = -1;
		stashTicks = 0;
		lastPhase = Phase.OFF;
		sampleTicks = 0;
		FlipSession.relistOnly = relistOnly;
		ChatWatch.clear();
		SessionLog.begin();

		if (!relistOnly) {
			SessionStats.begin();
			// The HUD's session figure and clock belong to the session, not to the game: they restart
			// with F6 instead of accumulating for as long as the client has been open.
			ProfileState.resetSession();
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
			showSummary();
		}
	}

	/**
	 * Opens the summary whenever the session recorded an order or a claim. A session that did nothing
	 * stays quiet; one that ended with stock still listed now still gets its figures (the old profit
	 * gate hid exactly those sessions, because committed buy orders count as spend until they sell).
	 */
	private static void showSummary() {
		if (!SessionStats.hasData()) {
			return;
		}

		SummaryScreen.open();
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

		// A claim means there is stock to collect, and the order list is only a snapshot: every couple of
		// minutes, and whenever a claim lands, PLACE hands back to SCAN instead of placing forever on an
		// increasingly stale picture. The old machine never returned, which is why one order filled the
		// whole session.
		if (phase == Phase.PLACE && (claimsSeen || System.currentTimeMillis() - lastScanAt > SCAN_INTERVAL_MS)) {
			claimsSeen = false;
			phase = Phase.SCAN;
			note = "re-reading your orders";
			SessionLog.event("phase", "PLACE -> SCAN (housekeeping)");
			return;
		}

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
		lastScanAt = System.currentTimeMillis();

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
			OrderRow row = OrderRow.parse(slot);

			if (row == null) {
				continue;
			}

			if (!row.readable()) {
				SessionLog.event("orders", "unreadable row: '" + slot.name() + "' | "
						+ (slot.lore().isEmpty() ? "no lore" : slot.lore().get(0)));
				continue;
			}

			own.add(row);
			SessionLog.event("orders", String.format(Locale.ROOT, "%s %s @%.1f %d/%d",
					row.sellSide() ? "SELL" : "BUY", row.name(), row.price(), row.filled(), row.total()));
		}
	}

	private static void relist(BazaarView view) {
		// An order whose detail screen is open stays the instruction: Cancel is the next click, and the
		// step must not be replaced on the following tick by whatever phase comes next.
		if (cancelling != null) {
			if (view.kind() == BazaarView.Kind.ORDER_DETAIL) {
				BazaarView.SlotView cancelButton = findCancel(view);

				if (cancelButton == null) {
					push(new GuideEngine.Step("Press Esc", "back to your orders", -1, false, ""));
					return;
				}

				note = "confirming the cancel";
				push(new GuideEngine.Step("Click Cancel",
						cancelling.name() + " · " + worthWaitingNote(cancelling), cancelButton.index(), false, ""));
				return;
			}

			if (view.kind() == BazaarView.Kind.ORDERS) {
				// Back on the list: a row that is still there was not cancelled (Esc), so keep working
				// on it. One that has gone did cancel, so drop it and start again from the top.
				if (rowFor(view, cancelling) != null) {
					cursor = Math.max(0, own.indexOf(cancelling));
				} else {
					int index = own.indexOf(cancelling);

					if (index >= 0) {
						own.remove(index);
					}

					cancelling = null;
					cursor = 0;
				}
			} else {
				// Chat, a sign or Esc closed the screen for a moment. The order is still ours and still
				// not cancelled: keep it and ask for the list back instead of dropping the step.
				push(escAware(view, "Back to your orders", "open /managebazaarorders again",
						"/managebazaarorders"));
				return;
			}
		} else if (view.kind() == BazaarView.Kind.ORDER_DETAIL) {
			// An order opened by hand: the guide's own recovery step knows the way back.
			return;
		}

		while (cursor < own.size()) {
			OrderRow order = own.get(cursor);

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

			BazaarView.SlotView row = rowFor(view, order);

			if (row == null) {
				cursor++;
				continue;
			}

			cancelling = order;
			// The cooldown starts when the order is actually taken on, not when someone merely asks
			// "/bzassist orders" why it would or would not move.
			LAST_RELISTED.put(key(order), System.currentTimeMillis());
			BookPosition position = position(order.id(), order.sellSide(), order.price(), order.open());
			note = order.name() + " · " + (order.sellSide() ? "undercut" : "overbid");
			push(new GuideEngine.Step("Click " + order.name(),
					"then click Cancel Order" + (position == null ? "" : " · " + position.aheadText()) + " · "
							+ worthWaitingNote(order),
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
			// The stash command answers with a container ("View Stash") when there is something to collect
			// and with a chat line when there is not. Materials come first: they are what a bazaar flip
			// actually returns.
			String which = stashStage == 0 ? "material" : "item";

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
			String name = slot.name();

			if (name.isBlank()) {
				continue;
			}

			String id = stashItem(name, slot.itemId());

			if (id == null) {
				SessionLog.event("stash", "row '" + name + "' did not resolve to a product");
				continue;
			}

			int units = Math.max(1, Math.max(slot.count(), unitsIn(name)));
			int capacity = ProfileState.freeSlots() * 64;

			if (units > capacity) {
				SessionLog.event("stash", ItemNames.name(id) + " x" + units + " does not fit in " + capacity);
				continue;
			}

			if (slot.index() == stashSlot) {
				stashTicks++;
			} else {
				stashSlot = slot.index();
				stashTicks = 0;
			}

			if (stashTicks > STASH_PATIENCE_TICKS) {
				// Half a minute on one stack: either it will not fit, or it was collected without the row
				// changing. Moving on beats trapping the player on a slot that never completes.
				SessionLog.event("stash", "gave up on slot " + slot.index() + " after " + stashTicks + " ticks");
				stashSlot = -1;
				stashTicks = 0;
				advanceStash();
				return;
			}

			note = "collecting " + ItemNames.name(id);
			push(new GuideEngine.Step("Click to collect " + ItemNames.name(id),
					"x" + units + " · then press Esc", slot.index(), false, ""));
			return;
		}

		advanceStash();
	}

	/** The bazaar product a stash row is: its name without the "x2" suffix, or its item id. */
	private static String stashItem(String name, String itemId) {
		String id = ItemNames.idForScreenName(stripCount(name));

		if (id == null) {
			id = ItemNames.idFor(stripCount(name));
		}

		if (id == null && itemId != null && BazaarApi.snapshot().get(itemId) != null) {
			id = itemId;
		}

		return id;
	}

	/** "Coal x2" to "Coal". */
	private static String stripCount(String name) {
		return name.replaceAll("(?i)\\s+x\\s*[\\d,]+$", "").trim();
	}

	/** The count printed in a stack name ("Coal x2"), or 1. */
	private static int unitsIn(String name) {
		Matcher matcher = Pattern.compile("(?i)\\s+x\\s*([\\d,]+)$").matcher(name);
		return matcher.find() ? Math.max(1, number(matcher.group(1))) : 1;
	}

	/** True when Hypixel has just said this half of the stash is empty. */
	private static boolean saysEmpty(String which) {
		return ChatWatch.seen(20_000L, which, "stash", "empty")
				|| ChatWatch.seen(20_000L, which, "stash", "nothing");
	}

	/** Material stash checked first, then the item stash, then move on. */
	private static void advanceStash() {
		stashSlot = -1;
		stashTicks = 0;

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
	 * True when this order — however it got there, bought this session or ten hours ago — is at least one
	 * price tick behind the book and should be re-priced. The order being handled right now always counts:
	 * its step has to stay on screen until the row is clicked.
	 *
	 * <p>There is no lore marker for an undercut or overbid order, so the comparison is made against the
	 * live API on the order's own side. What gates a move is the cap (do not spend limit you do not have)
	 * and a one-minute cooldown per order (never cancel-loop).</p>
	 */
	private static boolean needsRelist(OrderRow order) {
		if (cancelling != null && cancelling.equals(order)) {
			return true;
		}

		return relistReason(order) == null;
	}

	/**
	 * Why this order should not be relisted, or {@code null} when it should. Every branch is reported, so
	 * `/bzassist orders` and the session report can answer "why didn't it move my order?" without a guess.
	 */
	private static String relistReason(OrderRow order) {
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

		BookPosition position = position(product, order.sellSide(), order.price(), order.open());

		if (position == null) {
			return "the book has no " + (order.sellSide() ? "ask" : "bid") + " price";
		}

		if (!position.behind()) {
			return String.format(Locale.ROOT, "not behind the book (%.1f vs %.1f)", order.price(),
					position.best());
		}

		Long last = LAST_RELISTED.get(key(order));

		if (last != null && System.currentTimeMillis() - last < RELIST_COOLDOWN_MS) {
			return "relisted less than a minute ago";
		}

		double cost = order.open() * Math.max(1.0, order.price());

		if (ProfileState.dailyLimitRemaining() < cost) {
			return "not enough daily limit left to move it";
		}

		SessionLog.event("relist", String.format(Locale.ROOT,
				"%s %s %.1f vs book %.1f (%.1f behind) · %s · %d open",
				order.name(), order.sellSide() ? "undercut" : "overbid", order.price(), position.best(),
				position.gap(), position.aheadText(), order.open()));
		return null;
	}

	private static String key(OrderRow order) {
		return order.id() + (order.sellSide() ? "|sell" : "|buy");
	}

	/** Everything the order reader parsed, plus the relist verdict for each row. Used by `/bzassist orders`. */
	public static List<String> describeOrders() {
		List<String> lines = new ArrayList<>();
		BazaarView view = BazaarScreenReader.view();
		// Read the screen in front of the player, not the last session's snapshot: the command has to
		// answer "what does the reader see right now" even when no session has ever run.
		List<OrderRow> rows = own.isEmpty() && view.kind() == BazaarView.Kind.ORDERS ? rowsOf(view) : own;
		lines.add("screen: " + view.kind() + " '" + view.title() + "' with " + view.slots().size() + " slots");
		lines.add("parsed: " + rows.size() + " order(s)");

		if (rows.isEmpty()) {
			int shown = 0;

			for (BazaarView.SlotView slot : view.slots()) {
				if (shown++ >= 4) {
					break;
				}

				lines.add("row: '" + slot.name() + "' -> " + ItemNames.idForScreenName(slot.name())
						+ " · side=" + sideOf(slot) + " · price=" + OrderRow.priceOf(slot));
			}
		}

		for (OrderRow order : rows) {
			String reason = relistReason(order);
			BookPosition position = position(order.id(), order.sellSide(), order.price(), order.open());
			lines.add(String.format(Locale.ROOT, "%s %s @%.1f %d/%d -> %s%s",
					order.sellSide() ? "SELL" : "BUY", order.name(), order.price(), order.filled(), order.total(),
					reason == null ? "RELIST" : reason,
					position == null ? "" : " · " + position.aheadText()));
		}

		return lines;
	}

	/** The order rows of one view, without touching the session's own list. */
	private static List<OrderRow> rowsOf(BazaarView view) {
		List<OrderRow> rows = new ArrayList<>();

		for (BazaarView.SlotView slot : view.slots()) {
			OrderRow row = OrderRow.parse(slot);

			if (row != null && row.readable()) {
				rows.add(row);
			}
		}

		return rows;
	}

	/** "SELL", "BUY" or "-" for one slot, for the `/bzassist orders` diagnostics. */
	private static String sideOf(BazaarView.SlotView slot) {
		OrderRow row = OrderRow.parse(slot);
		return row == null ? "-" : row.sellSide() ? "SELL" : "BUY";
	}

	private static String worthWaitingNote(OrderRow order) {
		return order.filled() > 0 ? order.filled() + " of " + order.total() + " filled" : "nothing filled yet";
	}

	/**
	 * Where one of our orders sits against the live book.
	 *
	 * @param behind     true when a rival is at least {@link #PRICE_TICK} better priced
	 * @param best       the best price on our side, our own order included
	 * @param gap        how much worse our price is than {@code best}, in coins
	 * @param unitsAhead units priced better, plus the other units at our own price
	 */
	public record BookPosition(boolean behind, double best, double gap, long unitsAhead) {
		public String aheadText() {
			return String.format(Locale.ROOT, "%,d %s ahead", unitsAhead, unitsAhead == 1 ? "unit" : "units");
		}

		public String reason(boolean sellSide) {
			if (!behind) {
				return String.format(Locale.ROOT, "matching the top price (%.1f)", best);
			}

			return String.format(Locale.ROOT, "%s by %.1f · %s", sellSide ? "undercut" : "overbid", gap,
					aheadText());
		}
	}

	/** Prices one of our rows against the live book, or null when there is no book for it yet. */
	public static BookPosition position(String id, boolean sellSide, double price, int openUnits) {
		return position(BazaarApi.snapshot().get(id), sellSide, price, openUnits);
	}

	private static BookPosition position(BazaarProduct product, boolean sellSide, double price, int openUnits) {
		if (product == null || price <= 0.0) {
			return null;
		}

		double best = sellSide ? product.bestAsk() : product.bestBid();

		if (best <= 0.0) {
			return null;
		}

		// Sell offers are behind when something asks less; buy orders when something bids more. A gap of
		// 0.1 coins is one full tick and counts; merely matching the top price does not.
		double gap = sellSide ? price - best : best - price;
		boolean behind = gap >= PRICE_TICK - PRICE_EPSILON;
		long ahead = product.unitsAhead(sellSide, price, Math.max(0, openUnits));
		return new BookPosition(behind, best, Math.max(0.0, gap), ahead);
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

	private static BazaarView.SlotView rowFor(BazaarView view, OrderRow order) {
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

		// List exactly what is in the bag. A sell-only plan can no longer ask for more of it to be bought,
		// which the old shared plan did whenever the bag held fewer units than a fresh position wanted.
		GuideEngine.planSell(flip, ProfileState.unitsHeld(holding));
		placedAtPlan = SessionStats.placedOrders();
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

		for (OrderRow order : own) {
			if (!order.sellSide() && order.open() > 0) {
				openBuys++;
			}
		}

		int openSells = 0;

		for (OrderRow order : own) {
			if (order.sellSide()) {
				openSells++;
			}
		}

		// Dynamic, not a fixed reserve: every sell offer we actually have takes a slot, and two more are
		// held back so a fill always has somewhere to go. Holding stock in the bag costs nothing. Orders
		// placed since the last scan count as open buys, or the budget would never fill up between scans.
		openBuys = Math.max(openBuys, PLACED.size());
		int budget = Math.max(1, BazaarConfig.orderSlots() - openSells - 2);

		if (openBuys >= budget) {
			note = openBuys + "/" + budget + " buy slots in use, waiting for fills";
			push(new GuideEngine.Step("Wait", note, -1, false, ""));
			return;
		}

		if (GuideEngine.targetId() != null) {
			// One plan at a time, but not forever. Once Hypixel confirms the order in chat the plan has
			// done its job and the next product can be chosen; a sell-only plan clears the same way. A
			// buy plan whose product stopped being tradable is dropped too, so a dead market cannot pin
			// the session on one item.
			String plannedId = GuideEngine.targetId();
			boolean sellPlan = GuideEngine.isSellOnly();
			boolean placed = SessionStats.placedOrders() > placedAtPlan;

			if (!placed) {
				if (sellPlan) {
					return;
				}

				BazaarProduct planned = BazaarApi.snapshot().get(plannedId);

				if (planned != null && FlipEngine.evaluate(planned) != null) {
					return;
				}

				SessionLog.event("place", plannedId + " is no longer tradable; re-picking");
			} else if (!sellPlan) {
				// Remembered so the next ranking pass cannot choose the same product again before the
				// order list has been re-read.
				PLACED.add(plannedId);
				SessionLog.event("place", plannedId + " placed; moving to the next product");
			}

			GuideEngine.clear();
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
			placedAtPlan = SessionStats.placedOrders();
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
		if (PLACED.contains(id)) {
			return true;
		}

		for (OrderRow order : own) {
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
