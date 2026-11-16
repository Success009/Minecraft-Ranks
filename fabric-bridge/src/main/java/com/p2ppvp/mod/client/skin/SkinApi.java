package com.p2ppvp.mod.client.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Async, fail-soft HTTP client for skins. Never throws: failures come back as empty maps, null bytes or an UploadResult with ok=false. */
public class SkinApi {
    public record UploadResult(boolean ok, String token, String error) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final java.util.function.Supplier<String> baseSupplier;
    private volatile String cachedBase = null;
    private volatile long cachedAt = 0L;

    public SkinApi() { this.baseSupplier = com.p2ppvp.mod.P2PPvpMod::getMatchmakerUrl; }

    public SkinApi(String base) { this.baseSupplier = () -> base; }

    private CompletableFuture<String> base() {
        long now = System.currentTimeMillis();
        String c = cachedBase;
        if (c != null && now - cachedAt < 10_000L) return CompletableFuture.completedFuture(c);
        return CompletableFuture.supplyAsync(() -> {
            String b = baseSupplier.get();
            cachedBase = b;
            cachedAt = System.currentTimeMillis();
            return b;
        });
    }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }

    private HttpRequest.Builder req(String base, String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
    }

    /** Server token map {lowercase name: token} for the given names. Empty on any failure. */
    public CompletableFuture<Map<String, String>> tokens(Collection<String> names) {
        String joined = String.join(",", names);
        return base().thenCompose(b -> http.sendAsync(req(b, "/api/skin/tokens?names=" + enc(joined)).GET().build(),
                HttpResponse.BodyHandlers.ofString()))
            .thenApply(res -> parseTokens(res.statusCode() / 100 == 2 ? res.body() : null))
            .exceptionally(ex -> new HashMap<>());
    }

    static Map<String, String> parseTokens(String body) {
        Map<String, String> out = new HashMap<>();
        if (body == null) return out;
        try {
            JsonElement root = JsonParser.parseString(body);
            JsonObject t = root.getAsJsonObject().getAsJsonObject("tokens");
            if (t != null) for (var e : t.entrySet()) out.put(e.getKey(), e.getValue().getAsString());
        } catch (Exception ignored) {}
        return out;
    }

    /** PNG bytes of kind "head" or "full", or null if missing or on failure. */
    public CompletableFuture<byte[]> download(String kind, String name) {
        return base().thenCompose(b -> http.sendAsync(req(b, "/api/skin/" + kind + "?name=" + enc(name)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()))
            .thenApply(res -> res.statusCode() == 200 ? res.body() : (byte[]) null)
            .exceptionally(ex -> null);
    }

    /** Upload raw PNG bytes for a player name. */
    public CompletableFuture<UploadResult> upload(String name, byte[] png) {
        return base().thenCompose(b -> http.sendAsync(req(b, "/api/skin/upload?name=" + enc(name))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(png)).build(), HttpResponse.BodyHandlers.ofString()))
            .thenApply(res -> parseUpload(res.statusCode(), res.body()))
            .exceptionally(ex -> new UploadResult(false, null, "Server unreachable"));
    }

    static UploadResult parseUpload(int status, String body) {
        String token = null, error = null;
        try {
            JsonObject o = JsonParser.parseString(body == null ? "{}" : body).getAsJsonObject();
            if (o.has("token")) token = o.get("token").getAsString();
            if (o.has("error")) error = o.get("error").getAsString();
        } catch (Exception ignored) {}
        if (status / 100 == 2 && token != null) return new UploadResult(true, token, null);
        return new UploadResult(false, null, error != null ? error : "Upload failed (" + status + ")");
    }
}
