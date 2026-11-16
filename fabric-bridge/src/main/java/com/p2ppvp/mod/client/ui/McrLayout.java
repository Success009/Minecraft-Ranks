package com.p2ppvp.mod.client.ui;

/**
 * Pure layout math for MCR screens. No Minecraft imports.
 * All sizes are in GUI (scaled) pixels, i.e. Screen.width / Screen.height.
 */
public final class McrLayout {
    private McrLayout() {}

    public static final int MIN_W = 200;
    public static final int MIN_H = 120;

    public record Rect(int x, int y, int w, int h) {
        public int right() { return x + w; }
        public int bottom() { return y + h; }
        public boolean contains(int px, int py) { return px >= x && px < x + w && py >= y && py < y + h; }
        public boolean inside(int sw, int sh) { return x >= 0 && y >= 0 && right() <= sw && bottom() <= sh; }
    }

    /** Card centered on screen, clamped to [min, max] and never larger than the screen minus a margin. */
    public static Rect card(int sw, int sh, int prefW, int prefH, int margin) {
        int maxW = Math.max(1, sw - 2 * margin);
        int maxH = Math.max(1, sh - 2 * margin);
        int w = Math.min(prefW, maxW);
        int h = Math.min(prefH, maxH);
        return new Rect((sw - w) / 2, (sh - h) / 2, w, h);
    }

    /**
     * Splits a vertical area into n equal-height rows with a gap. Row height is capped at maxRowH
     * and shrinks (down to 1) when the area is too small. Returned rects never leave the area.
     */
    public static Rect[] rows(Rect area, int n, int maxRowH, int gap) {
        if (n <= 0) return new Rect[0];
        int g = n > 1 ? Math.max(0, gap) : 0;
        int usable = Math.max(n, area.h() - g * (n - 1));
        int rowH = Math.max(1, Math.min(maxRowH, usable / n));
        // If even rowH=1 plus gaps overflow, drop the gap.
        if (rowH * n + g * (n - 1) > area.h()) {
            g = 0;
            rowH = Math.max(1, Math.min(maxRowH, area.h() / n));
        }
        Rect[] out = new Rect[n];
        for (int i = 0; i < n; i++) {
            out[i] = new Rect(area.x(), area.y() + i * (rowH + g), area.w(), rowH);
        }
        return out;
    }

    /** Two side-by-side buttons filling a row, separated by gap. */
    public static Rect[] splitRow(Rect row, int gap) {
        int g = Math.max(0, Math.min(gap, row.w() - 2));
        int w1 = (row.w() - g) / 2;
        int w2 = row.w() - g - w1;
        return new Rect[] {
            new Rect(row.x(), row.y(), w1, row.h()),
            new Rect(row.x() + w1 + g, row.y(), w2, row.h())
        };
    }

    /**
     * List area for scrolling screens: card minus a header band on top and a footer band at the bottom
     * (both clamped so the list keeps at least one row of height).
     */
    public static Rect listArea(Rect card, int header, int footer, int padding, int minListH) {
        int avail = card.h() - 2 * padding;
        int h = header, f = footer;
        if (avail - h - f < minListH) {
            // Shrink header then footer proportionally, never below zero.
            int over = minListH - (avail - h - f);
            int cutH = Math.min(h, (over + 1) / 2);
            h -= cutH;
            over -= cutH;
            f = Math.max(0, f - over);
        }
        int listH = Math.max(1, avail - h - f);
        return new Rect(card.x() + padding, card.y() + padding + h, Math.max(1, card.w() - 2 * padding), listH);
    }

    /** How many whole rows of rowH (+gap) fit in listH; at least 1. */
    public static int visibleRows(int listH, int rowH, int gap) {
        int step = Math.max(1, rowH + Math.max(0, gap));
        return Math.max(1, (listH + Math.max(0, gap)) / step);
    }

    /** Clamps a scroll offset to [0, total - visible]. */
    public static int clampScroll(int offset, int total, int visible) {
        return Math.max(0, Math.min(offset, Math.max(0, total - visible)));
    }

    /** Bottom-right anchored box (toasts), kept fully on screen. */
    public static Rect bottomRight(int sw, int sh, int w, int h, int margin) {
        int bw = Math.min(w, Math.max(1, sw - margin));
        int bh = Math.min(h, Math.max(1, sh - margin));
        return new Rect(Math.max(0, sw - bw - margin), Math.max(0, sh - bh - margin), bw, bh);
    }

    public static boolean isTiny(int sw, int sh) { return sw < MIN_W || sh < MIN_H; }
}
