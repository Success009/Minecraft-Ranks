package com.p2ppvp.mod.client.ui;

import com.google.gson.JsonObject;
import java.util.function.IntUnaryOperator;

/** Pure model of a kit being arranged: which item sits in which slot. Slots 0-35 are movable, 36+ are fixed. No Minecraft imports. */
public final class LayoutBoard {
    public static final int MOVABLE = 36;
    private final int[] defaults;
    private final int[] cur;

    public LayoutBoard(int[] defaultSlots, IntUnaryOperator initialSlotFor) {
        this.defaults = defaultSlots.clone();
        this.cur = new int[defaults.length];
        boolean ok = true;
        boolean[] used = new boolean[64];
        for (int i = 0; i < defaults.length; i++) {
            int s = defaults[i] >= MOVABLE ? defaults[i] : initialSlotFor.applyAsInt(defaults[i]);
            if (s < 0 || s >= 64 || used[s] || (defaults[i] >= MOVABLE && s != defaults[i]) || (defaults[i] < MOVABLE && s >= MOVABLE)) { ok = false; break; }
            used[s] = true;
            cur[i] = s;
        }
        if (!ok) reset();
    }

    public int size() { return defaults.length; }
    public int slotOf(int item) { return cur[item]; }

    /** Index of the item in the slot, or -1. */
    public int itemAt(int slot) {
        for (int i = 0; i < cur.length; i++) if (cur[i] == slot) return i;
        return -1;
    }

    /** Moves the item in 'from' to 'to', swapping with whatever is there. Only slots 0-35 are allowed. */
    public boolean move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= MOVABLE || to >= MOVABLE) return false;
        int a = itemAt(from);
        if (a < 0) return false;
        int b = itemAt(to);
        cur[a] = to;
        if (b >= 0) cur[b] = from;
        return true;
    }

    public void reset() { System.arraycopy(defaults, 0, cur, 0, defaults.length); }

    public boolean isDefault() {
        for (int i = 0; i < cur.length; i++) if (cur[i] != defaults[i]) return false;
        return true;
    }

    /** Layout file format: default slot -> chosen slot, only for items that moved. */
    public JsonObject toMapping() {
        JsonObject o = new JsonObject();
        for (int i = 0; i < cur.length; i++) if (cur[i] != defaults[i]) o.addProperty(String.valueOf(defaults[i]), cur[i]);
        return o;
    }
}
