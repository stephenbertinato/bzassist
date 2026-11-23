package dev.bzassist.client.bazaar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import dev.bzassist.BzAssist;
import net.fabricmc.loader.api.FabricLoader;

/**
 * A full, timestamped record of one flipping session, written to {@code config/bzassist/sessions/}.
 *
 * <p>Every session opens a file, streams into it while it runs (a crash still leaves the interesting
 * part on disk) and closes it with a summary. Recorded: session start and end, configuration and
 * profile at start, every phase change, every instruction the guide or the session issued, every
 * container the screen reader recognised, every chat line the client received, the bazaar feed's
 * state, a periodic state sample, and every exception the mod catches.</p>
 *
 * <p>Nothing here may ever throw into the game: all of it is wrapped, and writes happen on the bazaar
 * executor rather than the render thread. The file is the thing to read after a session that behaved
 * strangely — it replays exactly what the mod thought was happening.</p>
 */
public final class SessionLog {
	private static final DateTimeFormatter FILE_STAMP =
			DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter CLOCK =
			DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter HUMAN =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
	/** Long sessions are capped so a forgotten session cannot grow without bound. */
	private static final int MAX_LINES = 100_000;
	private static final long FLUSH_MS = 2_000L;

	private static final StringBuilder BODY = new StringBuilder(64 * 1024);
	private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();
	private static boolean active;
	private static boolean truncated;
	private static long startedAt;
	private static long endedAt;
	private static long lastFlush;
	private static int lines;
	private static Path file;
	private static String lastStep = "";

	private SessionLog() {
	}

	public static synchronized boolean active() {
		return active;
	}

	public static synchronized Path file() {
		return file;
	}

	/** Opens a new report. Called when a session starts. */
	public static synchronized void begin() {
		if (active) {
			finish();
		}

		active = true;
		truncated = false;
		lines = 0;
		lastStep = "";
		startedAt = System.currentTimeMillis();
		lastFlush = startedAt;
		BODY.setLength(0);
		COUNTS.clear();
		file = FabricLoader.getInstance().getConfigDir()
				.resolve("bzassist/sessions/" + FILE_STAMP.format(Instant.ofEpochMilli(startedAt)) + ".log");

		event("session", "started");
		event("config", String.format(Locale.ROOT,
				"poll=%dms tax=%.3f%% slots=%d maxFill=%.1fh overlay=%s debug=%s",
				BazaarConfig.pollIntervalMs(), BazaarConfig.taxPercent(), BazaarConfig.orderSlots(),
				BazaarConfig.maxFillHours(), BazaarConfig.overlay(), BazaarConfig.debug()));
		event("profile", String.format(Locale.ROOT, "purse=%s freeSlots=%d limitLeft=%s",
				ProfileState.purseKnown() ? Long.toString(ProfileState.purse()) : "unknown",
				ProfileState.freeSlots(), Coins.compact(ProfileState.dailyLimitRemaining())));
		write(true);
	}

	/** Closes the report with a summary. Called when the session stops. */
	public static synchronized void finish() {
		if (!active) {
			return;
		}

		endedAt = System.currentTimeMillis();
		event("session", "stopped");
		active = false;
		write(true);
		BzAssist.LOGGER.info("Session report written to {}", file);
	}

	/** One line in the report. {@code kind} is a short tag: phase, step, screen, chat, api, error, state. */
	public static synchronized void event(String kind, String message) {
		if (!active) {
			return;
		}

		if (lines >= MAX_LINES) {
			if (!truncated) {
				truncated = true;
				BODY.append("... report truncated at ").append(MAX_LINES).append(" lines ...\n");
			}

			return;
		}

		BODY.append(CLOCK.format(Instant.now()))
				.append(" [").append(kind).append("] ")
				.append(oneLine(message))
				.append('\n');
		lines++;
		COUNTS.merge(kind, 1, Integer::sum);

		long now = System.currentTimeMillis();

		if (now - lastFlush >= FLUSH_MS) {
			lastFlush = now;
			writeAsync();
		}
	}

	/** Every instruction shown to the player, deduplicated: a step that repeats every tick is noise. */
	public static synchronized void step(GuideEngine.Step step) {
		if (!active || step == null) {
			return;
		}

		String key = step.action() + '\u0000' + step.detail() + '\u0000' + step.slotIndex()
				+ '\u0000' + step.rightClick() + '\u0000' + step.command();

		if (key.equals(lastStep)) {
			return;
		}

		lastStep = key;
		event("step", String.format(Locale.ROOT, "'%s' | '%s' | slot=%d right=%s cmd='%s'",
				step.action(), step.detail(), step.slotIndex(), step.rightClick(), step.command()));
	}

	/** A container the scanner recognised, or the fact that none is open any more. */
	public static synchronized void screen(BazaarView view) {
		if (!active) {
			return;
		}

		if (view.kind() == BazaarView.Kind.NONE) {
			event("screen", "none open");
			return;
		}

		StringBuilder extra = new StringBuilder();

		for (BazaarView.SlotView slot : view.slots()) {
			if (!slot.name().isBlank()) {
				extra.append(extra.isEmpty() ? "" : ", ").append(slot.name());
			}

			if (extra.length() > 240) {
				extra.append(", ...");
				break;
			}
		}

		event("screen", String.format(Locale.ROOT, "%s '%s' slots=%d names=[%s]",
				view.kind(), view.title(), view.slots().size(), extra));
	}

	public static void chat(String text) {
		event("chat", text);
	}

	/** Periodic sample so the report shows what the mod believed even between events. */
	public static void state(String phase, String note) {
		if (!active) {
			return;
		}

		event("state", String.format(Locale.ROOT,
				"phase=%s note='%s' purse=%s free=%d limitLeft=%s snapshotAge=%ds",
				phase, note,
				ProfileState.purseKnown() ? Long.toString(ProfileState.purse()) : "unknown",
				ProfileState.freeSlots(), Coins.compact(ProfileState.dailyLimitRemaining()),
				BazaarApi.snapshot().ageMillis() / 1000L));
	}

	/** An exception the mod caught. The game kept running; this is why it had to. */
	public static synchronized void failure(String where, Throwable error) {
		if (error == null) {
			return;
		}

		StringBuilder text = new StringBuilder(where).append(": ").append(error);
		StackTraceElement[] trace = error.getStackTrace();

		for (int i = 0; i < Math.min(12, trace.length); i++) {
			text.append(" <- ").append(trace[i]);
		}

		event("error", text.toString());
	}

	private static void writeAsync() {
		try {
			BazaarApi.executor().execute(() -> {
				try {
					write(false);
				} catch (RuntimeException exception) {
					BzAssist.LOGGER.warn("Could not write the session report: {}", exception.toString());
				}
			});
		} catch (RuntimeException exception) {
			BzAssist.LOGGER.warn("Could not queue the session report write: {}", exception.toString());
		}
	}

	private static synchronized void write(boolean complete) {
		if (file == null) {
			return;
		}

		StringBuilder out = new StringBuilder(BODY.length() + 512);
		out.append("BzAssist session report\n");
		out.append("version   ").append(BzAssist.MOD_VERSION).append('\n');
		out.append("started   ").append(HUMAN.format(Instant.ofEpochMilli(startedAt))).append('\n');

		if (complete && endedAt > 0L) {
			out.append("ended     ").append(HUMAN.format(Instant.ofEpochMilli(endedAt))).append('\n');
			out.append("duration  ").append(duration(endedAt - startedAt)).append('\n');
		}

		out.append("counts    ").append(COUNTS.isEmpty() ? "-" : COUNTS.toString()).append('\n');
		out.append("lines     ").append(lines).append(truncated ? " (truncated)" : "").append('\n');
		out.append("------------------------------------------------------------------------\n");
		out.append(BODY);

		if (complete) {
			out.append("------------------------------------------------------------------------\n");
			out.append("SUMMARY\n");

			for (Map.Entry<String, Integer> entry : COUNTS.entrySet()) {
				out.append(String.format(Locale.ROOT, "  %-8s %d%n", entry.getKey(), entry.getValue()));
			}
		}

		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			BzAssist.LOGGER.warn("Could not write {}: {}", file, exception.toString());
		}
	}

	private static String duration(long millis) {
		long seconds = Math.max(0L, millis / 1000L);
		return String.format(Locale.ROOT, "%dm %02ds", seconds / 60L, seconds % 60L);
	}

	private static String oneLine(String text) {
		return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ');
	}
}
