package com.p2ppvp.mod.client.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides whether an inventory is still a legal state while a player arranges a kit.
 * A snapshot is one string per slot (null/empty = empty slot), slots 0-40. No Minecraft imports.
 * Legal: the items in slots 0-40 (main, armor, offhand) plus the cursor are the same multiset as the baseline,
 * so items may be moved or worn but never dropped, added or swapped for something else.
 */
public final class LayoutGuard {
    private LayoutGuard() {}

    public static final int MAIN = 36;
    public static final int SIZE = 41;

    private static boolean empty(String s) { return s == null || s.isEmpty(); }

    private static List<String> multiset(String[] snap, String cursor) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < SIZE && i < snap.length; i++) if (!empty(snap[i])) out.add(snap[i]);
        if (!empty(cursor)) out.add(cursor);
        Collections.sort(out);
        return out;
    }

    public static boolean isValid(String[] baseline, String[] now, String cursor) {
        if (baseline == null || now == null) return false;
        return multiset(baseline, null).equals(multiset(now, cursor));
    }
}
