package dev.bzassist.client.bazaar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

/**
 * Watches incoming system chat, because some answers never appear on a screen.
 *
 * <p>Hypixel replies to {@code /viewstash item} with a container when there is something to collect and
 * with a chat line when there is not — {@code "Your item stash is empty!"}. A guide that only reads
 * containers waits forever in that case, which is exactly what happened before this existed. Every game
 * message is kept for a short window so a step can ask "did the game just tell me X?" and move on.</p>
 */
public final class ChatWatch {
	/** Long enough for a player to type a command and read the answer, short enough not to be stale. */
	private static final long KEEP_MS = 20_000L;
	private static final int MAX_NOTES = 200;
	private static final List<Note> NOTES = new ArrayList<>();

	private record Note(String text, long at) {
	}

	private ChatWatch() {
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> note(text(message)));
	}

	private static String text(Component message) {
		try {
			return message == null ? "" : message.getString();
		} catch (RuntimeException exception) {
			return "";
		}
	}

	public static void note(String line) {
		if (line == null || line.isBlank()) {
			return;
		}

		synchronized (NOTES) {
			NOTES.add(new Note(line, System.currentTimeMillis()));

			while (NOTES.size() > MAX_NOTES) {
				NOTES.remove(0);
			}
		}

		SessionLog.chat(line);
		ProfileState.noteChat(line);
		BazaarChat.note(line);
	}

	/** Forgets everything, so an answer to the previous question cannot satisfy the next one. */
	public static void clear() {
		synchronized (NOTES) {
			NOTES.clear();
		}
	}

	/** True when a recent message contains every fragment, case-insensitive. */
	public static boolean seen(long withinMs, String... fragments) {
		long since = System.currentTimeMillis() - Math.max(0L, withinMs);
		List<Note> copy;

		synchronized (NOTES) {
			copy = new ArrayList<>(NOTES);
		}

		for (int i = copy.size() - 1; i >= 0; i--) {
			Note note = copy.get(i);

			if (note.at() < since) {
				break;
			}

			String lower = note.text().toLowerCase(Locale.ROOT);
			boolean all = true;

			for (String fragment : fragments) {
				if (!lower.contains(fragment.toLowerCase(Locale.ROOT))) {
					all = false;
					break;
				}
			}

			if (all) {
				return true;
			}
		}

		return false;
	}
}
