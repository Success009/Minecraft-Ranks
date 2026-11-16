package com.p2ppvp.mod.client.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure logic for per-kit inventory layouts. A layout maps a kit's default slot to the slot the player prefers.
 * File format (nml_inventory_layout.json compatible): {"crystal": {"0": 3, "1": 0}, "pot": {...}}.
 * No Minecraft imports.
 */
public final class KitLayout {
    private KitLayout() {}

    public static final int INVENTORY_SIZE = 41; // 36 main + 4 armor + offhand

    /** Slot to place an item in: the saved slot for this kit, or the default when none/invalid. */
    public static int slotFor(JsonObject root, String kit, int defaultSlot) {
        if (root == null || kit == null) return defaultSlot;
        JsonElement k = root.get(kit.toLowerCase(java.util.Locale.ROOT));
        if (k == null || !k.isJsonObject()) return defaultSlot;
        JsonElement v = k.getAsJsonObject().get(String.valueOf(defaultSlot));
        if (v == null || !v.isJsonPrimitive()) return defaultSlot;
        try {
            int s = v.getAsInt();
            return s >= 0 && s < INVENTORY_SIZE ? s : defaultSlot;
        } catch (Exception e) {
            return defaultSlot;
        }
    }

    /** A layout is valid when no two items map to the same slot. */
    public static boolean isValid(JsonObject mapping) {
        if (mapping == null) return false;
        Set<Integer> used = new HashSet<>();
        for (var e : mapping.entrySet()) {
            try {
                int s = e.getValue().getAsInt();
                if (s < 0 || s >= INVENTORY_SIZE || !used.add(s)) return false;
            } catch (Exception ex) {
                return false;
            }
        }
        return true;
    }

    /** Return root with the kit's mapping replaced (or removed when mapping is null). */
    public static JsonObject withKit(JsonObject root, String kit, JsonObject mapping) {
        JsonObject out = root == null ? new JsonObject() : root.deepCopy();
        String key = kit.toLowerCase(java.util.Locale.ROOT);
        if (mapping == null) out.remove(key); else out.add(key, mapping);
        return out;
    }

    public static JsonObject parse(String json) {
        try {
            JsonElement e = JsonParser.parseString(json == null ? "" : json);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }
}
