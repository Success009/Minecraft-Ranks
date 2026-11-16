package com.p2ppvp.mod.client.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PollSchedulerTest {
    @Test void firstPollIsImmediateThenWaitsInterval() {
        AtomicLong t = new AtomicLong(1000);
        PollScheduler s = new PollScheduler(t::get, 2000);
        assertTrue(s.shouldPoll());
        assertFalse(s.shouldPoll());
        t.addAndGet(1999);
        assertFalse(s.shouldPoll());
        t.addAndGet(1);
        assertTrue(s.shouldPoll());
    }

    @Test void disabledNeverPolls() {
        AtomicLong t = new AtomicLong(0);
        PollScheduler s = new PollScheduler(t::get, 2000);
        s.setEnabled(false);
        t.addAndGet(100000);
        assertFalse(s.shouldPoll());
    }

    @Test void reEnableFiresImmediately() {
        AtomicLong t = new AtomicLong(0);
        PollScheduler s = new PollScheduler(t::get, 2000);
        assertTrue(s.shouldPoll());
        s.setEnabled(false);
        s.setEnabled(true);
        assertTrue(s.shouldPoll());
    }

    @Test void backoffDoublesAndCaps() {
        AtomicLong t = new AtomicLong(0);
        PollScheduler s = new PollScheduler(t::get, 2000);
        assertEquals(2000, s.currentIntervalMs());
        s.onFailure();
        assertEquals(4000, s.currentIntervalMs());
        s.onFailure();
        assertEquals(8000, s.currentIntervalMs());
        for (int i = 0; i < 10; i++) s.onFailure();
        assertEquals(PollScheduler.MAX_BACKOFF_MS, s.currentIntervalMs());
    }

    @Test void successResetsBackoff() {
        AtomicLong t = new AtomicLong(0);
        PollScheduler s = new PollScheduler(t::get, 2000);
        s.onFailure();
        s.onFailure();
        s.onSuccess();
        assertEquals(2000, s.currentIntervalMs());
        assertEquals(0, s.failures());
    }
}
