package com.p2ppvp.mod.client.social;

import static org.junit.jupiter.api.Assertions.*;

import com.p2ppvp.mod.client.social.SocialModels.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SocialJsonTest {
    @Test void parsesPlayers() {
        List<OnlinePlayer> p = SocialJson.players("{\"players\":[{\"player_id\":\"A\",\"name\":\"Alpha\",\"status\":\"in_match\",\"points\":120,\"challengeable\":false,\"cooldown\":4.5},{\"player_id\":\"B\"}]}");
        assertEquals(2, p.size());
        assertEquals("Alpha", p.get(0).name());
        assertFalse(p.get(0).challengeable());
        assertEquals(4.5, p.get(0).cooldown());
        assertEquals("B", p.get(1).name());
        assertEquals("idle", p.get(1).status());
        assertEquals(100, p.get(1).points());
    }

    @Test void skipsPlayersWithoutId() {
        assertTrue(SocialJson.players("{\"players\":[{\"name\":\"x\"},5]}").isEmpty());
    }

    @Test void parsesLiveMatches() {
        List<LiveMatch> m = SocialJson.liveMatches("{\"matches\":[{\"match_id\":\"m1\",\"host\":\"A\",\"guest\":\"B\",\"kit\":{\"type\":\"official\",\"name\":\"Pot\"},\"spectator_count\":5,\"max_spectators\":5,\"full\":true}]}");
        assertEquals(1, m.size());
        assertEquals("Pot", m.get(0).kit().name());
        assertEquals("Ranked", m.get(0).kit().typeLabel());
        assertTrue(m.get(0).full());
        assertEquals(5, m.get(0).spectatorCount());
    }

    @Test void kitAcceptsPlainStringAndNull() {
        assertEquals("UHC", SocialJson.liveMatches("{\"matches\":[{\"match_id\":\"x\",\"kit\":\"UHC\"}]}").get(0).kit().name());
        assertEquals("Crystal", SocialJson.liveMatches("{\"matches\":[{\"match_id\":\"x\"}]}").get(0).kit().name());
    }

    @Test void parsesPollIncomingAndOutcomes() {
        PollResult r = SocialJson.poll("{\"incoming\":[{\"challenge_id\":\"c1\",\"sender\":\"A\",\"kit\":{\"type\":\"custom\",\"name\":\"MyKit\"},\"seconds_left\":21.5}],\"outcomes\":[{\"challenge_id\":\"c0\",\"result\":\"declined\",\"target\":\"B\",\"kit\":{\"type\":\"official\",\"name\":\"Mace\"}}]}");
        assertEquals(1, r.incoming().size());
        assertEquals("Custom", r.incoming().get(0).kit().typeLabel());
        assertEquals(21.5, r.incoming().get(0).secondsLeft());
        assertEquals("declined", r.outcomes().get(0).result());
    }

    @Test void badJsonGivesEmpty() {
        assertTrue(SocialJson.players("not json").isEmpty());
        assertTrue(SocialJson.liveMatches("").isEmpty());
        assertTrue(SocialJson.poll("[1,2]").incoming().isEmpty());
    }

    @Test void rosterNamesPreferDisplayName() {
        assertEquals(List.of("SpecOne", "S2"), SocialJson.rosterNames("{\"spectators\":[{\"player_id\":\"S1\",\"name\":\"SpecOne\"},{\"player_id\":\"S2\"}]}"));
    }

    @Test void errorFields() {
        String j = "{\"error\":\"slow down\",\"code\":\"cooldown\",\"retry_after\":12.5}";
        assertEquals("cooldown", SocialJson.errorCode(j));
        assertEquals("slow down", SocialJson.errorMessage(j));
        assertEquals(12.5, SocialJson.retryAfter(j));
    }
}
