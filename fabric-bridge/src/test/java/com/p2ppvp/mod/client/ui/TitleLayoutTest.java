package com.p2ppvp.mod.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TitleLayoutTest {
    @Test
    void lastButtonRowIsInsideTheCard() {
        assertTrue(TitleLayout.LAST_ROW_BOTTOM < TitleLayout.CARD_H);
    }

    @Test
    void cardStaysOnScreenWhenItFits() {
        for (int[] s : new int[][] {{854, 480}, {640, 360}, {480, 270}, {427, 240}}) {
            int y = TitleLayout.cardY(s[1]);
            assertTrue(TitleLayout.fits(s[0], s[1]), s[0] + "x" + s[1]);
            assertTrue(y >= 0 && y + TitleLayout.CARD_H <= s[1], "card inside at " + s[0] + "x" + s[1] + " y=" + y);
        }
    }

    @Test
    void legacyPositionIsKeptOnRoomyScreens() {
        assertEquals(480 / 2 - 110 + 10, TitleLayout.cardY(480));
    }

    @Test
    void tooSmallScreensAreReportedAndCardYIsNeverNegative() {
        assertFalse(TitleLayout.fits(300, 200));
        assertTrue(TitleLayout.cardY(200) >= 0);
    }
}
