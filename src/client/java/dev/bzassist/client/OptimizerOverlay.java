package dev.bzassist.client;

import java.util.List;
import java.util.Locale;

import dev.bzassist.BzAssist;
import dev.bzassist.client.bazaar.BazaarApi;
import dev.bzassist.client.bazaar.BazaarConfig;
import dev.bzassist.client.bazaar.BazaarScreenTracker;
import dev.bzassist.client.bazaar.BazaarSnapshot;
import dev.bzassist.client.bazaar.Coins;
import dev.bzassist.client.bazaar.Flip;
import dev.bzassist.client.bazaar.ItemNames;
import dev.bzassist.client.bazaar.OptimizerService;
import dev.bzassist.client.bazaar.PlayerState;
import dev.bzassist.client.bazaar.ProfitTracker;
import dev.bzassist.client.font.FontRenderer;
import dev.bzassist.client.mixin.AbstractContainerScreenAccessor;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;

import org.lwjgl.glfw.GLFW;

/**
 * The whole 2.0 interface: a top-20 table shown only while the Bazaar is open.
 *
 * <p>There is no slot outline, no step, no session state and no chat prefill. The panel is placed
 * beside the container when there is room, otherwise it is centred over it. Rows are formatted and
 * measured once per ranking result and GUI scale, not once per frame.</p>
 */
public final class OptimizerOverlay {
	private static final int TEXT = 0xFFF2F5F8;
	private static final int DIM = 0xFF7E858E;
	private static final int LABEL = 0xFF6E757E;
	private static final int VALUE = 0xFFE9ECF0;
	private static final int ACCENT = 0xFF43D97F;
	private static final int WARN = 0xFFFFB020;
	private static final int PANEL = 0xF708090B;
	private static final int BORDER = 0xFF262A30;
	private static final int HAIRLINE = 0xFF23262B;

	private static final float PAD = 8.0F;
	private static final float TITLE_SIZE = 9.0F;
	private static final float LABEL_SIZE = 6.5F;
	private static final float ROW_SIZE = 8.0F;
	private static final float TRACK = 0.5F;
	private static final float ASCENT = 0.75F;
	private static final float ROW_HEIGHT = 9.5F;
	private static final float HEADER_HEIGHT = 34.0F;
	private static final float FOOTER_HEIGHT = 10.0F;
	private static final float MIN_PANEL_WIDTH = 300.0F;
	private static final float MAX_PANEL_WIDTH = 360.0F;

	private record Row(String rank, String item, String buy, String sell, String qty, String profit,
			String fill, float buyWidth, float sellWidth, float qtyWidth, float profitWidth, float fillWidth) {
	}

	private static final FontRenderer.Batch TEXT_BATCH = FontRenderer.batch();
	private static List<Flip> cachedFlips;
	private static float cachedItemMax = -1.0F;
	private static int cachedScale = -1;
	private static Row[] cachedRows = new Row[0];

	private static int metricScale = -1;
	private static float titleWidth;
	private static float headerBuyWidth;
	private static float headerSellWidth;
	private static float headerQtyWidth;
	private static float headerProfitWidth;
	private static float headerFillWidth;

	private static String cachedAgeText;
	private static String cachedAgeDrawn;
	private static long cachedAgeLastUpdated = Long.MIN_VALUE;
	private static long cachedAgeBucket = Long.MIN_VALUE;
	private static float cachedAgeWidth;
	private static int cachedAgeScale = -1;

	private static String cachedFooterRaw;
	private static String cachedFooter = "";
	private static float cachedFooterWidth = -1.0F;
	private static int cachedFooterScale = -1;

	private static String cachedTotalText = "0";
	private static float cachedTotalWidth;
	private static float totalLabelWidth;
	private static double cachedTotalValue = Double.NaN;
	private static int cachedTotalScale = -1;

	private static boolean dragging;
	private static float dragOffsetX;
	private static float dragOffsetY;
	private static float dragLeft;
	private static float dragTop;
	private static boolean boundsValid;
	private static float boundsLeft;
	private static float boundsTop;
	private static float boundsRight;
	private static float boundsBottom;

	private OptimizerOverlay() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			// Only container screens can show the Bazaar panel; do not add a per-frame callback to chat,
			// the HUD, or every other screen in the game.
			if (screen instanceof AbstractContainerScreen<?>) {
				ScreenEvents.afterExtract(screen).register(OptimizerOverlay::afterExtract);
				ScreenMouseEvents.allowMouseClick(screen).register((ignored, event) -> onMouseClick(event));
				ScreenMouseEvents.allowMouseRelease(screen).register((ignored, event) -> onMouseRelease(event));
			}
		});
	}

	private static void afterExtract(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float delta) {
		if (!BazaarScreenTracker.isOpen() || !BazaarConfig.overlay() || !PlayerState.onSkyblock()
				|| !(screen instanceof AbstractContainerScreen<?> container)) {
			boundsValid = false;
			finishDrag();
			return;
		}

		if (!FontRenderer.isReady()) {
			boundsValid = false;
			return;
		}

		try {
			followCursor(mouseX, mouseY);
			draw(graphics, container);
		} catch (RuntimeException exception) {
			boundsValid = false;
			BzAssist.LOGGER.warn("Optimizer overlay draw failed", exception);
		}
	}

	private static void draw(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> container) {
		FontRenderer.Batch batch = TEXT_BATCH;
		batch.reset();
		ensureMetrics();
		Minecraft minecraft = Minecraft.getInstance();
		int screenWidth = minecraft.getWindow().getGuiScaledWidth();
		int screenHeight = minecraft.getWindow().getGuiScaledHeight();
		float panelWidth = Math.min(MAX_PANEL_WIDTH, Math.max(MIN_PANEL_WIDTH, screenWidth - 16.0F));
		float panelHeight = PAD * 2.0F + HEADER_HEIGHT + OptimizerService.TOP_COUNT * ROW_HEIGHT + FOOTER_HEIGHT;
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) (Object) container;
		float left = accessor.bzassist$leftPos();
		float defaultX = left + 176.0F + PAD;

		if (defaultX + panelWidth > screenWidth - PAD) {
			defaultX = left - panelWidth - PAD;
		}
		if (defaultX < PAD) {
			defaultX = Math.max(PAD, (screenWidth - panelWidth) / 2.0F);
		}

		float savedX = BazaarConfig.panelX();
		float savedY = BazaarConfig.panelY();
		float x;
		float y;

		if (dragging) {
			x = dragLeft;
			y = dragTop;
		} else if (savedX >= 0.0F && savedY >= 0.0F) {
			x = savedX;
			y = savedY;
		} else {
			x = defaultX;
			y = Math.max(PAD, (screenHeight - panelHeight) / 2.0F);
		}

		x = clampX(x, panelWidth);
		y = clampY(y, panelHeight);
		boundsLeft = x;
		boundsTop = y;
		boundsRight = x + panelWidth;
		boundsBottom = y + panelHeight;
		boundsValid = true;

		rounded(graphics, x - 1.0F, y - 1.0F, x + panelWidth + 1.0F, y + panelHeight + 1.0F, 9.0F, BORDER);
		rounded(graphics, x, y, x + panelWidth, y + panelHeight, 8.0F, PANEL);

		float titleBaseline = y + PAD + TITLE_SIZE * ASCENT;
		float subtitleBaseline = titleBaseline + 9.0F;
		float dividerY = y + PAD + 20.0F;
		float headerBaseline = y + PAD + 29.0F;
		float rowsTop = y + PAD + HEADER_HEIGHT;
		float contentRight = x + panelWidth - PAD;
		float fillRight = contentRight;
		float profitRight = fillRight - 34.0F;
		float qtyRight = profitRight - 64.0F;
		float sellRight = qtyRight - 28.0F;
		float buyRight = sellRight - 52.0F;
		float itemX = x + 28.0F;
		float itemMax = Math.max(40.0F, buyRight - itemX - 8.0F);

		batch.draw("BZASSIST", x + PAD, titleBaseline, TITLE_SIZE, TEXT, 0.0F);
		batch.draw("TOP 20", x + PAD + titleWidth + 6.0F, titleBaseline, LABEL_SIZE, LABEL, TRACK);
		float totalWidth = drawTotal(batch, contentRight, titleBaseline);
		drawAge(batch, contentRight - totalWidth - 8.0F, titleBaseline);
		batch.draw("BUY ORDER  ·  SELL OFFER  ·  QTY  ·  PROFIT/H  ·  FILL",
				x + PAD, subtitleBaseline, LABEL_SIZE, LABEL, TRACK);

		graphics.fill(Math.round(x + PAD), Math.round(dividerY), Math.round(contentRight),
				Math.round(dividerY) + 1, HAIRLINE);

		batch.draw("#", x + PAD, headerBaseline, LABEL_SIZE, LABEL, TRACK);
		batch.draw("ITEM", itemX, headerBaseline, LABEL_SIZE, LABEL, TRACK);
		drawRight(batch, "BUY", buyRight, headerBaseline, headerBuyWidth, LABEL_SIZE, LABEL, TRACK);
		drawRight(batch, "SELL", sellRight, headerBaseline, headerSellWidth, LABEL_SIZE, LABEL, TRACK);
		drawRight(batch, "QTY", qtyRight, headerBaseline, headerQtyWidth, LABEL_SIZE, LABEL, TRACK);
		drawRight(batch, "PROFIT/H", profitRight, headerBaseline, headerProfitWidth, LABEL_SIZE, LABEL, TRACK);
		drawRight(batch, "FILL", fillRight, headerBaseline, headerFillWidth, LABEL_SIZE, LABEL, TRACK);

		List<Flip> flips = OptimizerService.top();
		Row[] rows = rows(flips, itemMax);

		if (rows.length == 0) {
			batch.draw("Waiting for the bazaar book…", x + PAD, rowsTop + 20.0F, ROW_SIZE, DIM, 0.0F);
		}

		for (int i = 0; i < rows.length; i++) {
			Row row = rows[i];
			float baseline = rowsTop + i * ROW_HEIGHT + ROW_SIZE * ASCENT + 1.0F;

			if ((i & 1) == 1) {
				graphics.fill(Math.round(x + PAD), Math.round(rowsTop + i * ROW_HEIGHT),
						Math.round(contentRight), Math.round(rowsTop + (i + 1) * ROW_HEIGHT), 0x0FFFFFFF);
			}

			batch.draw(row.rank(), x + PAD, baseline, ROW_SIZE, LABEL, 0.0F);
			batch.draw(row.item(), itemX, baseline, ROW_SIZE, TEXT, 0.0F);
			drawRight(batch, row.buy(), buyRight, baseline, row.buyWidth(), VALUE);
			drawRight(batch, row.sell(), sellRight, baseline, row.sellWidth(), VALUE);
			drawRight(batch, row.qty(), qtyRight, baseline, row.qtyWidth(), VALUE);
			drawRight(batch, row.profit(), profitRight, baseline, row.profitWidth(), ACCENT);
			drawRight(batch, row.fill(), fillRight, baseline, row.fillWidth(), DIM);
		}

		float footerBaseline = y + panelHeight - PAD;
		String footer = footerForWidth(contentRight - (x + PAD));

		if (!footer.isEmpty()) {
			int footerColor = OptimizerService.error().isEmpty() && BazaarApi.lastError().isEmpty() ? LABEL : WARN;
			batch.draw(footer, x + PAD, footerBaseline, LABEL_SIZE, footerColor, TRACK);
		}

		batch.submit(graphics);
	}

	/**
	 * Starts a panel drag on a left click inside it, and swallows every click over the panel so the
	 * container behind it never receives the press.
	 */
	private static boolean onMouseClick(MouseButtonEvent event) {
		if (!panelVisible() || !insidePanel(event.x(), event.y())) {
			return true;
		}

		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			dragging = true;
			dragOffsetX = (float) event.x() - boundsLeft;
			dragOffsetY = (float) event.y() - boundsTop;
			dragLeft = boundsLeft;
			dragTop = boundsTop;
		}

		return false;
	}

	private static boolean onMouseRelease(MouseButtonEvent event) {
		if (!dragging) {
			return !panelVisible() || !insidePanel(event.x(), event.y());
		}

		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			moveDrag((float) event.x(), (float) event.y());
			finishDrag();
		}

		return false;
	}

	/**
	 * Moves the panel with the real cursor every frame while it is held. This does not depend on
	 * Fabric's drag event being delivered after the initial click was swallowed.
	 */
	private static void followCursor(double mouseX, double mouseY) {
		if (!dragging) {
			return;
		}

		long window = Minecraft.getInstance().getWindow().handle();

		if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
			finishDrag();
			return;
		}

		moveDrag((float) mouseX, (float) mouseY);
	}

	private static void moveDrag(float mouseX, float mouseY) {
		if (!boundsValid) {
			return;
		}

		dragLeft = clampX(mouseX - dragOffsetX, boundsRight - boundsLeft);
		dragTop = clampY(mouseY - dragOffsetY, boundsBottom - boundsTop);
	}

	/** Persists the panel position once, on release or when the panel stops being drawn. */
	private static void finishDrag() {
		if (!dragging) {
			return;
		}

		dragging = false;
		BazaarConfig.setPanelPosition(dragLeft, dragTop);
	}

	private static boolean panelVisible() {
		return boundsValid && BazaarScreenTracker.isOpen() && BazaarConfig.overlay() && PlayerState.onSkyblock();
	}

	private static boolean insidePanel(double mouseX, double mouseY) {
		return mouseX >= boundsLeft && mouseX <= boundsRight && mouseY >= boundsTop && mouseY <= boundsBottom;
	}

	private static float clampX(float x, float panelWidth) {
		int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		float max = Math.max(PAD, screenWidth - panelWidth - PAD);
		return Math.max(PAD, Math.min(x, max));
	}

	private static float clampY(float y, float panelHeight) {
		int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
		float max = Math.max(PAD, screenHeight - panelHeight - PAD);
		return Math.max(PAD, Math.min(y, max));
	}

	/** Static label widths, measured once per GUI scale. */
	private static void ensureMetrics() {
		int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());

		if (scale == metricScale) {
			return;
		}

		metricScale = scale;
		titleWidth = FontRenderer.measure("BZASSIST", TITLE_SIZE);
		headerBuyWidth = FontRenderer.measure("BUY", LABEL_SIZE, TRACK);
		headerSellWidth = FontRenderer.measure("SELL", LABEL_SIZE, TRACK);
		headerQtyWidth = FontRenderer.measure("QTY", LABEL_SIZE, TRACK);
		headerProfitWidth = FontRenderer.measure("PROFIT/H", LABEL_SIZE, TRACK);
		headerFillWidth = FontRenderer.measure("FILL", LABEL_SIZE, TRACK);
		totalLabelWidth = FontRenderer.measure("TOTAL PROFIT", LABEL_SIZE, TRACK);
	}

	/** Formats and measures the rows once per result list / GUI scale / panel width. */
	private static Row[] rows(List<Flip> flips, float itemMax) {
		int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());

		if (flips == cachedFlips && scale == cachedScale && Math.abs(itemMax - cachedItemMax) < 0.5F) {
			return cachedRows;
		}

		Row[] rows = new Row[flips.size()];

		for (int i = 0; i < flips.size(); i++) {
			Flip flip = flips.get(i);
			String buy = Coins.compact(flip.buyPrice());
			String sell = Coins.compact(flip.sellPrice());
			String qty = Integer.toString(flip.quantity());
			String profit = Coins.compact(flip.profitPerHour()) + "/h";
			String fill = flip.fillText();

			rows[i] = new Row(Integer.toString(i + 1), fit(ItemNames.name(flip.id()), ROW_SIZE, itemMax), buy, sell, qty,
					profit, fill,
					FontRenderer.measure(buy, ROW_SIZE),
					FontRenderer.measure(sell, ROW_SIZE),
					FontRenderer.measure(qty, ROW_SIZE),
					FontRenderer.measure(profit, ROW_SIZE),
					FontRenderer.measure(fill, ROW_SIZE));
		}

		cachedFlips = flips;
		cachedScale = scale;
		cachedItemMax = itemMax;
		cachedRows = rows;
		return rows;
	}

	private static String footerForWidth(float maxWidth) {
		String raw = footerText();
		int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());

		if (raw.equals(cachedFooterRaw) && scale == cachedFooterScale
				&& Math.abs(maxWidth - cachedFooterWidth) < 0.5F) {
			return cachedFooter;
		}

		cachedFooterRaw = raw;
		cachedFooter = fit(raw, LABEL_SIZE, maxWidth);
		cachedFooterWidth = maxWidth;
		cachedFooterScale = scale;
		return cachedFooter;
	}

	private static String footerText() {
		String feedError = BazaarApi.lastError();

		if (!feedError.isEmpty()) {
			return "FEED: " + feedError;
		}

		String error = OptimizerService.error();

		if (!error.isEmpty()) {
			return error;
		}

		if (!PlayerState.purseKnown()) {
			return "PURSE UNKNOWN · SIZES USE A 1M BUDGET";
		}

		return "DATA FROM HYPIXEL · NO AUTOMATION";
	}

	/** The tiny cumulative profit readout, right-aligned on the title line. */
	private static float drawTotal(FontRenderer.Batch batch, float right, float baseline) {
		double value = ProfitTracker.total();
		int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());

		if (scale != cachedTotalScale || Double.compare(value, cachedTotalValue) != 0) {
			cachedTotalValue = value;
			cachedTotalScale = scale;
			cachedTotalText = value > 0.0
					? "+" + Coins.compact(value)
					: value < 0.0 ? Coins.compact(value) : "0";
			cachedTotalWidth = FontRenderer.measure(cachedTotalText, LABEL_SIZE, TRACK);
		}

		int color = value > 0.0 ? ACCENT : value < 0.0 ? WARN : DIM;
		batch.draw("TOTAL PROFIT", right - cachedTotalWidth - 6.0F - totalLabelWidth, baseline,
				LABEL_SIZE, LABEL, TRACK);
		batch.draw(cachedTotalText, right - cachedTotalWidth, baseline, LABEL_SIZE, color, TRACK);
		return totalLabelWidth + 6.0F + cachedTotalWidth;
	}

	private static void drawAge(FontRenderer.Batch batch, float right, float baseline) {
		String text = ageText();
		int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());

		if (scale != cachedAgeScale || text != cachedAgeDrawn) {
			cachedAgeDrawn = text;
			cachedAgeScale = scale;
			cachedAgeWidth = FontRenderer.measure(text, LABEL_SIZE, TRACK);
		}

		drawRight(batch, text, right, baseline, cachedAgeWidth, LABEL_SIZE, DIM, TRACK);
	}

	private static String ageText() {
		BazaarSnapshot snapshot = BazaarApi.snapshot();
		long lastUpdated = snapshot.lastUpdated();

		if (lastUpdated == 0L) {
			cachedAgeText = "NO DATA";
			cachedAgeLastUpdated = 0L;
			cachedAgeBucket = Long.MIN_VALUE;
			return cachedAgeText;
		}

		long age = snapshot.ageMillis();
		long bucket = Math.max(0L, age / 1_000L);

		if (lastUpdated == cachedAgeLastUpdated && bucket == cachedAgeBucket && cachedAgeText != null) {
			return cachedAgeText;
		}

		if (age < 1_000L) {
			cachedAgeText = "NOW";
		} else if (age < 60_000L) {
			cachedAgeText = bucket + "S AGO";
		} else {
			cachedAgeText = (age / 60_000L) + "M AGO";
		}

		cachedAgeLastUpdated = lastUpdated;
		cachedAgeBucket = bucket;
		return cachedAgeText;
	}

	private static void drawRight(FontRenderer.Batch batch, String text, float right, float baseline,
			float width, int color) {
		batch.draw(text, right - width, baseline, ROW_SIZE, color, 0.0F);
	}

	private static void drawRight(FontRenderer.Batch batch, String text, float right, float baseline,
			float width, float size, int color, float tracking) {
		batch.draw(text, right - width, baseline, size, color, tracking);
	}

	private static void drawRight(FontRenderer.Batch batch, String text, float right, float baseline,
			float size, int color, float tracking) {
		batch.draw(text, right - FontRenderer.measure(text, size, tracking), baseline, size, color, tracking);
	}

	/** Trims text to a width, ending with an ellipsis. */
	private static String fit(String text, float size, float maxWidth) {
		if (text == null || text.isEmpty()) {
			return "";
		}

		if (FontRenderer.measure(text, size) <= maxWidth) {
			return text;
		}

		String trimmed = text;

		while (trimmed.length() > 4 && FontRenderer.measure(trimmed + "…", size) > maxWidth) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}

		return trimmed + "…";
	}

	/** A rounded rectangle using one fill for the middle and a scanline per corner row. */
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
		graphics.fill(l, y, l + 1, y + 1, alpha(color, leftCoverage));

		if (rr > l + 1) {
			graphics.fill(l + 1, y, rr, y + 1, color);
		}

		graphics.fill(rr, y, rr + 1, y + 1, alpha(color, rightCoverage));
	}

	private static int alpha(int color, float alpha) {
		int base = (color >>> 24) & 0xFF;
		int value = Math.round(Math.max(0.0F, Math.min(1.0F, alpha)) * base);
		return (color & 0x00FFFFFF) | (value << 24);
	}
}
