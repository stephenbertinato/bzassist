package dev.bzassist.client.font;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Bitmap;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_GlyphSlot;
import org.lwjgl.util.freetype.FT_Vector;
import org.lwjgl.util.freetype.FreeType;

/**
 * A TrueType font rasterised by FreeType, the same shape of implementation font mods like Caxton
 * use: glyphs are rendered as real hinted coverage bitmaps at the exact pixel size they are drawn
 * at, instead of being reconstructed from a distance field.
 *
 * <p>One {@link Face} exists per pixel size, because FreeType stores the size on the face. All faces
 * share the same in-memory font file.</p>
 */
public final class TtfFont {
	/** A rasterised glyph: coverage bitmap plus the metrics needed to place and advance it. */
	public record Glyph(float advance, int left, int top, int width, int height, byte[] coverage) {
	}

	private final long library;
	private final ByteBuffer data;
	private final Map<Integer, Face> faces = new HashMap<>();

	private TtfFont(long library, ByteBuffer data) {
		this.library = library;
		this.data = data;
	}

	public static TtfFont load(InputStream stream) throws IOException {
		byte[] bytes = stream.readAllBytes();
		ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
		buffer.put(bytes).flip();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer libraryPointer = stack.mallocPointer(1);
			int error = FreeType.FT_Init_FreeType(libraryPointer);

			if (error != 0) {
				MemoryUtil.memFree(buffer);
				throw new IOException("FT_Init_FreeType failed with error " + error);
			}

			return new TtfFont(libraryPointer.get(0), buffer);
		}
	}

	/** The face for {@code pixelSize}, created on first use. */
	public Face atSize(int pixelSize) {
		return faces.computeIfAbsent(pixelSize, this::createFace);
	}

	private Face createFace(int pixelSize) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer facePointer = stack.mallocPointer(1);
			int error = FreeType.FT_New_Memory_Face(library, data, 0L, facePointer);

			if (error != 0) {
				throw new IllegalStateException("FT_New_Memory_Face failed with error " + error);
			}

			FT_Face face = FT_Face.create(facePointer.get(0));

			if (FreeType.FT_Set_Pixel_Sizes(face, 0, pixelSize) != 0) {
				throw new IllegalStateException("FT_Set_Pixel_Sizes failed for " + pixelSize + "px");
			}

			return new Face(face, pixelSize);
		}
	}

	/** A font loaded at one pixel size. */
	public static final class Face {
		private static final int KERNING_SLOTS = 4096;
		private static final int KERNING_LIMIT = 3072;

		private final FT_Face face;
		private final int pixelSize;
		private final Map<Integer, Glyph> glyphs = new HashMap<>();
		private final long[] kerningKeys = new long[KERNING_SLOTS];
		private final float[] kerningValues = new float[KERNING_SLOTS];
		private final boolean[] kerningUsed = new boolean[KERNING_SLOTS];
		private int kerningCount;

		private Face(FT_Face face, int pixelSize) {
			this.face = face;
			this.pixelSize = pixelSize;
		}

		public int pixelSize() {
			return pixelSize;
		}

		/** Distance from the baseline to the top of the tallest glyph, in pixels. */
		public float ascender() {
			return face.ascender() / 64.0F;
		}

		public float descender() {
			return face.descender() / 64.0F;
		}

		public float lineHeight() {
			return face.height() / 64.0F;
		}

		/**
		 * The font's own kerning adjustment between two codepoints, in pixels. Without this every pair
		 * is spaced by its advance alone, which is why letter pairs like "AV", "To" and "ry" look
		 * uneven compared with real typesetting.
		 */
		public float kerning(int left, int right) {
			long key = ((long) left << 32) | (right & 0xFFFFFFFFL);
			int slot = (int) (key ^ (key >>> 33)) & (KERNING_SLOTS - 1);

			while (kerningUsed[slot]) {
				if (kerningKeys[slot] == key) {
					return kerningValues[slot];
				}

				slot = (slot + 1) & (KERNING_SLOTS - 1);
			}

			float value = loadKerning(left, right);

			// Text uses a small alphabet per size; if a pathological screen fills the table, fall
			// back to computing without caching rather than growing or evicting entries.
			if (kerningCount < KERNING_LIMIT) {
				kerningKeys[slot] = key;
				kerningValues[slot] = value;
				kerningUsed[slot] = true;
				kerningCount++;
			}

			return value;
		}

		private float loadKerning(int left, int right) {
			int leftIndex = FreeType.FT_Get_Char_Index(face, left);
			int rightIndex = FreeType.FT_Get_Char_Index(face, right);

			if (leftIndex == 0 || rightIndex == 0) {
				return 0.0F;
			}

			FT_Vector vector = FT_Vector.malloc();

			try {
				if (FreeType.FT_Get_Kerning(face, leftIndex, rightIndex, FreeType.FT_KERNING_DEFAULT, vector) != 0) {
					return 0.0F;
				}

				return vector.x() / 64.0F;
			} catch (RuntimeException exception) {
				return 0.0F;
			} finally {
				vector.free();
			}
		}

		/** Rasterises a codepoint once per face, or returns {@code null} when the font lacks it. */
		public Glyph glyph(int codepoint) {
			Glyph cached = glyphs.get(codepoint);

			if (cached != null) {
				return cached;
			}

			Glyph glyph = loadGlyph(codepoint);

			if (glyph != null) {
				glyphs.put(codepoint, glyph);
			}

			return glyph;
		}

		private Glyph loadGlyph(int codepoint) {
			if (FreeType.FT_Load_Char(face, codepoint, FreeType.FT_LOAD_RENDER) != 0) {
				return null;
			}

			FT_GlyphSlot slot = face.glyph();
			FT_Bitmap bitmap = slot.bitmap();
			int width = bitmap.width();
			int rows = bitmap.rows();
			byte[] coverage = new byte[Math.max(0, width * rows)];

			if (width > 0 && rows > 0) {
				int pitch = bitmap.pitch();
				ByteBuffer pixels = bitmap.buffer(rows * pitch);

				for (int row = 0; row < rows; row++) {
					for (int col = 0; col < width; col++) {
						coverage[row * width + col] = pixels.get(row * pitch + col);
					}
				}
			}

			float advance = slot.metrics().horiAdvance() / 64.0F;
			return new Glyph(advance, slot.bitmap_left(), slot.bitmap_top(), width, rows, coverage);
		}
	}
}
