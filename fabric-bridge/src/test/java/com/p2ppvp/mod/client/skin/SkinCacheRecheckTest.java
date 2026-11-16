package com.p2ppvp.mod.client.skin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkinCacheRecheckTest {
    private static final long TTL = 1000L;
    private static final long RECHECK = 30_000L;

    @Test
    void verifiedCachedPlayerIsSkippedUntilRecheckExpires() {
        SkinCache c = new SkinCache(TTL, RECHECK);
        c.staleAfterLookup(List.of("alpha"), Map.of("alpha", "t1"), 0);
        c.markCached("alpha", "t1");
        assertTrue(c.namesToQuery(List.of("alpha"), 10_000).isEmpty());
        assertEquals(List.of("alpha"), c.namesToQuery(List.of("alpha"), 30_001));
    }

    @Test
    void playerWithoutCachedSkinIsStillQueried() {
        SkinCache c = new SkinCache(TTL, RECHECK);
        c.staleAfterLookup(List.of("alpha"), Map.of("alpha", "t1"), 0);
        assertEquals(List.of("alpha"), c.namesToQuery(List.of("alpha"), 100));
    }

    @Test
    void invalidateMakesPlayerQueryableAgain() {
        SkinCache c = new SkinCache(TTL, RECHECK);
        c.staleAfterLookup(List.of("alpha"), Map.of("alpha", "t1"), 0);
        c.markCached("alpha", "t1");
        c.invalidate("Alpha");
        assertEquals(List.of("alpha"), c.namesToQuery(List.of("alpha"), 100));
    }

    @Test
    void zeroRecheckKeepsOldBehaviour() {
        SkinCache c = new SkinCache(TTL);
        c.staleAfterLookup(List.of("alpha"), Map.of("alpha", "t1"), 0);
        c.markCached("alpha", "t1");
        assertEquals(List.of("alpha"), c.namesToQuery(List.of("alpha"), 100));
    }
}
