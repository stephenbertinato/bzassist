# Text rendering

The client draws text with a real font rasteriser (FreeType, which Minecraft 26.2 already ships)
rather than with a distance field, which is how font mods such as Caxton produce their output.

```java
FontRenderer.draw(graphics, "Hello", 8, 24, 16, 0xFFFFFFFF);  // x, baseline, size in GUI px
float width = FontRenderer.measure("Hello", 16);
```

## How it works

1. `TtfFont` loads a TTF from the mod's resources into native memory and creates one FreeType face
   per pixel size (FreeType stores the size on the face).
2. `GlyphAtlas` rasterises a codepoint on first use, packs the coverage bitmap into one 1024×1024
   image and uploads it as a single GPU texture. Glyphs are cached per `(pixel size, codepoint)`.
3. `FontRenderer` turns the string into quads and submits them through the GUI render state with
   vanilla's own `GUI_TEXTURED` pipeline, sampling the atlas through a linear, clamped sampler.
4. `FontQuadRenderState` is the submitted batch; it snapshots the current pose so it can be drawn
   later in the frame.

Because glyphs are rasterised at their **device** pixel size (`sizeGuiPx × GUI scale`), each glyph
texel maps to exactly one screen pixel at any GUI scale. FreeType hints every glyph for that pixel
grid, so curves, joins and numerals come out the way a normal UI font renders them — no blurring
from scaling a bitmap, and no reconstruction artefacts at corners.

## Adding a font

Drop a TTF under `src/main/resources/assets/<namespace>/fonts/`, then load it in `Fonts`:

```java
TtfFont mono = TtfFont.load(resource.open());   // resource: assets/bzassist/fonts/whatever.ttf
```

Note that resource paths must be lowercase (`inter_semibold.ttf`, never `Inter-SemiBold.ttf`) or
`Identifier` throws on construction.

Fonts are keyed by pixel size, so asking for 12 px and 32 px gives you two independent, correctly
hinted rasterisations instead of one upscaled one.

## Why not MSDF

The mod started with a hand-written MSDF generator. It was dropped after testing on real frames:

* acute joins (the apex of `A`, the waist of `s`) collapsed into notches,
* self-intersecting outlines like the numeral `4` produced holes,
* small glyphs like `·` came out as crosses,
* and bilinear/point sampling of a field is far less forgiving than a real hinted bitmap.

Screenshots of the artefacts are in the chat history; the distance-field code is gone rather than
kept around half-working.

## Limits

* Text is rasterised at the rounded device pixel size, so fractional sizes snap to whole pixels
  (that is what keeps it crisp).
* No kerning yet: advances come from the glyph's own `horiAdvance`.
* One atlas of 1024×1024 per font; once it fills up, new glyphs are skipped until the game restarts.
  That is far more than a UI needs (~4000 glyphs at 16 px).
* GUI/HUD only (screens, overlays); not in-world name tags.
* `-Dbzassist.debugScreenshot=1` (JAVA_TOOL_OPTIONS) still exists for capturing a frame to
  `run/screenshots` when you need to inspect the result without screen recording permissions.

## Bundled font

Inter SemiBold (static instance of Inter Variable, SIL Open Font License 1.1 — see
`tools/fonts/OFL.txt`), shipped at `assets/bzassist/fonts/inter_semibold.ttf`.
