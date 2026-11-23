package dev.bzassist.client.bazaar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import dev.bzassist.BzAssist;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Appends what the scanner sees to {@code config/bzassist/debug.jsonl}, one JSON object per line.
 *
 * <p>Only written when a view actually changes, so leaving a menu open does not flood the file. This is
 * what makes the unverified parts verifiable: open the bazaar, then read the file back.</p>
 */
public final class DebugLog {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bzassist")
			.resolve("debug.jsonl");
	private static String lastSignature = "";

	private DebugLog() {
	}

	public static Path file() {
		return FILE;
	}

	public static void screen(BazaarView view) {
		if (!BazaarConfig.debug() || !view.isOpen()) {
			return;
		}

		String signature = signature(view);

		if (signature.equals(lastSignature)) {
			return;
		}

		lastSignature = signature;
		StringBuilder json = new StringBuilder(512);
		json.append("{\"at\":\"").append(Instant.now()).append("\"");
		json.append(",\"kind\":\"").append(view.kind()).append('"');
		json.append(",\"title\":").append(quote(view.title()));
		json.append(",\"purse\":").append(ProfileState.purse());
		json.append(",\"freeSlots\":").append(ProfileState.freeSlots());
		json.append(",\"tax\":").append(BazaarConfig.taxPercent());
		json.append(",\"orderSlots\":").append(BazaarConfig.orderSlots());
		json.append(",\"slots\":[");

		for (int i = 0; i < view.slots().size(); i++) {
			BazaarView.SlotView slot = view.slots().get(i);

			if (i > 0) {
				json.append(',');
			}

			json.append("{\"i\":").append(slot.index());
			json.append(",\"name\":").append(quote(slot.name()));
			json.append(",\"id\":").append(quote(slot.itemId()));
			json.append(",\"n\":").append(slot.count());
			json.append(",\"lore\":[");

			for (int j = 0; j < slot.lore().size(); j++) {
				if (j > 0) {
					json.append(',');
				}

				json.append(quote(slot.lore().get(j)));
			}

			json.append("]}");
		}

		json.append("]}");
		append(json.toString());
	}

	public static void note(String message) {
		if (BazaarConfig.debug()) {
			append("{\"at\":\"" + Instant.now() + "\",\"note\":" + quote(message) + "}");
		}
	}

	private static String signature(BazaarView view) {
		StringBuilder builder = new StringBuilder(view.title()).append('|').append(view.kind());

		for (BazaarView.SlotView slot : view.slots()) {
			builder.append('|').append(slot.index()).append(slot.count()).append(slot.name()).append(slot.lore());
		}

		return Integer.toHexString(builder.toString().hashCode());
	}

	private static synchronized void append(String line) {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			BzAssist.LOGGER.warn("Could not write {}", FILE, e);
		}
	}

	private static String quote(String text) {
		StringBuilder builder = new StringBuilder(text.length() + 2);
		builder.append('"');

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			switch (c) {
				case '"' -> builder.append("\\\"");
				case '\\' -> builder.append("\\\\");
				case '\n' -> builder.append("\\n");
				case '\r' -> builder.append("\\r");
				case '\t' -> builder.append("\\t");
				default -> {
					if (c < 0x20) {
						builder.append(String.format("\\u%04x", (int) c));
					} else {
						builder.append(c);
					}
				}
			}
		}

		return builder.append('"').toString();
	}
}
