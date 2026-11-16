package com.p2ppvp.mod.client.ui;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a default-slot -> current-slot mapping by matching what the player holds against a kit's default items.
 * Armor/offhand (slots 36-40) are never moved. Duplicates are matched in default-slot order. No Minecraft imports.
 */
public final class KitLayoutMapper {
    private KitLayoutMapper() {}

    public record Item(int slot, String key) {}

    public static final int MOVABLE_LIMIT = 36;

    /**
     * @param defaults kit items with their default slots (key = item id plus variant such as potion)
     * @param current  what the player holds now, with current slots (same key format)
     * @return mapping default slot -> current slot (only for movable items that moved), or null if invalid
     */
    /** Keys of movable kit items the player no longer holds (extra items the player holds are ignored). */
    public static List<String> missing(List<Item> defaults, List<Item> current) {
        List<Item> unused = new ArrayList<>();
        for (Item d : defaults) if (d.slot() >= 0 && d.slot() < MOVABLE_LIMIT) unused.add(d);
        for (Item c : current) {
            if (c.slot() < 0 || c.slot() >= MOVABLE_LIMIT) continue;
            Item match = null;
            for (Item d : unused) if (d.key().equals(c.key())) { match = d; break; }
            if (match != null) unused.remove(match);
        }
        List<String> out = new ArrayList<>();
        for (Item d : unused) out.add(d.key());
        return out;
    }

    public static JsonObject build(List<Item> defaults, List<Item> current) {
        List<Item> unused = new ArrayList<>();
        for (Item d : defaults) if (d.slot() >= 0 && d.slot() < MOVABLE_LIMIT) unused.add(d);
        unused.sort((a, b) -> Integer.compare(a.slot(), b.slot()));

        List<Item> cur = new ArrayList<>();
        for (Item c : current) if (c.slot() >= 0 && c.slot() < MOVABLE_LIMIT) cur.add(c);
        cur.sort((a, b) -> Integer.compare(a.slot(), b.slot()));

        JsonObject out = new JsonObject();
        // Items that stayed on their default slot claim it first, so they are never reshuffled.
        List<Item> leftoverCur = new ArrayList<>();
        for (Item c : cur) {
            Item same = null;
            for (Item d : unused) if (d.slot() == c.slot() && d.key().equals(c.key())) { same = d; break; }
            if (same != null) unused.remove(same); else leftoverCur.add(c);
        }
        for (Item c : leftoverCur) {
            Item match = null;
            for (Item d : unused) if (d.key().equals(c.key())) { match = d; break; }
            if (match == null) continue;
            unused.remove(match);
            out.addProperty(String.valueOf(match.slot()), c.slot());
        }
        return KitLayout.isValid(out) ? out : null;
    }
}
