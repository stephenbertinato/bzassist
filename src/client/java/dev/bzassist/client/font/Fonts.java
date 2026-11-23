package dev.bzassist.client.font;

import java.io.InputStream;

import dev.bzassist.BzAssist;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/** Holds the bundled fonts. */
public final class Fonts {
	public static final Identifier INTER_SEMIBOLD =
			Identifier.fromNamespaceAndPath(BzAssist.MOD_ID, "fonts/inter_semibold.ttf");

	private static TtfFont interSemibold;
	private static boolean attempted;

	private Fonts() {
	}

	/** The mod's default UI font, or {@code null} if it could not be loaded (logged once). */
	public static TtfFont defaultFont() {
		if (interSemibold == null && !attempted) {
			attempted = true;
			interSemibold = load(INTER_SEMIBOLD);
		}

		return interSemibold;
	}

	private static TtfFont load(Identifier id) {
		try {
			Resource resource = Minecraft.getInstance().getResourceManager().getResource(id).orElse(null);

			if (resource == null) {
				BzAssist.LOGGER.error("Font {} is missing", id);
				return null;
			}

			try (InputStream stream = resource.open()) {
				TtfFont font = TtfFont.load(stream);
				BzAssist.LOGGER.info("Loaded font {}", id);
				return font;
			}
		} catch (Exception exception) {
			BzAssist.LOGGER.error("Failed to load font {}", id, exception);
			return null;
		}
	}
}
