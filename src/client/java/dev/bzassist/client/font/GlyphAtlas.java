package dev.bzassist.client.font;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * A growable glyph cache: rasterised glyphs are packed into one image and uploaded as a single
 * texture, which is what keeps drawing cheap after the first frame of a given size.
 *
 * <p>Glyphs are keyed by pixel size and codepoint, so text drawn at 16 px and 32 px stays sharp
 * independently - the size the text is rasterised at is the size it is displayed at.</p>
 */
public final class GlyphAtlas implements AutoCloseable {
	/** Where a glyph sits in the atlas, in both texture and pixel terms. */
	public record Region(float u0, float v0, float u1, float v1,
			int width, int height, int left, int top, float advance) {
	}

	private static final int ATLAS_SIZE = 1024;
	private static final int PADDING = 1;
	private static final int FAST_CACHE_SIZE = 8192;
	private static final int FAST_CACHE_LIMIT = 6144;

	private final NativeImage image = new NativeImage(NativeImage.Format.RGBA, ATLAS_SIZE, ATLAS_SIZE, true);
	private final Map<Long, Region> regions = new HashMap<>();
	private final long[] fastKeys = new long[FAST_CACHE_SIZE];
	private final Region[] fastValues = new Region[FAST_CACHE_SIZE];
	private final boolean[] fastUsed = new boolean[FAST_CACHE_SIZE];
	private int fastCount;

	private int penX = PADDING;
	private int penY = PADDING;
	private int rowHeight;
	private boolean dirty = true;

	private GpuTexture texture;
	private GpuTextureView view;
	private GpuSampler sampler;

	/** The region for a codepoint at a face's pixel size, rasterising and packing it on first use. */
	public Region region(TtfFont.Face face, int codepoint) {
		long key = ((long) face.pixelSize() << 32) | (codepoint & 0xFFFFFFFFL);
		int slot = (int) (key ^ (key >>> 33)) & (FAST_CACHE_SIZE - 1);

		while (fastUsed[slot]) {
			if (fastKeys[slot] == key) {
				return fastValues[slot];
			}

			slot = (slot + 1) & (FAST_CACHE_SIZE - 1);
		}

		Region existing = regions.get(key);

		if (existing != null) {
			cacheFast(key, existing);
			return existing;
		}

		TtfFont.Glyph glyph = face.glyph(codepoint);

		if (glyph == null) {
			return null;
		}

		Region region = pack(glyph);

		if (region != null) {
			regions.put(key, region);
			cacheFast(key, region);
		}

		return region;
	}

	/** A primitive hit cache in front of the map, so steady-state frames do not box keys. */
	private void cacheFast(long key, Region region) {
		if (fastCount >= FAST_CACHE_LIMIT) {
			return;
		}

		int slot = (int) (key ^ (key >>> 33)) & (FAST_CACHE_SIZE - 1);

		while (fastUsed[slot]) {
			if (fastKeys[slot] == key) {
				return;
			}

			slot = (slot + 1) & (FAST_CACHE_SIZE - 1);
		}

		fastKeys[slot] = key;
		fastValues[slot] = region;
		fastUsed[slot] = true;
		fastCount++;
	}

	private Region pack(TtfFont.Glyph glyph) {
		int width = glyph.width();
		int height = glyph.height();

		if (width > ATLAS_SIZE - 2 * PADDING || height > ATLAS_SIZE - 2 * PADDING) {
			return null;
		}

		if (width > 0 && height > 0) {
			if (penX + width + PADDING > ATLAS_SIZE) {
				penX = PADDING;
				penY += rowHeight + PADDING;
				rowHeight = 0;
			}

			if (penY + height + PADDING > ATLAS_SIZE) {
				return null; // Atlas is full for now; the glyph simply is not drawn.
			}

			blit(glyph, penX, penY);
			rowHeight = Math.max(rowHeight, height);
		}

		float u0 = (float) penX / ATLAS_SIZE;
		float v0 = (float) penY / ATLAS_SIZE;
		Region region = new Region(u0, v0,
				(float) (penX + width) / ATLAS_SIZE, (float) (penY + height) / ATLAS_SIZE,
				width, height, glyph.left(), glyph.top(), glyph.advance());

		if (width > 0 && height > 0) {
			penX += width + PADDING;
		}

		return region;
	}

	private void blit(TtfFont.Glyph glyph, int originX, int originY) {
		byte[] coverage = glyph.coverage();
		int width = glyph.width();
		int height = glyph.height();

		for (int row = 0; row < height; row++) {
			for (int col = 0; col < width; col++) {
				int alpha = coverage[row * width + col] & 0xFF;

				if (alpha != 0) {
					// White glyph, coverage in alpha: the pipeline multiplies by the vertex colour.
					image.setPixelABGR(originX + col, originY + row, (alpha << 24) | 0xFFFFFF);
				}
			}
		}

		dirty = true;
	}

	/** Makes sure the texture exists and matches the current image. */
	public void upload() {
		if (texture == null) {
			texture = RenderSystem.getDevice().createTexture(() -> "bzassist glyph atlas",
					GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
					GpuFormat.RGBA8_UNORM, ATLAS_SIZE, ATLAS_SIZE, 1, 1);
			view = RenderSystem.getDevice().createTextureView(texture);
			sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}

		if (dirty) {
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image);
			dirty = false;
		}
	}

	public GpuTextureView view() {
		return view;
	}

	public GpuSampler sampler() {
		return sampler;
	}

	@Override
	public void close() {
		image.close();

		if (texture != null) {
			texture.close();
		}
	}
}
