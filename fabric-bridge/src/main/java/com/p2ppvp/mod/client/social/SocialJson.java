package com.p2ppvp.mod.client.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2ppvp.mod.client.social.SocialModels.*;
import java.util.ArrayList;
import java.util.List;

/** Tolerant JSON parsing for social responses. Missing fields get safe defaults; bad JSON gives empty results. */
public final class SocialJson {
    private SocialJson() {}

    private static JsonObject obj(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception ex) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject o, String k, String d) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : d;
    }

    private static double num(JsonObject o, String k, double d) {
        try { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : d; } catch (Exception e) { return d; }
    }

    private static boolean bool(JsonObject o, String k, boolean d) {
        try { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : d; } catch (Exception e) { return d; }
    }

    static KitRef kit(JsonElement e) {
        if (e == null || e.isJsonNull()) return new KitRef("official", "Crystal");
        if (e.isJsonPrimitive()) return new KitRef("official", e.getAsString());
        if (!e.isJsonObject()) return new KitRef("official", "Crystal");
        JsonObject o = e.getAsJsonObject();
        return new KitRef(str(o, "type", "official"), str(o, "name", "Crystal"));
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    public static List<OnlinePlayer> players(String json) {
        List<OnlinePlayer> out = new ArrayList<>();
        for (JsonElement e : arr(obj(json), "players")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String id = str(o, "player_id", "");
            if (id.isEmpty()) continue;
            out.add(new OnlinePlayer(id, str(o, "name", id), str(o, "status", "idle"),
                (int) num(o, "points", 100), bool(o, "challengeable", false), num(o, "cooldown", 0)));
        }
        return out;
    }

    public static List<LiveMatch> liveMatches(String json) {
        List<LiveMatch> out = new ArrayList<>();
        for (JsonElement e : arr(obj(json), "matches")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String id = str(o, "match_id", "");
            if (id.isEmpty()) continue;
            out.add(new LiveMatch(id, str(o, "host", "?"), str(o, "guest", "?"), kit(o.get("kit")),
                (int) num(o, "spectator_count", 0), (int) num(o, "max_spectators", 5), bool(o, "full", false)));
        }
        return out;
    }

    public static PollResult poll(String json) {
        JsonObject root = obj(json);
        List<ChallengeInfo> in = new ArrayList<>();
        for (JsonElement e : arr(root, "incoming")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String id = str(o, "challenge_id", "");
            if (id.isEmpty()) continue;
            in.add(new ChallengeInfo(id, str(o, "sender", "?"), kit(o.get("kit")), num(o, "seconds_left", 0)));
        }
        List<Outcome> outs = new ArrayList<>();
        for (JsonElement e : arr(root, "outcomes")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            outs.add(new Outcome(str(o, "challenge_id", ""), str(o, "result", ""), str(o, "target", "?"), kit(o.get("kit"))));
        }
        return new PollResult(in, outs);
    }

    /** Spectator names from /api/spectate/roster (prefers display name). */
    public static List<String> rosterNames(String json) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : arr(obj(json), "spectators")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String n = str(o, "name", str(o, "player_id", ""));
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    public static String errorCode(String json) { return str(obj(json), "code", "error"); }
    public static String errorMessage(String json) { return str(obj(json), "error", "request failed"); }
    public static double retryAfter(String json) { return num(obj(json), "retry_after", 0); }
    public static String field(String json, String key) { return str(obj(json), key, ""); }
}
