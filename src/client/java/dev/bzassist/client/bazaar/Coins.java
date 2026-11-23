package dev.bzassist.client.bazaar;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coin amounts, as Hypixel writes them ("1,234.5 coins", "12.5m") and as we print them back.
 */
public final class Coins {
	private static final Pattern NUMBER = Pattern.compile("(-?[\\d,]*\\.?\\d+)\\s*([kKmMbBtT])?");

	private Coins() {
	}

	/** {@code "1,234.5 coins"} to {@code 1234.5}, {@code "12.5m"} to {@code 1.25E7}. 0 when there is no number. */
	public static double parse(String text) {
		if (text == null) {
			return 0.0;
		}

		Matcher matcher = NUMBER.matcher(text);

		if (!matcher.find()) {
			return 0.0;
		}

		String digits = matcher.group(1).replace(",", "");
		double value;

		try {
			value = Double.parseDouble(digits);
		} catch (NumberFormatException e) {
			return 0.0;
		}

		String suffix = matcher.group(2);

		if (suffix != null) {
			value *= switch (suffix.toLowerCase(Locale.ROOT)) {
				case "k" -> 1.0E3;
				case "m" -> 1.0E6;
				case "b" -> 1.0E9;
				case "t" -> 1.0E12;
				default -> 1.0;
			};
		}

		return value;
	}

	/** {@code 1234.5} to {@code "1,234.5"}. */
	public static String format(double value) {
		return String.format(Locale.ROOT, "%,.1f", value);
	}

	/** {@code 12345678.0} to {@code "12.35m"}, for columns that must stay short. */
	public static String compact(double value) {
		double abs = Math.abs(value);

		if (abs >= 1.0E9) {
			return String.format(Locale.ROOT, "%.2fb", value / 1.0E9);
		}

		if (abs >= 1.0E6) {
			return String.format(Locale.ROOT, "%.2fm", value / 1.0E6);
		}

		if (abs >= 1.0E3) {
			return String.format(Locale.ROOT, "%.1fk", value / 1.0E3);
		}

		return String.format(Locale.ROOT, "%.0f", value);
	}
}
