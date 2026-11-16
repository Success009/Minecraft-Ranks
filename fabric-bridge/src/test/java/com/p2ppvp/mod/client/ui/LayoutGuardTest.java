package com.p2ppvp.mod.client.ui;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LayoutGuardTest {
    private static String[] base() {
        String[] s = new String[41];
        s[0] = "sword"; s[1] = "pearl"; s[2] = "totem";
        s[36] = "boots"; s[37] = "legs"; s[38] = "chest"; s[39] = "helm"; s[40] = "shield";
        return s;
    }

    @Test void unchangedIsValid() { assertTrue(LayoutGuard.isValid(base(), base(), null)); }

    @Test void swapInMainIsValid() {
        String[] n = base(); n[0] = "pearl"; n[1] = "sword";
        assertTrue(LayoutGuard.isValid(base(), n, null));
    }

    @Test void moveToEmptySlotIsValid() {
        String[] n = base(); n[10] = n[0]; n[0] = null;
        assertTrue(LayoutGuard.isValid(base(), n, null));
    }

    @Test void itemOnCursorCounts() {
        String[] n = base(); n[0] = null;
        assertTrue(LayoutGuard.isValid(base(), n, "sword"));
        assertFalse(LayoutGuard.isValid(base(), n, null));
    }

    @Test void droppedItemIsInvalid() {
        String[] n = base(); n[2] = null;
        assertFalse(LayoutGuard.isValid(base(), n, null));
    }

    @Test void extraItemIsInvalid() {
        String[] n = base(); n[5] = "elytra";
        assertFalse(LayoutGuard.isValid(base(), n, null));
    }

    @Test void takingArmorOffIsValid() {
        String[] n = base(); n[38] = null; n[3] = "chest";
        assertTrue(LayoutGuard.isValid(base(), n, null));
    }

    @Test void armorOnCursorIsValid() {
        String[] n = base(); n[38] = null;
        assertTrue(LayoutGuard.isValid(base(), n, "chest"));
    }

    @Test void swappingArmorWithInventoryItemIsValid() {
        String[] b = base(); b[3] = "chest2";
        String[] n = b.clone(); n[38] = "chest2"; n[3] = "chest";
        assertTrue(LayoutGuard.isValid(b, n, null));
    }

    @Test void armorSlotItemDroppedIsInvalid() {
        String[] n = base(); n[38] = null;
        assertFalse(LayoutGuard.isValid(base(), n, null));
    }

    @Test void swappingArmorForForeignItemIsInvalid() {
        String[] n = base(); n[38] = "elytra";
        assertFalse(LayoutGuard.isValid(base(), n, null));
    }

    @Test void duplicatesAreCounted() {
        String[] b = base(); b[3] = "pot"; b[4] = "pot";
        String[] n = b.clone(); n[4] = null;
        assertFalse(LayoutGuard.isValid(b, n, null));
        n[20] = "pot";
        assertTrue(LayoutGuard.isValid(b, n, null));
    }
}
