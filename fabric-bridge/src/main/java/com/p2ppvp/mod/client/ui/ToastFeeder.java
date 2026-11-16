package com.p2ppvp.mod.client.ui;

import com.p2ppvp.mod.client.social.SocialModels.ChallengeInfo;
import com.p2ppvp.mod.client.social.SocialModels.KitRef;
import com.p2ppvp.mod.client.social.SocialModels.Outcome;
import com.p2ppvp.mod.client.ui.ToastQueue.Kind;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure mapping from poll results / API errors to toasts. No Minecraft imports. */
public final class ToastFeeder {
    public static final long RESULT_HOLD_MS = 4000;

    private ToastFeeder() {}

    /** Adds toasts for new incoming challenges and dismisses toasts whose challenge is gone. */
    public static void syncIncoming(ToastQueue q, List<ChallengeInfo> incoming, long now) {
        Set<String> live = new HashSet<>();
        for (ChallengeInfo c : incoming) {
            live.add(c.challengeId());
            KitRef k = c.kit();
            long hold = Math.max(1000, (long) (c.secondsLeft() * 1000));
            q.pushChallenge(c.challengeId(), c.sender(),
                k == null ? "custom" : k.typeLabel(), k == null ? "Unknown" : k.name(), hold);
        }
        for (ToastQueue.Toast t : q.visible()) {
            if (t.isChallenge() && !t.isDismissing() && !live.contains(t.challengeId)) {
                q.dismissChallenge(t.challengeId, now);
            }
        }
    }

    /** Result toasts for outcomes of challenges this player sent. */
    public static void addOutcomes(ToastQueue q, List<Outcome> outcomes) {
        for (Outcome o : outcomes) {
            KitRef k = o.kit();
            String kt = k == null ? null : k.typeLabel();
            String kn = k == null ? null : k.name();
            String who = o.target() == null ? "Player" : o.target();
            switch (o.result() == null ? "" : o.result()) {
                case "accepted" -> q.push(Kind.ACCEPTED, "Challenge accepted", who + " accepted", "Starting match...", kt, kn, RESULT_HOLD_MS);
                case "declined" -> q.push(Kind.DECLINED, "Challenge declined", who + " declined", null, kt, kn, RESULT_HOLD_MS);
                case "expired" -> q.push(Kind.EXPIRED, "Challenge expired", who + " did not respond", null, kt, kn, RESULT_HOLD_MS);
                default -> { }
            }
        }
    }

    /** Toast for a failed send/respond/spectate call. */
    public static void addError(ToastQueue q, String code, double retryAfter) {
        String c = code == null ? "" : code;
        switch (c) {
            case "cooldown" -> q.push(Kind.COOLDOWN, "Challenge on cooldown",
                "Try again in " + (int) Math.ceil(Math.max(retryAfter, 1)) + "s", null, null, null, RESULT_HOLD_MS);
            case "outgoing_pending" -> q.push(Kind.INFO, "Challenge pending", "Wait for your last challenge", null, null, null, RESULT_HOLD_MS);
            case "target_busy" -> q.push(Kind.INFO, "Player busy", "They have too many challenges", null, null, null, RESULT_HOLD_MS);
            case "self_challenge" -> q.push(Kind.INFO, "Can't challenge yourself", null, null, null, null, RESULT_HOLD_MS);
            case "opponent_unavailable" -> q.push(Kind.INFO, "Opponent unavailable", "Match could not start", null, null, null, RESULT_HOLD_MS);
            case "not_found", "match_not_found" -> q.push(Kind.INFO, "No longer available", "That match or challenge has ended", null, null, null, RESULT_HOLD_MS);
            case "match_full" -> q.push(Kind.INFO, "Match is full", "Spectator limit reached", null, null, null, RESULT_HOLD_MS);
            case "participant" -> q.push(Kind.INFO, "You are in this match", "Participants cannot spectate", null, null, null, RESULT_HOLD_MS);
            case "already_spectating" -> q.push(Kind.INFO, "Already spectating", "Leave your current match first", null, null, null, RESULT_HOLD_MS);
            case "spectator_busy" -> q.push(Kind.INFO, "You are busy", "Leave the queue or match first", null, null, null, RESULT_HOLD_MS);
            default -> q.push(Kind.INFO, "Request failed", c.isEmpty() ? null : c, null, null, null, RESULT_HOLD_MS);
        }
    }
}
