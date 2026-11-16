package com.p2ppvp.mod.client.ui;

/** Pure geometry for the title-screen card and its button rows. No Minecraft imports. */
public final class TitleLayout {
    private TitleLayout() {}

    public static final int CARD_W = 250;
    public static final int CARD_H = 220;
    public static final int ROWS_OFFSET = 104;   // first button row, relative to card top
    public static final int ROW_STEP = 24;       // spacing for rows 2..4 (26/50/74 in legacy code stay unchanged)
    public static final int LAST_ROW_BOTTOM = ROWS_OFFSET + 74 + 20;

    /** Card top Y: legacy position (centered, +10) shifted up just enough to stay on screen. */
    public static int cardY(int screenH) {
        int y = screenH / 2 - CARD_H / 2 + 10;
        int maxY = screenH - CARD_H - 2;
        if (y > maxY) y = maxY;
        return Math.max(2, y);
    }

    /** True when the whole card (and so its buttons) fits on the screen. */
    public static boolean fits(int screenW, int screenH) {
        return screenW >= CARD_W + 4 && screenH >= CARD_H + 4;
    }
}
