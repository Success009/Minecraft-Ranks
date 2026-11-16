package com.p2ppvp.mod.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.p2ppvp.mod.client.ui.ToastQueue.Kind;
import com.p2ppvp.mod.client.ui.ToastQueue.Toast;
import org.junit.jupiter.api.Test;

class ToastQueueTest {

    @Test
    void easingEndpointsAndBounds() {
        assertEquals(0.0, ToastQueue.easeOut(0), 1e-9);
        assertEquals(1.0, ToastQueue.easeOut(1), 1e-9);
        assertEquals(0.0, ToastQueue.easeIn(0), 1e-9);
        assertEquals(1.0, ToastQueue.easeIn(1), 1e-9);
        assertEquals(0.0, ToastQueue.easeOut(-3), 1e-9);
        assertEquals(1.0, ToastQueue.easeOut(7), 1e-9);
        assertTrue(ToastQueue.easeOut(0.5) > 0.5, "ease-out is front-loaded");
        assertTrue(ToastQueue.easeIn(0.5) < 0.5, "ease-in is back-loaded");
    }

    @Test
    void pushedToastIsWaitingUntilUpdate() {
        ToastQueue q = new ToastQueue();
        q.push(Kind.INFO, "Hi", "a", null, null, null, 3000);
        assertEquals(0, q.visible().size());
        assertEquals(1, q.pendingCount());
        q.update(1000);
        assertEquals(1, q.visible().size());
        assertEquals(0, q.pendingCount());
    }

    @Test
    void slideInProgressAndOffset() {
        ToastQueue q = new ToastQueue();
        q.push(Kind.INFO, "Hi", null, null, null, null, 3000);
        q.update(1000);
        Toast t = q.visible().get(0);
        assertEquals(0.0, t.slideProgress(1000), 1e-9);
        assertEquals(180, t.offsetX(180, 1000));
        double mid = t.slideProgress(1000 + ToastQueue.SLIDE_IN_MS / 2);
        assertTrue(mid > 0.0 && mid < 1.0);
        assertEquals(1.0, t.slideProgress(1000 + ToastQueue.SLIDE_IN_MS), 1e-9);
        assertEquals(0, t.offsetX(180, 1000 + ToastQueue.SLIDE_IN_MS));
        assertEquals(0, t.offsetX(180, 1000 + 99999), "stays shown during hold");
    }

    @Test
    void visibleCapAndPromotion() {
        ToastQueue q = new ToastQueue();
        for (int i = 0; i < 5; i++) q.push(Kind.INFO, "t" + i, null, null, null, null, 1000);
        q.update(0);
        assertEquals(ToastQueue.MAX_VISIBLE, q.visible().size());
        assertEquals(2, q.pendingCount());
        assertEquals(5, q.totalCount());
        assertEquals("t0", q.visible().get(0).title);
        assertEquals("t2", q.visible().get(2).title);
    }

    @Test
    void holdTimeoutThenSlideOutThenRemoval() {
        ToastQueue q = new ToastQueue();
        q.push(Kind.INFO, "x", null, null, null, null, 1000);
        q.update(0);
        long hide = ToastQueue.SLIDE_IN_MS + 1000;
        q.update(hide - 1);
        assertFalse(q.visible().get(0).isDismissing());
        q.update(hide);
        assertTrue(q.visible().get(0).isDismissing());
        Toast t = q.visible().get(0);
        assertEquals(1.0, t.slideProgress(hide), 1e-9);
        assertEquals(0.0, t.slideProgress(hide + ToastQueue.SLIDE_OUT_MS), 1e-9);
        q.update(hide + ToastQueue.SLIDE_OUT_MS - 1);
        assertEquals(1, q.visible().size());
        q.update(hide + ToastQueue.SLIDE_OUT_MS);
        assertEquals(0, q.visible().size());
        assertEquals(0, q.totalCount());
    }

    @Test
    void waitingToastIsPromotedWhenASlotFrees() {
        ToastQueue q = new ToastQueue();
        for (int i = 0; i < 4; i++) q.push(Kind.INFO, "t" + i, null, null, null, null, 500);
        q.update(0);
        assertEquals(1, q.pendingCount());
        long done = ToastQueue.SLIDE_IN_MS + 500 + ToastQueue.SLIDE_OUT_MS;
        q.update(ToastQueue.SLIDE_IN_MS + 500);
        q.update(done);
        assertEquals(1, q.visible().size());
        assertEquals("t3", q.visible().get(0).title);
        assertEquals(0, q.pendingCount());
    }

    @Test
    void holdMsZeroMeansNoAutoTimeout() {
        ToastQueue q = new ToastQueue();
        q.push(Kind.INFO, "sticky", null, null, null, null, 0);
        q.update(0);
        q.update(10_000_000);
        assertEquals(1, q.visible().size());
        assertFalse(q.visible().get(0).isDismissing());
        assertEquals(1.0, q.visible().get(0).holdRemaining(10_000_000), 1e-9);
    }

    @Test
    void duplicateChallengeIdsAreIgnored() {
        ToastQueue q = new ToastQueue();
        assertTrue(q.pushChallenge("c1", "Alice", "official", "Crystal", 30000) > 0);
        assertEquals(-1, q.pushChallenge("c1", "Alice", "official", "Crystal", 30000));
        q.update(0);
        assertEquals(-1, q.pushChallenge("c1", "Alice", "official", "Crystal", 30000), "also while visible");
        assertEquals(1, q.totalCount());
        assertTrue(q.pushChallenge("c2", "Bob", "custom", "My Kit", 30000) > 0);
        assertEquals(-1, q.pushChallenge(null, "x", "official", "Crystal", 1000));
    }

    @Test
    void challengeToastCarriesSenderAndKit() {
        ToastQueue q = new ToastQueue();
        q.pushChallenge("c1", "Alice", "community", "Speedrun", 30000);
        q.update(0);
        Toast t = q.visible().get(0);
        assertTrue(t.isChallenge());
        assertEquals(Kind.CHALLENGE, t.kind);
        assertEquals("c1", t.challengeId);
        assertEquals("Alice", t.title);
        assertEquals("community", t.kitType);
        assertEquals("Speedrun", t.kitName);
    }

    @Test
    void dismissVisibleRecordsChoiceAndSlidesOut() {
        ToastQueue q = new ToastQueue();
        long id = q.pushChallenge("c1", "Alice", "official", "Crystal", 30000);
        q.update(0);
        assertTrue(q.dismiss(id, true, 500));
        Toast t = q.visible().get(0);
        assertTrue(t.isDismissing());
        assertTrue(t.wasAccepted());
        assertFalse(q.dismiss(id, false, 600), "second dismiss rejected");
        assertTrue(t.wasAccepted(), "first choice kept");
        q.update(500 + ToastQueue.SLIDE_OUT_MS);
        assertEquals(0, q.totalCount());
    }

    @Test
    void dismissWaitingToastRemovesItImmediately() {
        ToastQueue q = new ToastQueue();
        for (int i = 0; i < 3; i++) q.push(Kind.INFO, "t" + i, null, null, null, null, 1000);
        long waiting = q.push(Kind.INFO, "w", null, null, null, null, 1000);
        q.update(0);
        assertEquals(1, q.pendingCount());
        assertTrue(q.dismiss(waiting, false, 10));
        assertEquals(0, q.pendingCount());
        assertFalse(q.dismiss(9999, false, 10), "unknown id");
    }

    @Test
    void dismissChallengeHandlesVisibleAndWaiting() {
        ToastQueue q = new ToastQueue();
        q.pushChallenge("c1", "A", "official", "Crystal", 30000);
        q.push(Kind.INFO, "i1", null, null, null, null, 1000);
        q.push(Kind.INFO, "i2", null, null, null, null, 1000);
        q.pushChallenge("c2", "B", "official", "Pot", 30000);
        q.update(0);
        assertEquals(1, q.pendingCount());
        assertTrue(q.dismissChallenge("c1", 100));
        assertTrue(q.visible().get(0).isDismissing());
        assertTrue(q.dismissChallenge("c2", 100));
        assertEquals(0, q.pendingCount());
        assertFalse(q.dismissChallenge("nope", 100));
        assertFalse(q.dismissChallenge(null, 100));
    }

    @Test
    void holdRemainingCountsDownFromOneToZero() {
        ToastQueue q = new ToastQueue();
        q.push(Kind.INFO, "x", null, null, null, null, 1000);
        q.update(0);
        Toast t = q.visible().get(0);
        assertEquals(1.0, t.holdRemaining(0), 1e-9);
        assertEquals(1.0, t.holdRemaining(ToastQueue.SLIDE_IN_MS), 1e-9);
        assertEquals(0.5, t.holdRemaining(ToastQueue.SLIDE_IN_MS + 500), 1e-9);
        assertEquals(0.0, t.holdRemaining(ToastQueue.SLIDE_IN_MS + 5000), 1e-9);
    }

    @Test
    void clearEmptiesEverything() {
        ToastQueue q = new ToastQueue();
        for (int i = 0; i < 5; i++) q.push(Kind.INFO, "t", null, null, null, null, 1000);
        q.update(0);
        q.clear();
        assertEquals(0, q.totalCount());
    }
}
