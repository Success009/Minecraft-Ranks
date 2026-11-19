package com.p2ppvp.mod.client.account;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The player's saved sign-in on this computer: username + session token, kept in one small file.
 * No Minecraft imports, so it is unit-testable. Never throws; a bad or missing file means "signed out".
 */
public final class AccountSession {
    private static final AccountSession INSTANCE = new AccountSession(Path.of("config", "mcr_account.json"));

    private final Path file;
    private volatile String username;
    private volatile String token;

    public AccountSession(Path file) {
        this.file = file;
        load();
    }

    public static AccountSession get() { return INSTANCE; }

    public boolean signedIn() { return token != null && !token.isEmpty(); }
    public String username() { return username; }
    public String token() { return token; }

    public synchronized void set(String username, String token) {
        this.username = username;
        this.token = token;
        save();
    }

    public synchronized void clear() {
        this.username = null;
        this.token = null;
        try { Files.deleteIfExists(file); } catch (Exception ignored) {}
    }

    /** Adds "Authorization: Bearer <token>" when signed in. Always returns the same builder. */
    public HttpRequest.Builder auth(HttpRequest.Builder b) {
        String t = token;
        if (t != null && !t.isEmpty()) b.header("Authorization", "Bearer " + t);
        return b;
    }

    /** Alternating name/value array for HttpRequest.Builder.headers(...). Empty when signed out, which headers() rejects, so see below. */
    public String[] authHeaders() {
        String t = token;
        if (t != null && !t.isEmpty()) return new String[] {"Authorization", "Bearer " + t};
        return new String[] {"X-MCR-Client", "1"};
    }

    public static HttpRequest.Builder withAuth(HttpRequest.Builder b) { return INSTANCE.auth(b); }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            String u = o.has("username") ? o.get("username").getAsString() : null;
            String t = o.has("token") ? o.get("token").getAsString() : null;
            if (u != null && t != null && !t.isEmpty()) { username = u; token = t; }
        } catch (Exception ignored) {
            username = null;
            token = null;
        }
    }

    private void save() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("username", username);
            o.addProperty("token", token);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {}
    }
}
