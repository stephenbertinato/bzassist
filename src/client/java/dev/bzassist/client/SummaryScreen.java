package dev.bzassist.client;

import java.util.List;
import java.util.Locale;

import dev.bzassist.BzAssist;
import dev.bzassist.client.bazaar.Coins;
import dev.bzassist.client.bazaar.ItemIcons;
import dev.bzassist.client.bazaar.SessionLog;
import dev.bzassist.client.bazaar.SessionStats;
import dev.bzassist.client.font.FontRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The session summary: a full-screen card with four headline figures, a cumulative profit chart, the
 * best and worst item of the session, and three key stats.
 *
 * <p>Laid out at a base size of 560x420 GUI pixels and scaled to fit whatever window it opens in, so it
 * looks the same at GUI scale 2 on a laptop and at scale 3 on a large monitor. Everything fades and
 * rises 6 px when it opens, like the HUD does.</p>
 */
public final class SummaryScreen extends Screen {
	private static final int PAGE_MASK = 0xB8000000;
	private static final int CARD = 0xFA0B0D11;
	private static final int INNER = 0xFF14161B;
	private static final int BORDER = 0xFF23262C;
	private static final int HAIRLINE = 0xFF23262B;
	private static final int TITLE = 0xFFF2F5F8;
	private static final int MUTED = 0xFF8A929C;
	private static final int VALUE = 0xFFE9ECF0;
	private static final int GREEN = 0xFF43D97F;
	private static final int RED = 0xFFFF5C5C;

	private static final float BASE_W = 560.0F;
	private static final float BASE_H = 420.0F;

	private final long openedAt = System.nanoTime();
	private float scale = 1.0F;
	private float originX;
	private float originY;

	public SummaryScreen() {
		super(Component.literal("Session Summary"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		try {
			draw(graphics);
		} catch (RuntimeException exception) {
			SessionLog.failure("summary screen", exception);
			BzAssist.LOGGER.error("Summary screen failed to draw", exception);
		}
	}

	private void draw(GuiGraphicsExtractor graphics) {
		float progress = Math.min(1.0F, (System.nanoTime() - openedAt) / 260.0F / 1.0E9F);
		float ease = easeOut(progress);
		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();

		graphics.fill(0, 0, screenW, screenH, alpha(PAGE_MASK, ease));

		scale = Math.min(1.0F, Math.min((screenW - 24.0F) / BASE_W, (screenH - 24.0F) / BASE_H));
		originX = Math.round((screenW - BASE_W * scale) / 2.0F);
		originY = Math.round((screenH - BASE_H * scale) / 2.0F + (1.0F - ease) * 6.0F);

		float a = ease;
		rounded(graphics, x(-1), y(-1), x(BASE_W + 1), y(BASE_H + 1), sz(13), alpha(BORDER, a));
		rounded(graphics, x(0), y(0), x(BASE_W), y(BASE_H), sz(12), alpha(CARD, a));

		header(graphics, a);
		statCards(graphics, a);
		chart(graphics, a);
		bottom(graphics, a);
	}

	// ------------------------------------------------------------------ header

	private void header(GuiGraphicsExtractor graphics, float a) {
		float icon = sz(30);
		rounded(graphics, x(20), y(18), x(20) + icon, y(18) + icon, sz(7), alpha(INNER, a));
		bars(graphics, x(20), y(18), icon, a);

		FontRenderer.draw(graphics, "Session Summary", x(62), y(18) + sz(17), sz(17), alpha(TITLE, a));
		FontRenderer.draw(graphics, "Your Automatic Bazaar session at a glance.", x(62), y(18) + sz(32),
				sz(9), alpha(MUTED, a));

		String duration = duration(SessionStats.elapsedMs());
		float pillW = sz(26) + FontRenderer.measure(duration, sz(9)) + sz(12);
		float pillX = x(BASE_W - 20) - pillW;
		rounded(graphics, pillX, y(20), x(BASE_W - 20), y(20) + sz(24), sz(12), alpha(INNER, a));
		rounded(graphics, pillX, y(20), x(BASE_W - 20), y(20) + sz(24), sz(12), alpha(BORDER, a));
		clock(graphics, pillX + sz(9), y(20) + sz(12), sz(7), alpha(MUTED, a));
		FontRenderer.draw(graphics, duration, pillX + sz(24), y(20) + sz(15), sz(9), alpha(VALUE, a));
	}

	/** Four bars and a baseline: the session-summary mark, drawn rather than textured. */
	private void bars(GuiGraphicsExtractor graphics, float x, float y, float box, float a) {
		float base = y + box * 0.72F;
		float[] heights = {0.30F, 0.52F, 0.40F, 0.62F};
		float barW = box * 0.10F;

		for (int i = 0; i < heights.length; i++) {
			float barX = x + box * (0.20F + i * 0.17F);
			float top = base - box * heights[i];
			graphics.fill(Math.round(barX), Math.round(top), Math.round(barX + barW), Math.round(base),
					alpha(GREEN, a));
		}

		graphics.fill(Math.round(x + box * 0.16F), Math.round(base), Math.round(x + box * 0.84F),
				Math.round(base) + Math.max(1, Math.round(sz(1))), alpha(GREEN, a));
	}

	private void clock(GuiGraphicsExtractor graphics, float cx, float cy, float radius, int color) {
		int left = Math.round(cx - radius);
		int top = Math.round(cy - radius);
		int right = Math.round(cx + radius);
		int bottom = Math.round(cy + radius);
		graphics.outline(left, top, right - left, bottom - top, color);
		graphics.fill(Math.round(cx), Math.round(cy - radius * 0.6F), Math.round(cx) + 1, Math.round(cy) + 1, color);
		graphics.fill(Math.round(cx), Math.round(cy), Math.round(cx + radius * 0.6F), Math.round(cy) + 1, color);
	}

	// ------------------------------------------------------------------ stat cards

	private void statCards(GuiGraphicsExtractor graphics, float a) {
		float gap = 10.0F;
		float width = (BASE_W - 40.0F - gap * 3.0F) / 4.0F;
		float top = 66.0F;
		float height = 74.0F;
		double elapsedHours = SessionStats.elapsedMs() / 3_600_000.0;

		String[] labels = {"Total Profit", "Total Sales", "Total Buys", "Net Margin"};
		String[] values = {
				signed(SessionStats.profit()),
				Integer.toString(SessionStats.sells()),
				Integer.toString(SessionStats.buys()),
				signedPercent(SessionStats.netMargin() * 100.0)};
		String[] subs = {
				"≈ " + signedPerHour(SessionStats.profit() / Math.max(elapsedHours, 1.0E-6)),
				"≈ " + Coins.compact(SessionStats.sells() * SessionStats.averageProfitPerSale()
						/ Math.max(elapsedHours, 1.0E-6)) + "/h",
				"≈ " + Coins.compact(SessionStats.spent() / Math.max(elapsedHours, 1.0E-6)) + "/h",
				""};
		boolean[] green = {SessionStats.profit() >= 0.0, false, false, SessionStats.netMargin() >= 0.0};

		for (int i = 0; i < labels.length; i++) {
			float cardX = 20.0F + i * (width + gap);
			rounded(graphics, x(cardX), y(top), x(cardX + width), y(top + height), sz(8), alpha(INNER, a));
			rounded(graphics, x(cardX), y(top), x(cardX + width), y(top + height), sz(8), alpha(BORDER, a));
			FontRenderer.draw(graphics, labels[i], x(cardX + 12), y(top + 18), sz(8), alpha(MUTED, a));
			FontRenderer.draw(graphics, values[i], x(cardX + 12), y(top + 44), sz(17),
					alpha(green[i] ? GREEN : VALUE, a));

			if (!subs[i].isEmpty()) {
				FontRenderer.draw(graphics, subs[i], x(cardX + 12), y(top + 60), sz(8), alpha(MUTED, a));
			}
		}
	}

	// ------------------------------------------------------------------ chart

	private void chart(GuiGraphicsExtractor graphics, float a) {
		float cardX = 20.0F;
		float top = 150.0F;
		float width = BASE_W - 40.0F;
		float height = 156.0F;
		rounded(graphics, x(cardX), y(top), x(cardX + width), y(top + height), sz(8), alpha(INNER, a));
		rounded(graphics, x(cardX), y(top), x(cardX + width), y(top + height), sz(8), alpha(BORDER, a));
		FontRenderer.draw(graphics, "Profit Over Time", x(cardX + 14), y(top + 16), sz(10), alpha(TITLE, a));

		List<SessionStats.Point> series = SessionStats.series();

		if (series.size() < 2) {
			FontRenderer.draw(graphics, "Not enough data yet", x(cardX + 14), y(top + 44), sz(9),
					alpha(MUTED, a));
			return;
		}

		float plotX0 = cardX + 46.0F;
		float plotX1 = cardX + width - 16.0F;
		float plotY0 = top + 32.0F;
		float plotY1 = top + height - 24.0F;
		double max = 0.0;

		for (SessionStats.Point point : series) {
			max = Math.max(max, point.profit());
		}

		double nice = niceMax(max);
		long duration = Math.max(1L, SessionStats.elapsedMs());

		for (int step = 0; step <= 2; step++) {
			float gy = plotY1 - (plotY1 - plotY0) * step / 2.0F;
			graphics.fill(Math.round(x(plotX0)), Math.round(y(gy)), Math.round(x(plotX1)), Math.round(y(gy)) + 1,
					alpha(HAIRLINE, a));
			String label = Coins.compact(nice * step / 2.0);
			FontRenderer.draw(graphics, label, x(plotX0 - 8) - FontRenderer.measure(label, sz(8)),
					y(gy) + sz(5), sz(8), alpha(MUTED, a));
		}

		int columns = Math.max(1, Math.round((plotX1 - plotX0) * scale));
		float baseline = y(plotY1);

		for (int i = 0; i <= columns; i++) {
			float gx = x(plotX0) + i;
			long at = Math.round(duration * (i / (double) columns));
			double value = valueAt(series, at);
			float gy = y((float) (plotY1 - (plotY1 - plotY0) * (nice <= 0.0 ? 0.0 : value / nice)));

			if (gy < baseline) {
				graphics.fill(Math.round(gx), Math.round(gy), Math.round(gx) + 1, Math.round(baseline),
						alpha(0x2E43D97F, a));
				graphics.fill(Math.round(gx), Math.round(gy), Math.round(gx) + 1, Math.round(gy) + Math.max(1,
						Math.round(sz(1))), alpha(GREEN, a));
			}
		}

		// End marker.
		float endY = y((float) (plotY1 - (plotY1 - plotY0) * (nice <= 0.0 ? 0.0 : max / nice)));
		float endX = x(plotX1);
		graphics.fill(Math.round(endX) - 2, Math.round(endY) - 2, Math.round(endX) + 2, Math.round(endY) + 2,
				alpha(GREEN, a));

		// Time axis: start, three quarters, end.
		for (int i = 0; i <= 4; i++) {
			long at = duration * i / 4L;
			String label = clockLabel(at);
			float lx = x(plotX0) + (x(plotX1) - x(plotX0)) * i / 4.0F - (i == 0 ? 0.0F
					: i == 4 ? FontRenderer.measure(label, sz(8)) : FontRenderer.measure(label, sz(8)) / 2.0F);
			FontRenderer.draw(graphics, label, lx, y(plotY1 + 12), sz(8), alpha(MUTED, a));
		}
	}

	private static double valueAt(List<SessionStats.Point> series, long at) {
		SessionStats.Point previous = series.get(0);

		for (SessionStats.Point point : series) {
			if (point.elapsedMs() >= at) {
				long span = point.elapsedMs() - previous.elapsedMs();

				if (span <= 0L) {
					return point.profit();
				}

				double t = (at - previous.elapsedMs()) / (double) span;
				return previous.profit() + (point.profit() - previous.profit()) * t;
			}

			previous = point;
		}

		return series.get(series.size() - 1).profit();
	}

	// ------------------------------------------------------------------ bottom row

	private void bottom(GuiGraphicsExtractor graphics, float a) {
		float gap = 10.0F;
		float width = (BASE_W - 40.0F - gap * 2.0F) / 3.0F;
		float top = 316.0F;
		float height = 88.0F;

		SessionStats.Product best = SessionStats.best();
		SessionStats.Product worst = SessionStats.worst();

		itemCard(graphics, a, 20.0F, top, width, height, "Best Item", best, true);
		itemCard(graphics, a, 20.0F + width + gap, top, width, height, "Worst Item", worst, false);

		float statsX = 20.0F + (width + gap) * 2.0F;
		rounded(graphics, x(statsX), y(top), x(statsX + width), y(top + height), sz(8), alpha(INNER, a));
		rounded(graphics, x(statsX), y(top), x(statsX + width), y(top + height), sz(8), alpha(BORDER, a));
		FontRenderer.draw(graphics, "Key Stats", x(statsX + 12), y(top + 18), sz(9), alpha(TITLE, a));

		String[][] rows = {
				{"Average Profit / Sale", Coins.compact(SessionStats.averageProfitPerSale())},
				{"Flip Success Rate", String.format(Locale.ROOT, "%.1f%%", SessionStats.successRate() * 100.0)},
				{"Total Flips", Integer.toString(SessionStats.flips())}};

		for (int i = 0; i < rows.length; i++) {
			float rowY = top + 34.0F + i * 17.0F;
			FontRenderer.draw(graphics, rows[i][0], x(statsX + 12), y(rowY), sz(8), alpha(MUTED, a));
			FontRenderer.draw(graphics, rows[i][1],
					x(statsX + width - 12) - FontRenderer.measure(rows[i][1], sz(8)), y(rowY), sz(8),
					alpha(VALUE, a));
		}
	}

	private void itemCard(GuiGraphicsExtractor graphics, float a, float left, float top, float width, float height,
			String label, SessionStats.Product product, boolean best) {
		rounded(graphics, x(left), y(top), x(left + width), y(top + height), sz(8), alpha(INNER, a));
		rounded(graphics, x(left), y(top), x(left + width), y(top + height), sz(8), alpha(BORDER, a));
		FontRenderer.draw(graphics, label, x(left + 12), y(top + 18), sz(9), alpha(TITLE, a));

		if (product == null) {
			FontRenderer.draw(graphics, "None", x(left + 12), y(top + 46), sz(10), alpha(MUTED, a));
			return;
		}

		float iconSize = sz(26);
		rounded(graphics, x(left + 12), y(top + 32), x(left + 12) + iconSize, y(top + 32) + iconSize, sz(6),
				alpha(0xFF1B1E24, a));
		ItemStack icon = ItemIcons.icon(product.id());

		try {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x(left + 12) + iconSize / 2.0F - sz(8), y(top + 32) + iconSize / 2.0F - sz(8));
			graphics.pose().scale(scale);
			graphics.item(icon, 0, 0);
			graphics.pose().popMatrix();
		} catch (RuntimeException exception) {
			SessionLog.failure("summary icon", exception);
		}

		float textX = left + 12.0F + 34.0F;
		FontRenderer.draw(graphics, trim(product.name(), 18), x(textX), y(top + 46), sz(9), alpha(VALUE, a));
		String amount = signed(product.profit());
		FontRenderer.draw(graphics, amount, x(textX), y(top + 62), sz(12), alpha(best ? GREEN : RED, a));
		String count = product.soldOrders() > 0
				? "(" + product.soldOrders() + " sold)"
				: "(" + product.boughtOrders() + " bought)";
		FontRenderer.draw(graphics, count, x(textX) + FontRenderer.measure(amount, sz(12)) + sz(4),
				y(top + 62), sz(8), alpha(MUTED, a));
	}

	// ------------------------------------------------------------------ helpers

	private float x(float value) {
		return originX + value * scale;
	}

	private float y(float value) {
		return originY + value * scale;
	}

	private float sz(float value) {
		return value * scale;
	}

	private static String signed(double value) {
		return (value >= 0.0 ? "+" : "") + Coins.compact(value);
	}

	private static String signedPerHour(double value) {
		return (value >= 0.0 ? "+" : "") + Coins.compact(value) + "/h";
	}

	private static String signedPercent(double value) {
		return String.format(Locale.ROOT, "%s%.1f%%", value >= 0.0 ? "+" : "", value);
	}

	private static double niceMax(double value) {
		if (value <= 0.0) {
			return 1.0;
		}

		double magnitude = Math.pow(10.0, Math.floor(Math.log10(value)));
		double scaled = value / magnitude;
		double step = scaled <= 1.0 ? 1.0 : scaled <= 2.0 ? 2.0 : scaled <= 5.0 ? 5.0 : 10.0;
		return step * magnitude;
	}

	private static String duration(long millis) {
		long minutes = Math.max(0L, millis / 60_000L);
		return minutes >= 60L ? (minutes / 60L) + "h " + (minutes % 60L) + "m" : minutes + "m";
	}

	private static String clockLabel(long millis) {
		long minutes = Math.max(0L, millis / 60_000L);
		return minutes >= 60L ? (minutes / 60L) + "h" + (minutes % 60L == 0L ? "" : " " + (minutes % 60L) + "m")
				: minutes + "m";
	}

	private static String trim(String text, int limit) {
		return text.length() <= limit ? text : text.substring(0, limit - 1) + "...";
	}

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


	private static int alpha(int color, float value) {
		int base = (color >>> 24) & 0xFF;
		int a = Math.round(Math.max(0.0F, Math.min(1.0F, value)) * base);
		return (color & 0x00FFFFFF) | (a << 24);
	}

	private static float easeOut(float value) {
		float clamped = Math.max(0.0F, Math.min(1.0F, value));
		float inverse = 1.0F - clamped;
		return 1.0F - inverse * inverse * inverse;
	}
}
