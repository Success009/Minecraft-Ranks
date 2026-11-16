package com.p2ppvp.mod.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure toast model: FIFO queue, visible cap, slide-in/hold/slide-out timing. No Minecraft imports.
 * Time is passed in (epoch millis or any monotonic millis) so tests need no real clock.
 */
public final class ToastQueue {
    public enum Kind { CHALLENGE, DECLINED, EXPIRED, COOLDOWN, ACCEPTED, INFO }

    public static final int MAX_VISIBLE = 3;
    public static final long SLIDE_IN_MS = 250;
    public static final long SLIDE_OUT_MS = 200;

    public static final class Toast {
        public final long id;
        public final Kind kind;
        public final String challengeId; // null unless CHALLENGE
        public final String title;
        public final String line1;
        public final String line2;
        public final String kitType;
        public final String kitName;
        public final long holdMs;

        long shownAt = -1;      // when it became visible (slide-in start)
        long dismissAt = -1;    // when slide-out started, -1 if not dismissing
        boolean accepted;       // for CHALLENGE: last user action, informational

        Toast(long id, Kind kind, String challengeId, String title, String line1, String line2,
              String kitType, String kitName, long holdMs) {
            this.id = id; this.kind = kind; this.challengeId = challengeId; this.title = title;
            this.line1 = line1; this.line2 = line2; this.kitType = kitType; this.kitName = kitName;
            this.holdMs = holdMs;
        }

        public boolean isChallenge() { return kind == Kind.CHALLENGE; }
        public boolean isDismissing() { return dismissAt >= 0; }
        public boolean wasAccepted() { return accepted; }

        /** 0 = fully off-screen, 1 = fully shown. */
        public double slideProgress(long now) {
            if (shownAt < 0) return 0;
            if (dismissAt >= 0) {
                double t = clamp01((now - dismissAt) / (double) SLIDE_OUT_MS);
                return 1.0 - easeIn(t);
            }
            double t = clamp01((now - shownAt) / (double) SLIDE_IN_MS);
            return easeOut(t);
        }

        /** Horizontal shift in pixels to draw at (0 when shown, width when hidden). */
        public int offsetX(int width, long now) {
            return (int) Math.round((1.0 - slideProgress(now)) * width);
        }

        /** Fraction of hold time remaining, 1 -> 0, for a countdown bar. */
        public double holdRemaining(long now) {
            if (shownAt < 0 || holdMs <= 0) return 1;
            return 1.0 - clamp01((now - shownAt - SLIDE_IN_MS) / (double) holdMs);
        }
    }

    private final List<Toast> pending = new ArrayList<>();
    private final List<Toast> visible = new ArrayList<>();
    private long nextId = 1;

    public static double easeOut(double t) { double u = 1 - clamp01(t); return 1 - u * u * u; }
    public static double easeIn(double t) { double c = clamp01(t); return c * c * c; }
    static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** Adds a result/info toast. Returns its id. */
    public synchronized long push(Kind kind, String title, String line1, String line2,
                                  String kitType, String kitName, long holdMs) {
        Toast t = new Toast(nextId++, kind, null, title, line1, line2, kitType, kitName, holdMs);
        pending.add(t);
        return t.id;
    }

    /**
     * Adds a challenge toast. Returns its id, or -1 if a toast for this challengeId already exists
     * (so repeated polls don't stack duplicates).
     */
    public synchronized long pushChallenge(String challengeId, String sender, String kitType, String kitName, long holdMs) {
        if (challengeId == null || hasChallenge(challengeId)) return -1;
        Toast t = new Toast(nextId++, Kind.CHALLENGE, challengeId, sender, "challenges you", null, kitType, kitName, holdMs);
        pending.add(t);
        return t.id;
    }

    public synchronized boolean hasChallenge(String challengeId) {
        for (Toast t : visible) if (challengeId.equals(t.challengeId)) return true;
        for (Toast t : pending) if (challengeId.equals(t.challengeId)) return true;
        return false;
    }

    /** Advances the model: promotes pending toasts, starts timeouts, removes finished ones. */
    public synchronized void update(long now) {
        // Timeouts: start slide-out after slide-in + hold.
        for (Toast t : visible) {
            if (t.dismissAt < 0 && t.holdMs > 0 && now - t.shownAt >= SLIDE_IN_MS + t.holdMs) {
                t.dismissAt = now;
            }
        }
        // Remove finished slide-outs.
        visible.removeIf(t -> t.dismissAt >= 0 && now - t.dismissAt >= SLIDE_OUT_MS);
        // Promote from pending while there is room.
        while (visible.size() < MAX_VISIBLE && !pending.isEmpty()) {
            Toast t = pending.remove(0);
            t.shownAt = now;
            visible.add(t);
        }
    }

    /** Snapshot of visible toasts, oldest first. */
    public synchronized List<Toast> visible() { return new ArrayList<>(visible); }

    public synchronized int pendingCount() { return pending.size(); }

    public synchronized int totalCount() { return visible.size() + pending.size(); }

    /** User accepted/declined: starts slide-out. Returns false if the toast is unknown or already dismissing. */
    public synchronized boolean dismiss(long toastId, boolean accepted, long now) {
        for (Toast t : visible) {
            if (t.id == toastId) {
                if (t.dismissAt >= 0) return false;
                t.dismissAt = now;
                t.accepted = accepted;
                return true;
            }
        }
        // Not visible yet: drop it from the waiting list outright.
        return pending.removeIf(t -> t.id == toastId);
    }

    /** Removes any toast for a challenge (e.g. the server says it expired or was cancelled). */
    public synchronized boolean dismissChallenge(String challengeId, long now) {
        boolean any = false;
        for (Toast t : visible) {
            if (challengeId != null && challengeId.equals(t.challengeId) && t.dismissAt < 0) {
                t.dismissAt = now;
                any = true;
            }
        }
        any |= pending.removeIf(t -> challengeId != null && challengeId.equals(t.challengeId));
        return any;
    }

    public synchronized void clear() { pending.clear(); visible.clear(); }
}
