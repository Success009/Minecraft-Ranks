package com.p2ppvp.mod.client.account;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Async, fail-soft client for the account routes. Never throws; problems come back as Result.ok == false with a friendly message. */
public class AccountApi {
    /** username/token/recovery are filled on success. code is the server's short error code ("" if none). */
    public record Result(boolean ok, String message, String code, String username, String token, String recoveryCode) {
        static Result fail(String code, String message) { return new Result(false, message, code, null, null, null); }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final java.util.function.Supplier<String> baseSupplier;

    public AccountApi() { this.baseSupplier = com.p2ppvp.mod.P2PPvpMod::getMatchmakerUrl; }
    public AccountApi(String base) { this.baseSupplier = () -> base; }

    /** Short, friendly text for a server error code. */
    public static String friendly(String code) {
        if (code == null) return "Something went wrong";
        return switch (code) {
            case "bad_username" -> "Name must be 3-16 letters, numbers or _";
            case "bad_password" -> "Password must be 6 or more characters";
            case "name_taken" -> "That name is taken";
            case "wrong_credentials" -> "Wrong name or password";
            case "too_many_attempts" -> "Too many tries, wait a few minutes";
            case "banned" -> "This account is banned";
            case "bad_recovery" -> "Wrong recovery code";
            case "session_expired" -> "Please sign in again";
            case "wrong_account" -> "Signed in as a different account";
            case "network" -> "Matchmaker is offline";
            default -> "Something went wrong";
        };
    }

    private CompletableFuture<Result> post(String path, JsonObject body, boolean withAuth) {
        final String tok = withAuth ? AccountSession.get().token() : null;   // captured now: the session may be cleared before the request is built
        return CompletableFuture.supplyAsync(baseSupplier::get).thenCompose(base -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            if (tok != null && !tok.isEmpty()) b.header("Authorization", "Bearer " + tok);
            return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString());
        }).thenApply(res -> parse(res.statusCode(), res.body()))
          .exceptionally(ex -> Result.fail("network", friendly("network")));
    }

    static Result parse(int status, String body) {
        String code = "", username = null, token = null, recovery = null;
        try {
            JsonObject o = JsonParser.parseString(body == null || body.isEmpty() ? "{}" : body).getAsJsonObject();
            if (o.has("code")) code = o.get("code").getAsString();
            if (o.has("token")) token = o.get("token").getAsString();
            if (o.has("recovery_code")) recovery = o.get("recovery_code").getAsString();
            if (o.has("account") && o.get("account").isJsonObject() && o.getAsJsonObject("account").has("username"))
                username = o.getAsJsonObject("account").get("username").getAsString();
        } catch (Exception ignored) {}
        if (status / 100 == 2) return new Result(true, "", "", username, token, recovery);
        return Result.fail(code, friendly(code));
    }

    public CompletableFuture<Result> register(String username, String password) {
        JsonObject b = new JsonObject();
        b.addProperty("username", username);
        b.addProperty("password", password);
        return post("/api/account/register", b, false);
    }

    public CompletableFuture<Result> login(String username, String password) {
        JsonObject b = new JsonObject();
        b.addProperty("username", username);
        b.addProperty("password", password);
        return post("/api/account/login", b, false);
    }

    /** Does this name already have an account? Completes with true/false, or null if the server can't be reached. */
    public CompletableFuture<Boolean> exists(String name) {
        return CompletableFuture.supplyAsync(baseSupplier::get).thenCompose(base -> {
            String q = java.net.URLEncoder.encode(name == null ? "" : name, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/account/exists?name=" + q))
                .timeout(Duration.ofSeconds(5)).GET().build();
            return http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        }).thenApply(res -> {
            if (res.statusCode() / 100 != 2) return (Boolean) null;
            try {
                JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
                return o.has("exists") ? Boolean.valueOf(o.get("exists").getAsBoolean()) : (Boolean) null;
            } catch (Exception e) { return (Boolean) null; }
        }).exceptionally(ex -> (Boolean) null);
    }

    public CompletableFuture<Result> logout() {
        return post("/api/account/logout", new JsonObject(), true);
    }

    /** Random id saved once in the config folder; lets the server limit reset tries per computer. */
    private static String deviceId() {
        try {
            java.nio.file.Path p = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mcr_device_id.txt");
            if (java.nio.file.Files.exists(p)) { String s = java.nio.file.Files.readString(p).trim(); if (s.length() >= 16) return s; }
            String id = java.util.UUID.randomUUID().toString();
            java.nio.file.Files.writeString(p, id);
            return id;
        } catch (Exception e) { return ""; }
    }

    /** Checks only the recovery code (no password change). */
    public CompletableFuture<Result> recoverCheck(String username, String code) {
        JsonObject b = new JsonObject();
        b.addProperty("username", username);
        b.addProperty("recovery_code", code);
        b.addProperty("device_id", deviceId());
        return post("/api/account/recover_check", b, false);
    }

    public CompletableFuture<Result> recover(String username, String code, String newPassword) {
        JsonObject b = new JsonObject();
        b.addProperty("username", username);
        b.addProperty("recovery_code", code);
        b.addProperty("new_password", newPassword);
        b.addProperty("device_id", deviceId());
        return post("/api/account/recover", b, false);
    }
}
