package com.p2ppvp.mod.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LayoutBoardTest {
    private static LayoutBoard board() { return new LayoutBoard(new int[] {0, 1, 2, 39}, s -> s); }

    @Test void startsAtDefaults() {
        LayoutBoard b = board();
        assertTrue(b.isDefault());
        assertEquals(0, b.toMapping().size());
        assertEquals(1, b.itemAt(1));
    }

    @Test void moveToEmptySlot() {
        LayoutBoard b = board();
        assertTrue(b.move(0, 20));
        assertEquals(20, b.slotOf(0));
        assertEquals(20, b.toMapping().get("0").getAsInt());
    }

    @Test void moveSwapsOccupants() {
        LayoutBoard b = board();
        assertTrue(b.move(0, 1));
        assertEquals(1, b.slotOf(0));
        assertEquals(0, b.slotOf(1));
    }

    @Test void armorAndEmptyAreLocked() {
        LayoutBoard b = board();
        assertFalse(b.move(39, 5));
        assertFalse(b.move(5, 6));
        assertFalse(b.move(0, 39));
        assertFalse(b.move(0, 0));
    }

    @Test void resetRestoresDefaults() {
        LayoutBoard b = board();
        b.move(0, 30);
        b.reset();
        assertTrue(b.isDefault());
    }

    @Test void savedLayoutIsLoadedAndBadOnesFallBack() {
        LayoutBoard ok = new LayoutBoard(new int[] {0, 1}, s -> s == 0 ? 7 : s);
        assertEquals(7, ok.slotOf(0));
        LayoutBoard dup = new LayoutBoard(new int[] {0, 1}, s -> 5);
        assertTrue(dup.isDefault());
    }
}
