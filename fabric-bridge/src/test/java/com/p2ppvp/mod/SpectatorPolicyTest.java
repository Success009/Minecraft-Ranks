package com.p2ppvp.mod;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpectatorPolicyTest {
    @BeforeEach @AfterEach
    void reset() { SpectatorPolicy.clear(); }

    @Test
    void hostAlwaysAllowed() {
        assertEquals(SpectatorPolicy.Role.HOST, SpectatorPolicy.roleFor("Me", true, "Foe", false, 0));
    }

    @Test
    void opponentMatchedCaseInsensitively() {
        assertEquals(SpectatorPolicy.Role.OPPONENT, SpectatorPolicy.roleFor("fOe", false, "Foe", false, 0));
    }

    @Test
    void mockOpponentAllowed() {
        assertEquals(SpectatorPolicy.Role.OPPONENT, SpectatorPolicy.roleFor("Anyone", false, "Mock_Opponent", true, 0));
    }

    @Test
    void strangerDeniedByDefault() {
        assertEquals(SpectatorPolicy.Role.DENIED, SpectatorPolicy.roleFor("Rando", false, "Foe", false, 0));
    }

    @Test
    void authorizedSpectatorAllowed() {
        SpectatorPolicy.setAuthorized(List.of("Watcher"));
        assertEquals(SpectatorPolicy.Role.SPECTATOR, SpectatorPolicy.roleFor("watcher", false, "Foe", false, 0));
    }

    @Test
    void capEnforcedHostSide() {
        SpectatorPolicy.setAuthorized(List.of("S1"));
        assertEquals(SpectatorPolicy.Role.SPECTATOR, SpectatorPolicy.roleFor("S1", false, "Foe", false, 4));
        assertEquals(SpectatorPolicy.Role.DENIED, SpectatorPolicy.roleFor("S1", false, "Foe", false, 5));
    }

    @Test
    void authorizedListTruncatedToCap() {
        SpectatorPolicy.setAuthorized(Arrays.asList("a", "b", "c", "d", "e", "f"));
        assertTrue(SpectatorPolicy.isAuthorizedSpectator("e"));
        assertFalse(SpectatorPolicy.isAuthorizedSpectator("f"));
    }

    @Test
    void blankAndNullNamesIgnored() {
        SpectatorPolicy.setAuthorized(Arrays.asList("", "  ", null, "ok"));
        assertTrue(SpectatorPolicy.isAuthorizedSpectator("OK"));
        assertFalse(SpectatorPolicy.isAuthorizedSpectator(""));
        assertFalse(SpectatorPolicy.isAuthorizedSpectator(null));
    }

    @Test
    void setAuthorizedReplacesPreviousSet() {
        SpectatorPolicy.setAuthorized(List.of("old"));
        SpectatorPolicy.setAuthorized(List.of("new"));
        assertFalse(SpectatorPolicy.isAuthorizedSpectator("old"));
        assertTrue(SpectatorPolicy.isAuthorizedSpectator("new"));
    }

    @Test
    void spectatorsAreNotCombatants() {
        SpectatorPolicy.setAuthorized(List.of("Watcher"));
        assertFalse(SpectatorPolicy.isCombatant("Watcher", false, "Foe"));
        assertTrue(SpectatorPolicy.isCombatant("Foe", false, "Foe"));
        assertTrue(SpectatorPolicy.isCombatant("Me", true, "Foe"));
    }

    @Test
    void customCapRespected() {
        SpectatorPolicy.setCap(3);
        SpectatorPolicy.setAuthorized(List.of("a", "b", "c", "d"));
        assertFalse(SpectatorPolicy.isAuthorizedSpectator("d"));
        assertEquals(SpectatorPolicy.Role.DENIED, SpectatorPolicy.roleFor("a", false, "x", false, 3));
    }
}
