package com.p2ppvp.mod.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChallengeKitTest {
    private static Set<String> set(String... s) {
        return new LinkedHashSet<>(java.util.Arrays.asList(s));
    }

    @Test
    void officialKitIsUsable() {
        ChallengeKit.Choice c = ChallengeKit.from(set("Crystal"));
        assertTrue(c.ok());
        assertEquals("official", c.kit().type());
        assertEquals("Crystal", c.kit().name());
        assertEquals("Ranked Crystal", c.label());
    }

    @Test
    void communityKitIsUsable() {
        ChallengeKit.Choice c = ChallengeKit.from(set("Community:SpearDrift"));
        assertTrue(c.ok());
        assertEquals("community", c.kit().type());
        assertEquals("SpearDrift", c.kit().name());
    }

    @Test
    void unofficialPrefixMapsToCommunity() {
        ChallengeKit.Choice c = ChallengeKit.from(set("Unofficial:Foo"));
        assertTrue(c.ok());
        assertEquals("community", c.kit().type());
        assertEquals("Foo", c.kit().name());
    }

    @Test
    void randomAsksForSpecificKit() {
        ChallengeKit.Choice c = ChallengeKit.from(set("Random"));
        assertFalse(c.ok());
        assertEquals("Pick one kit first", c.hint());
    }

    @Test
    void emptyAndNullAskForKit() {
        assertFalse(ChallengeKit.from(set()).ok());
        assertFalse(ChallengeKit.from(null).ok());
    }

    @Test
    void severalKitsAreRejected() {
        ChallengeKit.Choice c = ChallengeKit.from(set("Crystal", "Pot"));
        assertFalse(c.ok());
        assertEquals("Pick only one kit", c.hint());
    }

    @Test
    void customKitsAreRejected() {
        assertFalse(ChallengeKit.from(set("Custom:My Kit")).ok());
        assertFalse(ChallengeKit.from(set("Custom:Random")).ok());
    }

    @Test
    void emptyCommunityNameIsRejected() {
        assertFalse(ChallengeKit.from(set("Community:")).ok());
    }

    @Test
    void randomResolvesToAnOfficialKit() {
        var c = ChallengeKit.resolve(set("Random"), new java.util.Random(1));
        assertTrue(c.ok());
        assertEquals("official", c.kit().type());
        assertEquals("Ranked Random", ChallengeKit.label(set("Random")));
    }

    @Test
    void severalKitsPickOneOfThem() {
        for (long seed = 0; seed < 20; seed++) {
            var c = ChallengeKit.resolve(set("Crystal", "Pot"), new java.util.Random(seed));
            assertTrue(c.ok());
            assertTrue(c.kit().name().equals("Crystal") || c.kit().name().equals("Pot"));
        }
        assertEquals("Random of Crystal, Pot", ChallengeKit.label(set("Crystal", "Pot")));
    }

    @Test
    void customOnlyIsNotResolvable() {
        assertFalse(ChallengeKit.resolve(set("Custom:X"), new java.util.Random(1)).ok());
    }
}
