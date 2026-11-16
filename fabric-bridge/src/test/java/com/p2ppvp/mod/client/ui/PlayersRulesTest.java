package com.p2ppvp.mod.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.p2ppvp.mod.client.social.SocialModels.OnlinePlayer;
import org.junit.jupiter.api.Test;

class PlayersRulesTest {
    private static OnlinePlayer p(String id, boolean ok, double cd) {
        return new OnlinePlayer(id, id, "idle", 10, ok, cd);
    }

    @Test
    void noSelectionAndSelfAreDisabled() {
        assertFalse(PlayersRules.buttonFor(null, "Me", true).enabled());
        assertEquals("That's you", PlayersRules.buttonFor(p("Me", true, 0), "Me", true).label());
    }

    @Test
    void offlineApiDisablesEvenForChallengeablePlayer() {
        assertEquals("Offline", PlayersRules.buttonFor(p("Bob", true, 0), "Me", false).label());
    }

    @Test
    void cooldownShowsCeilingSecondsAndBeatsUnavailable() {
        PlayersRules.ButtonState s = PlayersRules.buttonFor(p("Bob", false, 4.2), "Me", true);
        assertFalse(s.enabled());
        assertEquals("Wait 5s", s.label());
    }

    @Test
    void busyPlayerIsUnavailableAndIdleIsChallengeable() {
        assertEquals("Unavailable", PlayersRules.buttonFor(p("Bob", false, 0), "Me", true).label());
        PlayersRules.ButtonState s = PlayersRules.buttonFor(p("Bob", true, 0), "Me", true);
        assertTrue(s.enabled());
        assertEquals("Challenge", s.label());
    }

    @Test
    void kitCycleWrapsAndHandlesUnknown() {
        assertEquals("UHC", PlayersRules.nextKit("Crystal"));
        assertEquals("Crystal", PlayersRules.nextKit("Sword"));
        assertEquals("Crystal", PlayersRules.nextKit(null));
        assertEquals("Crystal", PlayersRules.nextKit("Nope"));
    }

    @Test
    void statusColors() {
        assertEquals("§c", PlayersRules.statusColor("in_match"));
        assertEquals("§e", PlayersRules.statusColor("queued"));
        assertEquals("§a", PlayersRules.statusColor("idle"));
        assertEquals("§a", PlayersRules.statusColor(null));
    }
}
