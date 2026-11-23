package dev.bzassist.client.font;

import java.util.HashMap;
import java.util.Map;

import dev.bzassist.BzAssist;
import dev.bzassist.client.mixin.GuiGraphicsExtractorAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/**
 * Draws text by rasterising glyphs at the exact device pixel size they are shown at and submitting
 * them as textured quads with vanilla's own GUI pipeline.
 *
 * <p>Rasterising per size (rather than scaling one bitmap or a distance field) is what makes the
 * result look like real UI text: FreeType hints each glyph for its actual pixel grid, and because
 * the atlas is keyed by device pixels, the glyph is always drawn one texel to one screen pixel no
 * matter the GUI scale.</p>
 *
 * <pre>{@code
 * FontRenderer.draw(graphics, "Hello", 8, 24, 16, 0xFFFFFFFF);
 * }</pre>
 */
public final class FontRenderer {
	private static final Map<TtfFont, GlyphAtlas> ATLASES = new HashMap<>();
	private static boolean loggedFirstDraw;

	private FontRenderer() {
	}

	/** Draws {@code text} and returns the x where the next character would start (GUI pixels). */
	public static float draw(GuiGraphicsExtractor graphics, String text, float x, float baselineY,
			float sizeGuiPx, int argb) {
		return draw(graphics, text, x, baselineY, sizeGuiPx, argb, 0.0F);
	}

	/**
	 * Draws {@code text} with extra space between glyphs (tracking), which is what makes small
	 * all-caps labels read as labels instead of as cramped uppercase words.
	 */
	public static float draw(GuiGraphicsExtractor graphics, String text, float x, float baselineY,
			float sizeGuiPx, int argb, float tracking) {
		TtfFont font = Fonts.defaultFont();

		if (font == null || text.isEmpty()) {
			return x;
		}

		Minecraft minecraft = Minecraft.getInstance();
		int guiScale = Math.max(1, minecraft.getWindow().getGuiScale());
		int pixelSize = Math.max(4, Math.round(sizeGuiPx * guiScale));
		TtfFont.Face face = font.atSize(pixelSize);
		float toGui = 1.0F / guiScale;
		GlyphAtlas atlas = ATLASES.computeIfAbsent(font, key -> new GlyphAtlas());

		Quads quads = new Quads();
		float penX = x;
		float penY = baselineY;
		int previous = -1;
		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;

		for (int i = 0; i < text.length(); ) {
			int codepoint = text.codePointAt(i);
			i += Character.charCount(codepoint);

			if (codepoint == '\n') {
				penX = x;
				penY += face.lineHeight() * toGui;
				previous = -1;
				continue;
			}

			if (previous >= 0) {
				penX += face.kerning(previous, codepoint) * toGui;
			}

			previous = codepoint;
			GlyphAtlas.Region region = atlas.region(face, codepoint);

			if (region == null) {
				continue;
			}

			if (region.width() > 0 && region.height() > 0) {
				// Land the glyph on whole device pixels: a glyph drawn at a fractional position is
				// resampled by the texture filter and comes out soft.
				float snappedX = Math.round(penX * guiScale) / (float) guiScale;
				float snappedY = Math.round(penY * guiScale) / (float) guiScale;
				float x0 = snappedX + region.left() * toGui;
				float y0 = snappedY - region.top() * toGui;
				float x1 = x0 + region.width() * toGui;
				float y1 = y0 + region.height() * toGui;
				quads.add(x0, y0, x1, y1, region.u0(), region.v0(), region.u1(), region.v1());
				minX = Math.min(minX, x0);
				minY = Math.min(minY, y0);
				maxX = Math.max(maxX, x1);
				maxY = Math.max(maxY, y1);
			}

			penX += region.advance() * toGui + tracking;
		}

		if (quads.isEmpty()) {
			return penX;
		}

		atlas.upload();

		if (atlas.view() == null) {
			return penX;
		}

		GuiRenderState state = ((GuiGraphicsExtractorAccessor) (Object) graphics).bzassist$guiRenderState();
		ScreenRectangle bounds = new ScreenRectangle((int) Math.floor(minX), (int) Math.floor(minY),
				Math.max(1, (int) Math.ceil(maxX - minX)), Math.max(1, (int) Math.ceil(maxY - minY)));
		state.addGuiElement(new FontQuadRenderState(RenderPipelines.GUI_TEXTURED,
				TextureSetup.singleTexture(atlas.view(), atlas.sampler()),
				FontQuadRenderState.snapshot(graphics.pose()), quads.toArray(), argb, null, bounds));

		if (!loggedFirstDraw) {
			loggedFirstDraw = true;
			BzAssist.LOGGER.info("Rendered first text batch: {} glyphs at {}px (GUI scale {})",
					quads.count() / 8, pixelSize, guiScale);
		}

		return penX;
	}

	/** Width of {@code text} in GUI pixels at the given size. */
	public static float measure(String text, float sizeGuiPx) {
		return measure(text, sizeGuiPx, 0.0F);
	}

	/** Width of {@code text} at the given size and tracking. */
	public static float measure(String text, float sizeGuiPx, float tracking) {
		TtfFont font = Fonts.defaultFont();

		if (font == null) {
			return 0.0F;
		}

		int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
		TtfFont.Face face = font.atSize(Math.max(4, Math.round(sizeGuiPx * guiScale)));
		float toGui = 1.0F / guiScale;
		float width = 0.0F;
		int glyphs = 0;
		int previous = -1;

		for (int i = 0; i < text.length(); ) {
			int codepoint = text.codePointAt(i);
			i += Character.charCount(codepoint);

			if (previous >= 0) {
				width += face.kerning(previous, codepoint) * toGui;
			}

			previous = codepoint;
			TtfFont.Glyph glyph = face.glyph(codepoint);

			if (glyph != null) {
				width += glyph.advance() * toGui + tracking;
				glyphs++;
			}
		}

		// Tracking sits between glyphs, not after the last one.
		return glyphs > 0 ? Math.max(0.0F, width - tracking) : 0.0F;
	}

	/** Minimal growable float buffer (8 floats per quad). */
	private static final class Quads {
		private float[] data = new float[8 * 16];
		private int size;

		void add(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) {
			if (size + 8 > data.length) {
				float[] grown = new float[data.length * 2];
				System.arraycopy(data, 0, grown, 0, size);
				data = grown;
			}

			data[size++] = x0;
			data[size++] = y0;
			data[size++] = x1;
			data[size++] = y1;
			data[size++] = u0;
			data[size++] = v0;
			data[size++] = u1;
			data[size++] = v1;
		}

		boolean isEmpty() {
			return size == 0;
		}

		int count() {
			return size;
		}

		float[] toArray() {
			float[] copy = new float[size];
			System.arraycopy(data, 0, copy, 0, size);
			return copy;
		}
	}
}
