"""Challenge manager (pure logic, no HTTP).

Flow: sender -> send() -> target sees it in inbox(target) -> respond(accept|decline).
Sender learns the outcome via outcome(sender) (consumed on read).

Rules (see docs/PLAN_SPECTATE_AND_CHALLENGES.md A5):
- challenge expires after CHALLENGE_TTL seconds
- per sender->target cooldown of PAIR_COOLDOWN seconds after send/decline/expiry
- global SEND_COOLDOWN seconds between any two sends by one sender
- max 1 outgoing pending challenge per sender
- max MAX_INCOMING_PENDING pending incoming per target
"""
import time
import uuid

CHALLENGE_TTL = 30.0
PAIR_COOLDOWN = 30.0
SEND_COOLDOWN = 3.0
MAX_INCOMING_PENDING = 3

# Error codes (mapped to HTTP statuses by the server layer)
ERR_SELF = "self_challenge"
ERR_COOLDOWN = "cooldown"
ERR_OUTGOING_PENDING = "outgoing_pending"
ERR_TARGET_BUSY = "target_busy"
ERR_NOT_FOUND = "not_found"
ERR_FORBIDDEN = "forbidden"
ERR_INVALID = "invalid"


class ChallengeManager:
    def __init__(self, clock=time.time):
        self._clock = clock
        self._challenges = {}      # id -> dict
        self._pair_cd = {}         # (sender, target) -> ready_at
        self._send_cd = {}         # sender -> ready_at
        self._outcomes = {}        # sender -> list of outcome dicts

    # -- internals ---------------------------------------------------
    def _expire(self):
        now = self._clock()
        for cid, c in list(self._challenges.items()):
            if c["status"] == "pending" and now >= c["expires_at"]:
                c["status"] = "expired"
                self._pair_cd[(c["sender"], c["target"])] = now + PAIR_COOLDOWN
                self._push_outcome(c, "expired")
                del self._challenges[cid]

    def _push_outcome(self, c, result):
        self._outcomes.setdefault(c["sender"], []).append({
            "challenge_id": c["id"], "result": result,
            "target": c["target"], "kit": c["kit"],
        })

    def _pending_from(self, sender):
        return [c for c in self._challenges.values()
                if c["sender"] == sender and c["status"] == "pending"]

    def _pending_to(self, target):
        return [c for c in self._challenges.values()
                if c["target"] == target and c["status"] == "pending"]

    def cooldown_remaining(self, sender, target):
        now = self._clock()
        ready = max(self._pair_cd.get((sender, target), 0.0), self._send_cd.get(sender, 0.0))
        return max(0.0, ready - now)

    # -- public API --------------------------------------------------
    def send(self, sender, target, kit):
        """Returns (challenge, error_code, retry_after_seconds)."""
        self._expire()
        if not sender or not target or not isinstance(kit, dict):
            return None, ERR_INVALID, 0.0
        if sender == target:
            return None, ERR_SELF, 0.0
        wait = self.cooldown_remaining(sender, target)
        if wait > 0:
            return None, ERR_COOLDOWN, wait
        if self._pending_from(sender):
            return None, ERR_OUTGOING_PENDING, 0.0
        if len(self._pending_to(target)) >= MAX_INCOMING_PENDING:
            return None, ERR_TARGET_BUSY, 0.0
        now = self._clock()
        c = {
            "id": str(uuid.uuid4()), "sender": sender, "target": target,
            "kit": dict(kit), "status": "pending",
            "created_at": now, "expires_at": now + CHALLENGE_TTL,
        }
        self._challenges[c["id"]] = c
        self._pair_cd[(sender, target)] = now + PAIR_COOLDOWN
        self._send_cd[sender] = now + SEND_COOLDOWN
        return dict(c), None, 0.0

    def inbox(self, target):
        """Pending challenges addressed to target (oldest first)."""
        self._expire()
        rows = sorted(self._pending_to(target), key=lambda c: c["created_at"])
        now = self._clock()
        return [dict(c, seconds_left=max(0.0, c["expires_at"] - now)) for c in rows]

    def respond(self, challenge_id, responder, accept):
        """Returns (challenge, error_code). On accept, challenge is returned and removed."""
        self._expire()
        c = self._challenges.get(challenge_id)
        if c is None:
            return None, ERR_NOT_FOUND
        if c["target"] != responder:
            return None, ERR_FORBIDDEN
        del self._challenges[challenge_id]
        if accept:
            c["status"] = "accepted"
            self._push_outcome(c, "accepted")
        else:
            c["status"] = "declined"
            self._pair_cd[(c["sender"], c["target"])] = self._clock() + PAIR_COOLDOWN
            self._push_outcome(c, "declined")
        return dict(c), None

    def cancel(self, challenge_id, sender):
        self._expire()
        c = self._challenges.get(challenge_id)
        if c is None:
            return False, ERR_NOT_FOUND
        if c["sender"] != sender:
            return False, ERR_FORBIDDEN
        del self._challenges[challenge_id]
        return True, None

    def outcomes(self, sender):
        """Pop and return outcome notifications for a sender."""
        self._expire()
        return self._outcomes.pop(sender, [])

    def drop_player(self, player_id):
        """Remove all pending challenges involving a player (e.g. they entered a match)."""
        for cid, c in list(self._challenges.items()):
            if player_id in (c["sender"], c["target"]):
                del self._challenges[cid]
