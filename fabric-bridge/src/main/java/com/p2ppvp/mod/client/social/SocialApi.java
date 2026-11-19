package com.p2ppvp.mod.client.social;

import com.google.gson.JsonObject;
import com.p2ppvp.mod.client.social.SocialModels.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Async, fail-soft HTTP client for presence, challenges and spectating. Never throws; errors come back as ApiResult.failure. */
public class SocialApi {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final java.util.function.Supplier<String> baseSupplier;
    private volatile String cachedBase = null;
    private volatile long cachedAt = 0L;

    public SocialApi() { this.baseSupplier = com.p2ppvp.mod.P2PPvpMod::getMatchmakerUrl; }

    public SocialApi(String base) { this.baseSupplier = () -> base; }

    /** Resolve the matchmaker base URL off the caller's thread; cached 10 s because the resolver probes the network. */
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

    private <T> CompletableFuture<ApiResult<T>> send(java.util.function.Function<String, HttpRequest> reqFor, Function<String, T> parse) {
        return base().thenCompose(b -> http.sendAsync(reqFor.apply(b), HttpResponse.BodyHandlers.ofString()))
            .thenApply(res -> {
                String body = res.body() == null ? "" : res.body();
                if (res.statusCode() / 100 == 2) return ApiResult.success(parse.apply(body));
                return ApiResult.<T>failure(SocialJson.errorCode(body), SocialJson.errorMessage(body), SocialJson.retryAfter(body));
            })
            .exceptionally(ex -> ApiResult.<T>failure("network", "matchmaker unreachable", 0));
    }

    private HttpRequest.Builder req(String base, String path) {
        return com.p2ppvp.mod.client.account.AccountSession.withAuth(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(4)));
    }

    private <T> CompletableFuture<ApiResult<T>> get(String path, Function<String, T> parse) {
        return send(b -> req(b, path).GET().build(), parse);
    }

    private <T> CompletableFuture<ApiResult<T>> post(String path, JsonObject body, Function<String, T> parse) {
        return send(b -> req(b, path).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), parse);
    }

    public CompletableFuture<ApiResult<String>> heartbeat(String playerId, String displayName, String tailscaleIp, long perfScore) {
        JsonObject info = new JsonObject();
        info.addProperty("name", displayName);
        if (tailscaleIp != null) info.addProperty("tailscale_ip", tailscaleIp);
        info.addProperty("perf_score", perfScore);
        JsonObject b = new JsonObject();
        b.addProperty("player_id", playerId);
        b.add("info", info);
        return post("/api/presence/heartbeat", b, s -> SocialJson.field(s, "status"));
    }

    public CompletableFuture<ApiResult<List<OnlinePlayer>>> onlinePlayers(String me) {
        return get("/api/players/online?player_id=" + enc(me), SocialJson::players);
    }

    public CompletableFuture<ApiResult<List<LiveMatch>>> liveMatches() {
        return get("/api/matches/live", SocialJson::liveMatches);
    }

    public CompletableFuture<ApiResult<PollResult>> pollChallenges(String me) {
        return get("/api/challenge/poll?player_id=" + enc(me), SocialJson::poll);
    }

    /** The player's saved kit layouts, or null. Sent so the host can give the guest their layout. */
    private static JsonObject localLayouts() {
        try {
            java.io.File f = new java.io.File("nml_inventory_layout.json");
            if (!f.exists()) return null;
            String t = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            return com.p2ppvp.mod.client.ui.KitLayout.parse(t);
        } catch (Exception e) {
            return null;
        }
    }

    public CompletableFuture<ApiResult<String>> sendChallenge(String sender, String target, KitRef kit) {
        JsonObject k = new JsonObject();
        k.addProperty("type", kit.type());
        k.addProperty("name", kit.name());
        JsonObject b = new JsonObject();
        b.addProperty("sender", sender);
        b.addProperty("target", target);
        b.add("kit", k);
        JsonObject lay = localLayouts();
        if (lay != null) b.add("layouts", lay);
        return post("/api/challenge/send", b, s -> SocialJson.field(s, "challenge_id"));
    }

    /** On accept the value is the raw "matched" payload (match_id, role, host_ip, opponent_id, kit). */
    public CompletableFuture<ApiResult<String>> respond(String challengeId, String me, boolean accept) {
        JsonObject b = new JsonObject();
        b.addProperty("challenge_id", challengeId);
        b.addProperty("player_id", me);
        b.addProperty("accept", accept);
        JsonObject lay = accept ? localLayouts() : null;
        if (lay != null) b.add("layouts", lay);
        return post("/api/challenge/respond", b, s -> s);
    }

    public CompletableFuture<ApiResult<String>> cancelChallenge(String challengeId, String me) {
        JsonObject b = new JsonObject();
        b.addProperty("challenge_id", challengeId);
        b.addProperty("player_id", me);
        return post("/api/challenge/cancel", b, s -> SocialJson.field(s, "status"));
    }

    /** Value is the raw join payload (role, host_ip, kit, ...). */
    public CompletableFuture<ApiResult<String>> spectateJoin(String me, String matchId) {
        JsonObject b = new JsonObject();
        b.addProperty("player_id", me);
        b.addProperty("match_id", matchId);
        return post("/api/spectate/join", b, s -> s);
    }

    public CompletableFuture<ApiResult<String>> spectateLeave(String me) {
        JsonObject b = new JsonObject();
        b.addProperty("player_id", me);
        return post("/api/spectate/leave", b, s -> s);
    }

    public CompletableFuture<ApiResult<List<String>>> roster(String participantId) {
        return get("/api/spectate/roster?player_id=" + enc(participantId), SocialJson::rosterNames);
    }
}
