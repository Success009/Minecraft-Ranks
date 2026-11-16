package com.p2ppvp.mod.client.social;

import java.util.function.LongSupplier;

/**
 * Pure timing rules for the social poller (no Minecraft, no network).
 * Heartbeat + challenge poll run every {@code intervalMs} while enabled; after failures the
 * interval backs off (doubling, capped) and resets on the first success.
 */
public final class PollScheduler {
    public static final long DEFAULT_INTERVAL_MS = 2000;
    public static final long MAX_BACKOFF_MS = 16000;

    private final LongSupplier clock;
    private final long baseMs;
    private long nextAt;
    private int failures;
    private boolean enabled = true;

    public PollScheduler(LongSupplier clock, long baseMs) {
        this.clock = clock;
        this.baseMs = Math.max(1, baseMs);
        this.nextAt = clock.getAsLong();
    }

    public PollScheduler(LongSupplier clock) { this(clock, DEFAULT_INTERVAL_MS); }

    /** Disabled while in a match or when the client is not on a menu screen. */
    public void setEnabled(boolean on) {
        if (on && !enabled) nextAt = clock.getAsLong();
        enabled = on;
    }

    public boolean isEnabled() { return enabled; }

    /** True if a poll should start now. Marks the next slot so callers cannot double-fire. */
    public boolean shouldPoll() {
        if (!enabled) return false;
        long now = clock.getAsLong();
        if (now < nextAt) return false;
        nextAt = now + currentIntervalMs();
        return true;
    }

    public void onSuccess() {
        failures = 0;
    }

    public void onFailure() {
        failures++;
    }

    public long currentIntervalMs() {
        long v = baseMs;
        for (int i = 0; i < failures && v < MAX_BACKOFF_MS; i++) v *= 2;
        return Math.min(v, MAX_BACKOFF_MS);
    }

    public int failures() { return failures; }
}
