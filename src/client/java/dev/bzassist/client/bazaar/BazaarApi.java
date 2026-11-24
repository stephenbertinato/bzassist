package dev.bzassist.client.bazaar;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.bzassist.BzAssist;

/**
 * Fetches the bazaar order book from Hypixel's public API.
 *
 * <p>Everything happens on one daemon thread and is published through an immutable {@link BazaarSnapshot},
 * so the render thread never blocks and never sees a half-built snapshot.</p>
 *
 * <p>Measured against the live API: the endpoint needs no key, answers gzip-encoded (3.6 MB of JSON
 * becomes ~500 KB on the wire) and its {@code lastUpdated} advances roughly every 20 seconds. Polling
 * faster than that only spends bandwidth, so the default is 20s and a response carrying an unchanged
 * {@code lastUpdated} is not parsed again.</p>
 */
public final class BazaarApi {
	public static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
	public static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
	private static final String USER_AGENT = "bzassist/" + BzAssist.MOD_VERSION + " (Fabric client mod)";

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "bzassist-bazaar");
		thread.setDaemon(true);
		// API parsing and ranking are background work; they must never compete with the render thread.
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private static volatile BazaarSnapshot snapshot = BazaarSnapshot.empty();
	private static volatile String lastError = "";
	private static volatile int failures;
	private static volatile boolean active;
	private static boolean started;

	private BazaarApi() {
	}

	public static ScheduledExecutorService executor() {
		return EXEC;
	}

	public static BazaarSnapshot snapshot() {
		return snapshot;
	}

	public static String lastError() {
		return lastError;
	}

	public static void start() {
		if (started) {
			return;
		}

		started = true;
		EXEC.execute(BazaarApi::pollOnce);
	}

	/** Called when the Bazaar screen opens or closes, so the feed only runs while it is useful. */
	public static void setActive(boolean value) {
		boolean wasActive = active;
		active = value;

		if (value && !wasActive) {
			refreshNow();
		}
	}

	/** Fetches once, off-thread, without disturbing the poll rhythm. */
	public static void refreshNow() {
		EXEC.execute(() -> {
			try {
				poll();
				failures = 0;
				lastError = "";
			} catch (Exception exception) {
				recordFailure(exception);
			}
		});
	}

	private static void pollOnce() {
		if (!active) {
			EXEC.schedule(BazaarApi::pollOnce, 1_000L, TimeUnit.MILLISECONDS);
			return;
		}

		long delay;

		try {
			poll();
			failures = 0;
			lastError = "";
			delay = BazaarConfig.pollIntervalMs();
		} catch (Exception exception) {
			recordFailure(exception);
			delay = Math.min(60_000L, BazaarConfig.pollIntervalMs() * (1L << Math.min(4, failures)));
		}

		EXEC.schedule(BazaarApi::pollOnce, delay, TimeUnit.MILLISECONDS);
	}

	private static void recordFailure(Exception exception) {
		failures++;
		lastError = exception.getClass().getSimpleName() + ": " + exception.getMessage();

		if (failures == 1 || failures % 30 == 0) {
			BzAssist.LOGGER.warn("Bazaar fetch failed ({} in a row): {}", failures, lastError);
		}
	}

	private static void poll() throws IOException, InterruptedException {
		String text = get(BAZAAR_URL);
		long updatedAt = peekLastUpdated(text);

		if (updatedAt > 0L && updatedAt == snapshot.lastUpdated()) {
			return;
		}

		snapshot = parse(text);
	}

	/** Reads {@code lastUpdated} without building the 2200-product tree, so unchanged polls stay cheap. */
	private static long peekLastUpdated(String text) {
		int start = text.indexOf("\"lastUpdated\":");

		if (start < 0) {
			return -1L;
		}

		int index = start + 14;
		int end = index;

		while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '-')) {
			end++;
		}

		try {
			return end > index ? Long.parseLong(text.substring(index, end)) : -1L;
		} catch (NumberFormatException e) {
			return -1L;
		}
	}

	private static BazaarSnapshot parse(String text) throws IOException {
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();

		if (!root.has("products") || !root.get("products").isJsonObject()) {
			throw new IOException("Hypixel reply has no products object");
		}

		JsonObject productsJson = root.getAsJsonObject("products");
		Map<String, BazaarProduct> products = new HashMap<>(productsJson.size() * 2);

		for (Map.Entry<String, JsonElement> entry : productsJson.entrySet()) {
			if (!entry.getValue().isJsonObject()) {
				continue;
			}

			JsonObject product = entry.getValue().getAsJsonObject();
			String id = string(product, "product_id", entry.getKey());
			JsonObject quick = product.has("quick_status") && product.get("quick_status").isJsonObject()
					? product.getAsJsonObject("quick_status")
					: new JsonObject();

			products.put(id, new BazaarProduct(
					id,
					number(quick, "buyPrice"),
					number(quick, "sellPrice"),
					whole(quick, "buyVolume"),
					whole(quick, "sellVolume"),
					whole(quick, "buyMovingWeek"),
					whole(quick, "sellMovingWeek"),
					(int) whole(quick, "buyOrders"),
					(int) whole(quick, "sellOrders"),
					levels(product.get("buy_summary")),
					levels(product.get("sell_summary"))));
		}

		return new BazaarSnapshot(whole(root, "lastUpdated"), System.currentTimeMillis(), products);
	}

	private static List<OrderLevel> levels(JsonElement element) {
		if (element == null || !element.isJsonArray()) {
			return List.of();
		}

		JsonArray array = element.getAsJsonArray();
		List<OrderLevel> levels = new ArrayList<>(array.size());

		for (JsonElement levelElement : array) {
			if (!levelElement.isJsonObject()) {
				continue;
			}

			JsonObject level = levelElement.getAsJsonObject();
			levels.add(new OrderLevel((int) whole(level, "amount"), number(level, "pricePerUnit"),
					(int) whole(level, "orders")));
		}

		return levels;
	}

	private static String string(JsonObject object, String key, String fallback) {
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
	}

	private static double number(JsonObject object, String key) {
		JsonElement element = object.get(key);

		try {
			return element == null || element.isJsonNull() ? 0.0 : element.getAsDouble();
		} catch (RuntimeException e) {
			return 0.0;
		}
	}

	private static long whole(JsonObject object, String key) {
		return Math.round(number(object, key));
	}

	/** GETs a URL, undoes gzip if the server used it, and returns the body as text. */
	static String get(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", USER_AGENT)
				.header("Accept-Encoding", "gzip")
				.timeout(Duration.ofSeconds(30))
				.GET()
				.build();
		HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());

		if (response.statusCode() == 429 || response.statusCode() == 503) {
			// The poll loop backs off exponentially on any failure, up to a minute.
			throw new IOException("HTTP " + response.statusCode() + " (rate limited) from " + url);
		}

		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " from " + url);
		}

		byte[] body = response.body();
		String encoding = response.headers().firstValue("Content-Encoding").orElse("").toLowerCase(Locale.ROOT);

		if (encoding.contains("gzip")) {
			try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
				body = in.readAllBytes();
			}
		}

		return new String(body, StandardCharsets.UTF_8);
	}
}
