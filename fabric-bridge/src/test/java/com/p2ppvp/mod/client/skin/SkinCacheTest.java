package com.p2ppvp.mod.client.skin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkinCacheTest {
    private static final long TTL = 1000L;

    @Test
    void keyIsLowercaseTrimmedAndNullSafe() {
        assertEquals("steve_1", SkinCache.key("  Steve_1 "));
        assertEquals("", SkinCache.key(null));
        assertEquals("steve-abcd1234", SkinCache.cacheKey("Steve", "abcd1234"));
    }

    @Test
    void namesToQueryDedupesAndSkipsInvalid() {
        SkinCache c = new SkinCache(TTL);
        List<String> out = c.namesToQuery(List.of("Alpha", "alpha", " ", "", "x".repeat(17), "Bravo"), 0);
        assertEquals(List.of("alpha", "bravo"), out);
    }

    @Test
    void namesToQueryNullIsEmpty() {
        assertTrue(new SkinCache(TTL).namesToQuery(null, 0).isEmpty());
    }

    @Test
    void namesToQueryCapsAtMaxBatch() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add("p" + i);
        assertEquals(SkinCache.MAX_BATCH, new SkinCache(TTL).namesToQuery(many, 0).size());
    }

    @Test
    void missingNameIsSuppressedUntilTtlExpires() {
        SkinCache c = new SkinCache(TTL);
        c.staleAfterLookup(List.of("ghost"), Map.of(), 100);
        assertTrue(c.namesToQuery(List.of("ghost"), 500).isEmpty());
        assertEquals(List.of("ghost"), c.namesToQuery(List.of("ghost"), 1100));
    }

    @Test
    void newTokenIsStaleAndCachedTokenIsNot() {
        SkinCache c = new SkinCache(TTL);
        assertEquals(List.of("alpha"), c.staleAfterLookup(List.of("Alpha"), Map.of("alpha", "t1"), 0));
        c.markCached("Alpha", "t1");
        assertTrue(c.staleAfterLookup(List.of("alpha"), Map.of("alpha", "t1"), 0).isEmpty());
        assertEquals(List.of("alpha"), c.staleAfterLookup(List.of("alpha"), Map.of("alpha", "t2"), 0));
    }

    @Test
    void tokenAppearingClearsMissMark() {
        SkinCache c = new SkinCache(TTL);
        c.staleAfterLookup(List.of("late"), Map.of(), 0);
        assertEquals(List.of("late"), c.staleAfterLookup(List.of("late"), Map.of("late", "t1"), 10));
        assertEquals(List.of("late"), c.namesToQuery(List.of("late"), 20));
    }

    @Test
    void skinRemovedOnServerDropsCachedState() {
        SkinCache c = new SkinCache(TTL);
        c.markCached("gone", "t1");
        assertTrue(c.hasSkin("gone"));
        c.staleAfterLookup(List.of("gone"), Map.of(), 0);
        assertFalse(c.hasSkin("gone"));
        assertNull(c.tokenOf("gone"));
    }

    @Test
    void invalidateForgetsTokenAndMiss() {
        SkinCache c = new SkinCache(TTL);
        c.markCached("me", "t1");
        c.staleAfterLookup(List.of("other"), null, 0);
        c.invalidate("Me");
        c.invalidate("other");
        assertNull(c.tokenOf("me"));
        assertEquals(List.of("other"), c.namesToQuery(List.of("other"), 1));
    }
}
