package com.p2ppvp.mod.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.p2ppvp.mod.client.social.SocialModels.ChallengeInfo;
import com.p2ppvp.mod.client.social.SocialModels.KitRef;
import com.p2ppvp.mod.client.social.SocialModels.Outcome;
import com.p2ppvp.mod.client.ui.ToastQueue.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToastFeederTest {
    private static ChallengeInfo ch(String id, String sender, double left) {
        return new ChallengeInfo(id, sender, new KitRef("official", "Crystal"), left);
    }

    @Test
    void newIncomingChallengeBecomesOneToastEvenAcrossRepeatedPolls() {
        ToastQueue q = new ToastQueue();
        ToastFeeder.syncIncoming(q, List.of(ch("c1", "Alice", 30)), 0);
        ToastFeeder.syncIncoming(q, List.of(ch("c1", "Alice", 28)), 2000);
        assertEquals(1, q.totalCount());
        q.update(0);
        ToastQueue.Toast t = q.visible().get(0);
        assertEquals("Alice", t.title);
        assertEquals("Ranked", t.kitType);
        assertEquals("Crystal", t.kitName);
    }

    @Test
    void challengeToastIsDismissedWhenChallengeDisappears() {
        ToastQueue q = new ToastQueue();
        ToastFeeder.syncIncoming(q, List.of(ch("c1", "Alice", 30)), 0);
        q.update(0);
        ToastFeeder.syncIncoming(q, List.of(), 1000);
        assertTrue(q.visible().get(0).isDismissing());
    }

    @Test
    void stillLiveChallengeIsNotDismissed() {
        ToastQueue q = new ToastQueue();
        ToastFeeder.syncIncoming(q, List.of(ch("c1", "A", 30), ch("c2", "B", 30)), 0);
        q.update(0);
        ToastFeeder.syncIncoming(q, List.of(ch("c2", "B", 25)), 1000);
        assertTrue(q.visible().get(0).isDismissing());
        assertFalse(q.visible().get(1).isDismissing());
    }

    @Test
    void holdIsAtLeastOneSecond() {
        ToastQueue q = new ToastQueue();
        ToastFeeder.syncIncoming(q, List.of(ch("c1", "A", 0.1)), 0);
        q.update(0);
        assertEquals(1000, q.visible().get(0).holdMs);
    }

    @Test
    void outcomesMapToDistinctKindsAndUnknownIsIgnored() {
        ToastQueue q = new ToastQueue();
        KitRef k = new KitRef("official", "Pot");
        ToastFeeder.addOutcomes(q, List.of(
            new Outcome("a", "accepted", "Bob", k),
            new Outcome("b", "declined", "Bob", k),
            new Outcome("c", "expired", "Bob", k),
            new Outcome("d", "weird", "Bob", k)));
        q.update(0);
        List<ToastQueue.Toast> v = q.visible();
        assertEquals(3, q.totalCount());
        assertEquals(Kind.ACCEPTED, v.get(0).kind);
        assertEquals(Kind.DECLINED, v.get(1).kind);
        assertEquals(Kind.EXPIRED, v.get(2).kind);
    }

    @Test
    void cooldownErrorShowsSecondsRoundedUp() {
        ToastQueue q = new ToastQueue();
        ToastFeeder.addError(q, "cooldown", 12.2);
        q.update(0);
        ToastQueue.Toast t = q.visible().get(0);
        assertEquals(Kind.COOLDOWN, t.kind);
        assertEquals("Try again in 13s", t.line1);
    }

    @Test
    void realSpectateErrorCodesGetSpecificToasts() {
        for (String code : new String[] {"match_full", "spectator_busy", "match_not_found", "participant", "already_spectating"}) {
            ToastQueue q = new ToastQueue();
            ToastFeeder.addError(q, code, 0);
            q.update(0);
            assertNotEquals("Request failed", q.visible().get(0).title, code);
        }
    }

    @Test
    void unknownAndNullCodesFallBackToGenericToast() {
        ToastQueue q = new ToastQueue();
        ToastFeeder.addError(q, null, 0);
        ToastFeeder.addError(q, "zzz", 0);
        q.update(0);
        assertEquals("Request failed", q.visible().get(0).title);
        assertEquals("zzz", q.visible().get(1).line1);
    }
}
