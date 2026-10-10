package dev.fakecrowd;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/**
 * Клієнт до хмарної нейромережі через OpenAI-сумісний API (/chat/completions).
 * Повністю асинхронний - головний потік сервера ніколи не чекає. Є ліміт запитів на хвилину.
 * Якщо щось не так - повертає null, і плагін використовує вбудований запасний "ШІ".
 */
final class AiClient {
    private final FakeCrowd plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Semaphore slots = new Semaphore(2);
    private final Deque<Long> stamps = new ArrayDeque<>();
    private volatile long failUntil = 0;
    private volatile long lastWarn = 0;

    AiClient(FakeCrowd plugin) { this.plugin = plugin; }

    boolean enabled() {
        FileConfiguration c = plugin.getConfig();
        return c.getBoolean("ai.enabled", false)
                && !c.getString("ai.base-url", "").isBlank()
                && System.currentTimeMillis() >= failUntil;
    }

    private boolean rateOk(FileConfiguration c) {
        long now = System.currentTimeMillis();
        int max = c.getInt("ai.max-requests-per-minute", 20);
        synchronized (stamps) {
            while (!stamps.isEmpty() && now - stamps.peekFirst() > 60_000L) stamps.pollFirst();
            if (stamps.size() >= max) return false;
            stamps.addLast(now);
            return true;
        }
    }

    CompletableFuture<String> ask(String system, String user) {
        FileConfiguration c = plugin.getConfig();
        if (!enabled() || !rateOk(c) || !slots.tryAcquire()) return CompletableFuture.completedFuture(null);
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", c.getString("ai.model", ""));
            body.addProperty("max_tokens", c.getInt("ai.max-tokens", 40));
            body.addProperty("temperature", c.getDouble("ai.temperature", 0.9));
            JsonArray msgs = new JsonArray();
            msgs.add(msg("system", system));
            msgs.add(msg("user", user));
            body.add("messages", msgs);

            String base = c.getString("ai.base-url", "").replaceAll("/+$", "");
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                    .timeout(Duration.ofSeconds(c.getInt("ai.timeout-seconds", 12)))
                    .header("Content-Type", "application/json");
            String key = c.getString("ai.api-key", "");
            if (!key.isBlank()) rb.header("Authorization", "Bearer " + key.trim());
            HttpRequest req = rb.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();

            return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).handle((resp, err) -> {
                slots.release();
                if (err != null || resp == null || resp.statusCode() != 200) {
                    failUntil = System.currentTimeMillis() + 30_000L;
                    warn(err != null ? err.toString() : "HTTP " + resp.statusCode() + " " + shorten(resp.body()));
                    return null;
                }
                try {
                    JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                    return o.getAsJsonArray("choices").get(0).getAsJsonObject()
                            .getAsJsonObject("message").get("content").getAsString();
                } catch (Exception e) {
                    warn("Bad AI response: " + e);
                    return null;
                }
            });
        } catch (Exception e) {
            slots.release();
            warn(e.toString());
            return CompletableFuture.completedFuture(null);
        }
    }

    private void warn(String s) {
        long now = System.currentTimeMillis();
        if (now - lastWarn < 300_000L) return;
        lastWarn = now;
        plugin.getLogger().warning("AI request failed (using built-in fallback): " + s);
    }

    private static String shorten(String s) { return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) : s); }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }
}
