package dev.bzassist.client;

import java.util.Locale;

import dev.bzassist.BzAssist;
import dev.bzassist.client.bazaar.BazaarApi;
import dev.bzassist.client.bazaar.BazaarConfig;
import dev.bzassist.client.bazaar.Coins;
import dev.bzassist.client.bazaar.Flip;
import dev.bzassist.client.bazaar.FlipEngine;
import dev.bzassist.client.bazaar.FlipSession;
import dev.bzassist.client.bazaar.GuideEngine;
import dev.bzassist.client.bazaar.ProfileState;
import dev.bzassist.client.bazaar.SessionLog;
import dev.bzassist.client.font.FontRenderer;
import dev.bzassist.client.mixin.AbstractContainerScreenAccessor;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;

/**
 * The HUD: one wide rounded card, a large instruction with profit on the right, a detail line, and a
 * single row of six metric columns divided by hairlines.
 *
 * <pre>
 *   Create Buy Order                                   12.4m /H
 *   buy 64 &#64; 12.4k - Top Order +0.1                SMOLDERING VI
 *   ------------------------------------------------------------------
 *   PROFIT/H  |  LIMIT  |  CYCLE  |  SLOTS  |  SESSION  |  TIME
 *   12.4m/h   |  9.31b  |  +1.20m |  2/14   |  184.2m   |  24:10
 * </pre>
 *
 * <p>Motion: the card fades and rises 6 px in when a session starts and fades out when it stops; a
 * change of instruction cross-fades the action, detail and item name (old out and up, new in from
 * below) while the width eases to the wider of the two; the slot outline breathes on a 1.8 s sine.</p>
 */
public final class AssistOverlay implements HudElement {
	public static final Identifier ID = BzAssist.id("assist");

	private static final int TEXT = 0xFFF2F5F8;
	private static final int DIM = 0xFF7E858E;
	private static final int LABEL = 0xFF6E757E;
	private static final int VALUE = 0xFFE9ECF0;
	private static final int ACCENT = 0xFF43D97F;
	private static final int WARN = 0xFFFFB020;
	private static final int PANEL = 0xF708090B;
	private static final int BORDER = 0xFF262A30;
	private static final int HAIRLINE = 0xFF23262B;

	// Geometry in GUI pixels. Everything is on a 2 px step so it lands on whole device pixels.
	private static final float TOP = 8.0F;
	private static final float PAD_X = 12.0F;
	private static final float PAD_Y = 10.0F;
	private static final float RADIUS = 8.0F;
	private static final float ACTION_SIZE = 11.0F;
	private static final float ACTION_LINE = 14.0F;
	private static final float DETAIL_SIZE = 8.5F;
	private static final float DETAIL_LINE = 11.0F;
	private static final float SECTION_GAP = 8.0F;
	private static final float LABEL_SIZE = 6.5F;
	private static final float VALUE_SIZE = 9.5F;
	private static final float LABEL_GAP = 3.0F;
	private static final float COLUMN_GAP = 14.0F;
	/** Letterspacing for the all-caps labels. */
	private static final float TRACK = 0.5F;
	/** Inter's ink starts about three quarters of its size above the baseline. */
	private static final float ASCENT = 0.75F;
	private static final float MAX_WIDTH_SHARE = 0.72F;

	private static long lastFrame;
	private static float delta;
	private static float pulse;
	private static float presence;
	private static float width;
	private static float fadeIn = 1.0F;
	private static float fadeOut;
	private static String curAction = "";
	private static String curDetail = "";
	private static String curItem = "";
	private static String prevAction = "";
	private static String prevDetail = "";
	private static String prevItem = "";

	private static Flip cachedFlip;
	private static String cachedId = "";
	private static long cachedAt;

	private record Metric(String label, String value, boolean accent) {
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (Minecraft.getInstance().gui.screen() == null) {
			draw(graphics);
		}
	}

	public static void register() {
		HudElementRegistry.addLast(ID, new AssistOverlay());
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
				ScreenEvents.afterExtract(screen).register(AssistOverlay::afterExtract));
	}

	private static void afterExtract(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float delta) {
		draw(graphics);

		try {
			highlight(graphics, screen);
		} catch (RuntimeException exception) {
			SessionLog.failure("slot highlight", exception);
			BzAssist.LOGGER.error("Slot highlight failed", exception);
		}
	}

	private static void draw(GuiGraphicsExtractor graphics) {
		try {
			drawPanel(graphics);
		} catch (RuntimeException exception) {
			SessionLog.failure("HUD draw", exception);
			BzAssist.LOGGER.error("HUD draw failed (repeat failures are not logged)", exception);
		}
	}

	private static void drawPanel(GuiGraphicsExtractor graphics) {
		if (!BazaarConfig.overlay() || !ProfileState.onSkyblock()
				|| FontRenderer.measure("BzAssist", 11.0F) <= 0.0F) {
			return;
		}

		tickAnimation();
		boolean live = FlipSession.active() || GuideEngine.targetId() != null;

		if (!live && presence <= 0.01F) {
			return;
		}

		if (live) {
			GuideEngine.Step step = GuideEngine.step();

			if (!step.action().isBlank()) {
				setContent(step);
			}
		}

		int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		Metric[] metrics = metrics();
		float[] columns = new float[metrics.length];
		float columnsWidth = 0.0F;

		for (int i = 0; i < metrics.length; i++) {
			columns[i] = Math.max(FontRenderer.measure(metrics[i].label(), LABEL_SIZE, TRACK),
					FontRenderer.measure(metrics[i].value(), VALUE_SIZE));
			columnsWidth += columns[i];
		}

		columnsWidth += COLUMN_GAP * (metrics.length - 1);

		String perHour = Coins.compact(hourly());
		float perHourWidth = FontRenderer.measure(perHour, ACTION_SIZE) + 4.0F
				+ FontRenderer.measure("/H", LABEL_SIZE, TRACK);
		float actionWidth = FontRenderer.measure(curAction, ACTION_SIZE);
		float detailWidth = FontRenderer.measure(curDetail, DETAIL_SIZE);
		float itemWidth = FontRenderer.measure(curItem, LABEL_SIZE, TRACK);
		float topRow = actionWidth + 16.0F + perHourWidth;
		float detailRow = detailWidth + (itemWidth > 0.0F ? itemWidth + 14.0F : 0.0F);
		float content = Math.max(columnsWidth, Math.max(topRow, detailRow));

		if (fadeOut > 0.01F) {
			float prevItemWidth = prevItem.isEmpty() ? 0.0F : FontRenderer.measure(prevItem, LABEL_SIZE, TRACK);
			content = Math.max(content, Math.max(
					FontRenderer.measure(prevAction, ACTION_SIZE) + 16.0F + perHourWidth,
					FontRenderer.measure(prevDetail, DETAIL_SIZE)
							+ (prevItemWidth > 0.0F ? prevItemWidth + 14.0F : 0.0F)));
		}

		float maxContent = Math.max(columnsWidth, maxContentWidth());
		float needed = Math.min(content, maxContent) + PAD_X * 2.0F;
		width = width <= 0.0F ? needed : approach(width, needed, delta, 0.16F);

		float alphaBase = Math.max(0.0F, Math.min(1.0F, presence));
		float height = PAD_Y * 2.0F + ACTION_LINE + DETAIL_LINE + SECTION_GAP + 1.0F + SECTION_GAP
				+ LABEL_SIZE + LABEL_GAP + VALUE_SIZE;
		float x0 = Math.round((screenWidth - width) / 2.0F);
		float y0 = TOP + (1.0F - easeOut(alphaBase)) * 6.0F;
		float x1 = x0 + width;
		float y1 = y0 + height;
		float contentX = x0 + PAD_X;
		float contentRight = x1 - PAD_X;

		// Body, then a 1 px border drawn just outside it: the reference card has one thin outline.
		rounded(graphics, x0 - 1.0F, y0 - 1.0F, x1 + 1.0F, y1 + 1.0F, RADIUS + 1.0F, alpha(BORDER, alphaBase));
		rounded(graphics, x0, y0, x1, y1, RADIUS, alpha(PANEL, alphaBase));

		float actionBaseline = y0 + PAD_Y + ACTION_SIZE * ASCENT;
		float detailBaseline = y0 + PAD_Y + ACTION_LINE + DETAIL_SIZE * ASCENT;
		float in = easeOut(fadeIn);
		float out = fadeOut;

		if (out > 0.01F && !prevAction.isEmpty()) {
			FontRenderer.draw(graphics, prevAction, contentX, actionBaseline - (1.0F - out) * 2.0F,
					ACTION_SIZE, alpha(TEXT, alphaBase * out * 0.85F));

			if (!prevDetail.isEmpty()) {
				FontRenderer.draw(graphics, prevDetail, contentX, detailBaseline - (1.0F - out) * 2.0F,
						DETAIL_SIZE, alpha(DIM, alphaBase * out * 0.85F));
			}
		}

		float current = alphaBase * in;
		int actionColor = GuideEngine.step().rightClick() ? WARN : TEXT;

		if (current > 0.004F) {
			FontRenderer.draw(graphics, curAction, contentX, actionBaseline + (1.0F - in) * 2.0F,
					ACTION_SIZE, alpha(actionColor, current));

			if (!curDetail.isEmpty()) {
				FontRenderer.draw(graphics, curDetail, contentX, detailBaseline + (1.0F - in) * 2.0F,
						DETAIL_SIZE, alpha(DIM, current));
			}

			if (!curItem.isEmpty()) {
				FontRenderer.draw(graphics, curItem,
						contentRight - FontRenderer.measure(curItem, LABEL_SIZE, TRACK),
						detailBaseline + (1.0F - in) * 2.0F, LABEL_SIZE, alpha(LABEL, current), TRACK);
			}
		}

		// Profit on the instruction row: green figure, small tracked unit.
		float perHourX = contentRight - perHourWidth;
		FontRenderer.draw(graphics, perHour, perHourX, actionBaseline, ACTION_SIZE,
				alpha(ACCENT, alphaBase * in));
		FontRenderer.draw(graphics, "/H", perHourX + FontRenderer.measure(perHour, ACTION_SIZE) + 4.0F,
				actionBaseline, LABEL_SIZE, alpha(LABEL, alphaBase * in), TRACK);

		// Section divider, then the six-column metric row.
		float dividerY = y0 + PAD_Y + ACTION_LINE + DETAIL_LINE + SECTION_GAP;
		graphics.fill(Math.round(contentX), Math.round(dividerY), Math.round(contentRight),
				Math.round(dividerY) + 1, alpha(HAIRLINE, alphaBase));

		float metricsTop = dividerY + 1.0F + SECTION_GAP;
		float labelBaseline = metricsTop + LABEL_SIZE * ASCENT;
		float valueBaseline = metricsTop + LABEL_SIZE + LABEL_GAP + VALUE_SIZE * ASCENT;
		float columnX = contentX;

		for (int i = 0; i < metrics.length; i++) {
			Metric metric = metrics[i];

			if (i > 0) {
				float separator = columnX - COLUMN_GAP * 0.5F;
				graphics.fill(Math.round(separator), Math.round(labelBaseline - LABEL_SIZE),
						Math.round(separator) + 1, Math.round(valueBaseline + 2.0F), alpha(HAIRLINE, alphaBase));
			}

			FontRenderer.draw(graphics, metric.label(), columnX, labelBaseline, LABEL_SIZE,
					alpha(LABEL, alphaBase), TRACK);
			FontRenderer.draw(graphics, metric.value(), columnX, valueBaseline, VALUE_SIZE,
					alpha(metric.accent() ? ACCENT : VALUE, alphaBase));
			columnX += columns[i] + COLUMN_GAP;
		}
	}

	/** Six figures, in the order the card shows them. */
	private static Metric[] metrics() {
		Flip flip = plannedFlip();
		return new Metric[] {
				new Metric("PROFIT/H", Coins.compact(hourly()) + "/h", true),
				new Metric("LIMIT", Coins.compact(ProfileState.dailyLimitRemaining()), false),
				new Metric("CYCLE", flip == null ? "-" : "+" + Coins.compact(flip.profitPerCycle()), false),
				new Metric("SLOTS", slots(), false),
				new Metric("SESSION", Coins.compact(ProfileState.sessionProfit()), false),
				new Metric("TIME", clock((System.currentTimeMillis() - ProfileState.sessionStart()) / 1000L), false)
		};
	}

	/** Order slots: in use over allowed while a session is running, otherwise the allowed count. */
	private static String slots() {
		int allowed = Math.max(1, BazaarConfig.orderSlots());

		if (!FlipSession.active()) {
			return Integer.toString(allowed);
		}

		return Math.min(FlipSession.ownOrderCount(), allowed) + "/" + allowed;
	}

	/** Accepts new content and starts a cross-fade if it differs from what is on screen. */
	private static void setContent(GuideEngine.Step step) {
		float max = maxContentWidth();
		String item = trimAllCaps(GuideEngine.targetName(), 24);
		float itemWidth = item.isEmpty() ? 0.0F : FontRenderer.measure(item, LABEL_SIZE, TRACK) + 14.0F;
		float perHourWidth = FontRenderer.measure(Coins.compact(hourly()), ACTION_SIZE) + 4.0F
				+ FontRenderer.measure("/H", LABEL_SIZE, TRACK);
		String action = fit(step.action(), ACTION_SIZE, max - perHourWidth - 16.0F);
		String detail = fit(step.detail(), DETAIL_SIZE, max - itemWidth);

		if (action.equals(curAction) && detail.equals(curDetail) && item.equals(curItem)) {
			return;
		}

		prevAction = curAction;
		prevDetail = curDetail;
		prevItem = curItem;
		curAction = action;
		curDetail = detail;
		curItem = item;
		fadeIn = 0.0F;
		fadeOut = prevAction.isEmpty() ? 0.0F : 1.0F;
	}

	/** Presence, cross-fade and width easing, advanced once per frame from a real clock. */
	private static void tickAnimation() {
		long now = System.nanoTime();
		delta = lastFrame == 0L ? 1.0F / 60.0F : Math.min(0.1F, (now - lastFrame) / 1.0E9F);
		lastFrame = now;
		pulse += delta * 1.8F;

		boolean live = FlipSession.active() || GuideEngine.targetId() != null;
		presence = approach(presence, live ? 1.0F : 0.0F, delta, live ? 0.09F : 0.07F);
		fadeIn = Math.min(1.0F, fadeIn + delta / 0.13F);
		fadeOut = Math.max(0.0F, fadeOut - delta / 0.10F);
	}

	/** The widest the card may be, as a share of the screen. */
	private static float maxContentWidth() {
		int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		return Math.max(200.0F, screenWidth * MAX_WIDTH_SHARE - PAD_X * 2.0F);
	}

	/** The plan's flip, recomputed when the plan changes and at most twice a second. */
	private static Flip plannedFlip() {
		String id = GuideEngine.targetId();
		long now = System.currentTimeMillis();

		if (id == null) {
			cachedFlip = null;
			cachedId = "";
			return null;
		}

		if (!id.equals(cachedId) || now - cachedAt > 500L) {
			cachedId = id;
			cachedAt = now;
			cachedFlip = FlipEngine.evaluate(BazaarApi.snapshot().get(id));
		}

		return cachedFlip;
	}

	/** Coins per hour since the session started, or 0 during the first minute. */
	private static double hourly() {
		long elapsed = System.currentTimeMillis() - ProfileState.sessionStart();

		if (elapsed < 60_000L) {
			return 0.0;
		}

		return ProfileState.sessionProfit() * 3_600_000.0 / elapsed;
	}

	private static String clock(long seconds) {
		return String.format(Locale.ROOT, "%02d:%02d", Math.max(0L, seconds) / 60L, Math.max(0L, seconds) % 60L);
	}

	/** Exactly one outline: the slot the current step names. */
	private static void highlight(GuiGraphicsExtractor graphics, Screen screen) {
		GuideEngine.Step step = GuideEngine.step();

		if (!BazaarConfig.overlay() || !ProfileState.onSkyblock() || !step.highlights()
				|| !(screen instanceof AbstractContainerScreen<?> container)) {
			return;
		}

		if (step.slotIndex() >= container.getMenu().slots.size()) {
			return;
		}

		Slot slot = container.getMenu().slots.get(step.slotIndex());
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) (Object) container;
		int x = accessor.bzassist$leftPos() + slot.x;
		int y = accessor.bzassist$topPos() + slot.y;
		int color = step.rightClick() ? WARN : ACCENT;
		float breath = 0.72F + 0.28F * pulse01();
		outline(graphics, x - 1, y - 1, 18, 18, alpha(color, breath));
		outline(graphics, x, y, 16, 16, alpha(color, 0.22F * breath));
	}

	/** Four 1 px edges: nothing filled, nothing shaded. */
	private static void outline(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
		graphics.fill(x, y, x + width, y + 1, color);
		graphics.fill(x, y + height - 1, x + width, y + height, color);
		graphics.fill(x, y, x + 1, y + height, color);
		graphics.fill(x + width - 1, y, x + width, y + height, color);
	}

	/**
	 * A genuinely rounded rectangle: one block for the middle and one fill per row through each corner,
	 * with the row inset following a circle.
	 */
	/**
	 * A rounded rectangle drawn with anti-aliased corners: the straight middle is one fill, and each row
	 * through a corner is drawn with partial coverage on its first and last pixel. That is what turns the
	 * staircase of one-fill-per-row into a single smooth curve at any radius.
	 */
	private static void rounded(GuiGraphicsExtractor graphics, float x0, float y0, float x1, float y1,
			float radius, int color) {
		if (x1 <= x0 || y1 <= y0) {
			return;
		}

		float r = Math.max(0.0F, Math.min(radius, Math.min((x1 - x0) / 2.0F, (y1 - y0) / 2.0F)));

		if (r < 0.75F) {
			graphics.fill(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), color);
			return;
		}

		int top = Math.round(y0);
		int bottom = Math.round(y1);
		int rows = (int) Math.ceil(r);
		int middleTop = Math.round(y0 + r);
		int middleBottom = Math.round(y1 - r);

		if (middleBottom > middleTop) {
			graphics.fill(Math.round(x0), middleTop, Math.round(x1), middleBottom, color);
		}

		for (int i = 0; i < rows; i++) {
			int topRow = top + i;
			int bottomRow = bottom - 1 - i;

			if (topRow >= middleTop && bottomRow < middleBottom) {
				break;
			}

			double dy = r - i - 0.5;
			double inset = r - Math.sqrt(Math.max(0.0, (double) r * r - dy * dy));
			float left = x0 + (float) inset;
			float right = x1 - (float) inset;

			if (topRow < middleTop) {
				row(graphics, left, right, topRow, color);
			}

			if (bottomRow >= middleBottom) {
				row(graphics, left, right, bottomRow, color);
			}
		}
	}

	/** One scanline of a rounded rectangle, with fractional coverage at both ends. */
	private static void row(GuiGraphicsExtractor graphics, float left, float right, int y, int color) {
		if (right <= left) {
			return;
		}

		int l = (int) Math.floor(left);
		int rr = (int) Math.floor(right);

		if (l == rr) {
			graphics.fill(l, y, l + 1, y + 1, alpha(color, right - left));
			return;
		}

		float leftCoverage = l + 1 - left;
		float rightCoverage = right - rr;
		graphics.fill(l, y, l + 1, y + 1,
				leftCoverage >= 0.995F ? color : alpha(color, leftCoverage));

		if (rr > l + 1) {
			graphics.fill(l + 1, y, rr, y + 1, color);
		}

		graphics.fill(rr, y, rr + 1, y + 1,
				rightCoverage >= 0.995F ? color : alpha(color, rightCoverage));
	}


	/** Trims text to a width, ending with an ellipsis, so one long step cannot widen the whole card. */
	private static String fit(String text, float size, float maxWidth) {
		if (text.isEmpty() || FontRenderer.measure(text, size) <= maxWidth) {
			return text;
		}

		String trimmed = text;

		while (trimmed.length() > 4 && FontRenderer.measure(trimmed + "...", size) > maxWidth) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}

		return trimmed + "...";
	}

	/** The item name as a small all-caps label, trimmed so the top row cannot grow. */
	private static String trimAllCaps(String name, int limit) {
		if (name.isBlank()) {
			return "";
		}

		String upper = name.toUpperCase(Locale.ROOT);
		return upper.length() <= limit ? upper : upper.substring(0, limit - 1) + "...";
	}

	private static float pulse01() {
		return (float) ((Math.sin(pulse) + 1.0) * 0.5);
	}

	private static int alpha(int color, float alpha) {
		int base = (color >>> 24) & 0xFF;
		int value = Math.round(Math.max(0.0F, Math.min(1.0F, alpha)) * base);
		return (color & 0x00FFFFFF) | (value << 24);
	}

	private static float approach(float current, float target, float dt, float tau) {
		float step = 1.0F - (float) Math.exp(-Math.max(0.0F, dt) / tau);
		return current + (target - current) * step;
	}

	private static float easeOut(float value) {
		float clamped = Math.max(0.0F, Math.min(1.0F, value));
		float inverse = 1.0F - clamped;
		return 1.0F - inverse * inverse * inverse;
	}
}
