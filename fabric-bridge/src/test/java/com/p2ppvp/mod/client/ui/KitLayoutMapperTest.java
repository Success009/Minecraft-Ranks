package com.p2ppvp.mod.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.p2ppvp.mod.client.ui.KitLayoutMapper.Item;
import java.util.List;
import org.junit.jupiter.api.Test;

class KitLayoutMapperTest {
    private static Item i(int slot, String key) { return new Item(slot, key); }

    @Test
    void unmovedItemsProduceEmptyMapping() {
        var d = List.of(i(0, "sword"), i(1, "gapple"));
        JsonObject m = KitLayoutMapper.build(d, d);
        assertNotNull(m);
        assertEquals(0, m.size());
    }

    @Test
    void movedItemMapsDefaultToCurrent() {
        var d = List.of(i(0, "sword"), i(1, "gapple"));
        var c = List.of(i(5, "sword"), i(1, "gapple"));
        JsonObject m = KitLayoutMapper.build(d, c);
        assertEquals(5, m.get("0").getAsInt());
        assertFalse(m.has("1"));
    }

    @Test
    void swappedItemsMapBothWays() {
        var d = List.of(i(0, "sword"), i(1, "gapple"));
        var c = List.of(i(1, "sword"), i(0, "gapple"));
        JsonObject m = KitLayoutMapper.build(d, c);
        assertEquals(1, m.get("0").getAsInt());
        assertEquals(0, m.get("1").getAsInt());
    }

    @Test
    void duplicatesMatchInSlotOrder() {
        var d = List.of(i(2, "splash"), i(3, "splash"));
        var c = List.of(i(10, "splash"), i(11, "splash"));
        JsonObject m = KitLayoutMapper.build(d, c);
        assertEquals(10, m.get("2").getAsInt());
        assertEquals(11, m.get("3").getAsInt());
    }

    @Test
    void armorAndUnknownItemsAreIgnored() {
        var d = List.of(i(0, "sword"), i(36, "boots"));
        var c = List.of(i(4, "sword"), i(37, "boots"), i(9, "dirt"));
        JsonObject m = KitLayoutMapper.build(d, c);
        assertEquals(1, m.size());
        assertEquals(4, m.get("0").getAsInt());
    }

    private static KitLayoutMapper.Item it(int slot, String key) { return new KitLayoutMapper.Item(slot, key); }

    @Test
    void missingIsEmptyWhenAllItemsPresent() {
        var defaults = java.util.List.of(it(0, "a"), it(1, "b"));
        var current = java.util.List.of(it(5, "a"), it(1, "b"));
        org.junit.jupiter.api.Assertions.assertTrue(KitLayoutMapper.missing(defaults, current).isEmpty());
    }

    @Test
    void missingReportsRemovedItem() {
        var defaults = java.util.List.of(it(0, "a"), it(1, "b"));
        var current = java.util.List.of(it(0, "a"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("b"), KitLayoutMapper.missing(defaults, current));
    }

    @Test
    void missingIgnoresExtraItems() {
        var defaults = java.util.List.of(it(0, "a"));
        var current = java.util.List.of(it(0, "a"), it(3, "creative_extra"));
        org.junit.jupiter.api.Assertions.assertTrue(KitLayoutMapper.missing(defaults, current).isEmpty());
    }

    @Test
    void missingCountsDuplicates() {
        var defaults = java.util.List.of(it(0, "p"), it(1, "p"), it(2, "p"));
        var current = java.util.List.of(it(0, "p"));
        org.junit.jupiter.api.Assertions.assertEquals(2, KitLayoutMapper.missing(defaults, current).size());
    }

    @Test
    void itemInOffhandIsNotMissingAndMapsTo40() {
        var d = List.of(i(0, "sword"), i(5, "totem"));
        var c = List.of(i(0, "sword"), i(40, "totem"));
        assertTrue(KitLayoutMapper.missing(d, c).isEmpty());
        JsonObject m = KitLayoutMapper.build(d, c);
        assertEquals(40, m.get("5").getAsInt());
        assertEquals(1, m.size());
    }

    @Test
    void armorSlotsStillNotCountedAsHoldingItems() {
        var d = List.of(i(5, "totem"));
        var c = List.of(i(38, "totem"));
        assertEquals(List.of("totem"), KitLayoutMapper.missing(d, c));
    }
}
