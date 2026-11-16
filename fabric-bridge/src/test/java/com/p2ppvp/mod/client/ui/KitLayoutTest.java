package com.p2ppvp.mod.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class KitLayoutTest {
    private static JsonObject root() {
        return KitLayout.parse("{\"crystal\":{\"0\":3,\"1\":0},\"pot\":{\"0\":5}}");
    }

    @Test
    void savedSlotIsUsed() {
        assertEquals(3, KitLayout.slotFor(root(), "crystal", 0));
        assertEquals(0, KitLayout.slotFor(root(), "Crystal", 1));
    }

    @Test
    void missingKitOrSlotFallsBackToDefault() {
        assertEquals(7, KitLayout.slotFor(root(), "uhc", 7));
        assertEquals(9, KitLayout.slotFor(root(), "crystal", 9));
        assertEquals(2, KitLayout.slotFor(null, "crystal", 2));
        assertEquals(2, KitLayout.slotFor(root(), null, 2));
    }

    @Test
    void outOfRangeSavedSlotFallsBackToDefault() {
        JsonObject r = KitLayout.parse("{\"pot\":{\"0\":99,\"1\":-1,\"2\":\"x\"}}");
        assertEquals(0, KitLayout.slotFor(r, "pot", 0));
        assertEquals(1, KitLayout.slotFor(r, "pot", 1));
        assertEquals(2, KitLayout.slotFor(r, "pot", 2));
    }

    @Test
    void duplicateTargetSlotsAreInvalid() {
        assertTrue(KitLayout.isValid(KitLayout.parse("{\"0\":3,\"1\":4}")));
        assertFalse(KitLayout.isValid(KitLayout.parse("{\"0\":3,\"1\":3}")));
        assertFalse(KitLayout.isValid(KitLayout.parse("{\"0\":50}")));
        assertFalse(KitLayout.isValid(null));
    }

    @Test
    void withKitReplacesAndRemovesWithoutMutatingInput() {
        JsonObject r = root();
        JsonObject updated = KitLayout.withKit(r, "Pot", KitLayout.parse("{\"0\":8}"));
        assertEquals(8, KitLayout.slotFor(updated, "pot", 0));
        assertEquals(5, KitLayout.slotFor(r, "pot", 0));
        JsonObject removed = KitLayout.withKit(updated, "pot", null);
        assertEquals(0, KitLayout.slotFor(removed, "pot", 0));
    }

    @Test
    void badJsonParsesToEmpty() {
        assertEquals(0, KitLayout.parse("not json").size());
        assertEquals(0, KitLayout.parse(null).size());
    }
}
