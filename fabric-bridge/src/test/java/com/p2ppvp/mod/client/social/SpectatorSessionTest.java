package com.p2ppvp.mod.client.social;

import static org.junit.jupiter.api.Assertions.*;

import com.p2ppvp.mod.client.social.SpectatorSession.State;
import org.junit.jupiter.api.Test;

class SpectatorSessionTest {

    @Test
    void startsIdle() {
        SpectatorSession s = new SpectatorSession();
        assertEquals(State.IDLE, s.state());
        assertFalse(s.isActive());
        assertNull(s.matchId());
        assertNull(s.hostIp());
    }

    @Test
    void happyPathJoinThenDisconnect() {
        SpectatorSession s = new SpectatorSession();
        assertTrue(s.begin("m1"));
        assertEquals(State.JOINING, s.state());
        assertTrue(s.isActive());
        assertTrue(s.onJoined("100.64.0.5"));
        assertEquals(State.SPECTATING, s.state());
        assertEquals("100.64.0.5", s.hostIp());
        assertEquals("m1", s.matchId());
        assertTrue(s.onDisconnect());
        assertEquals(State.IDLE, s.state());
        assertNull(s.matchId());
        assertNull(s.hostIp());
    }

    @Test
    void secondBeginIsRejectedWhileActive() {
        SpectatorSession s = new SpectatorSession();
        assertTrue(s.begin("m1"));
        assertFalse(s.begin("m2"));
        assertEquals("m1", s.matchId());
        s.onJoined("1.2.3.4");
        assertFalse(s.begin("m3"));
        assertEquals("m1", s.matchId());
    }

    @Test
    void beginRejectsNullAndEmptyMatchId() {
        SpectatorSession s = new SpectatorSession();
        assertFalse(s.begin(null));
        assertFalse(s.begin(""));
        assertEquals(State.IDLE, s.state());
    }

    @Test
    void joinFailureResetsToIdle() {
        SpectatorSession s = new SpectatorSession();
        s.begin("m1");
        s.onJoinFailed();
        assertEquals(State.IDLE, s.state());
        assertNull(s.matchId());
        assertTrue(s.begin("m2"));
    }

    @Test
    void joinFailureDoesNotAffectConfirmedSession() {
        SpectatorSession s = new SpectatorSession();
        s.begin("m1");
        s.onJoined("1.2.3.4");
        s.onJoinFailed();
        assertEquals(State.SPECTATING, s.state());
    }

    @Test
    void onJoinedRejectedWhenIdleOrBadIp() {
        SpectatorSession s = new SpectatorSession();
        assertFalse(s.onJoined("1.2.3.4"));
        s.begin("m1");
        assertFalse(s.onJoined(null));
        assertFalse(s.onJoined(""));
        assertEquals(State.JOINING, s.state());
    }

    @Test
    void disconnectWhileIdleNeedsNoLeave() {
        SpectatorSession s = new SpectatorSession();
        assertFalse(s.onDisconnect());
    }

    @Test
    void disconnectWhileJoiningStillSendsLeave() {
        SpectatorSession s = new SpectatorSession();
        s.begin("m1");
        assertTrue(s.onDisconnect());
        assertEquals(State.IDLE, s.state());
    }
}
