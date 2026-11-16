"""Live match registry and spectator management (pure logic, no HTTP)."""
import time

MAX_SPECTATORS = 5

ERR_NOT_FOUND = "match_not_found"
ERR_FULL = "match_full"
ERR_PARTICIPANT = "participant"
ERR_BUSY = "spectator_busy"
ERR_ALREADY = "already_spectating"
ERR_INVALID = "invalid"


class LiveMatchRegistry:
    def __init__(self, max_spectators=MAX_SPECTATORS, clock=time.time):
        self._max = max_spectators
        self._clock = clock
        self._matches = {}       # match_id -> dict
        self._spectating = {}    # spectator -> match_id

    @property
    def max_spectators(self):
        return self._max

    def register(self, match_id, host_id, guest_id, host_ip, kit):
        if not match_id or not host_id or not guest_id:
            return False
        self._matches[match_id] = {
            "match_id": match_id, "host": host_id, "guest": guest_id,
            "host_ip": host_ip, "kit": dict(kit) if isinstance(kit, dict) else {"type": "official", "name": str(kit)},
            "spectators": [], "started_at": self._clock(),
        }
        return True

    def unregister(self, match_id):
        """End a match. Returns the list of spectators that were removed."""
        m = self._matches.pop(match_id, None)
        if not m:
            return []
        for s in m["spectators"]:
            self._spectating.pop(s, None)
        return list(m["spectators"])

    def unregister_player(self, player_id):
        """End any match the player participates in. Returns removed match ids."""
        ended = [mid for mid, m in self._matches.items() if player_id in (m["host"], m["guest"])]
        for mid in ended:
            self.unregister(mid)
        return ended

    def is_in_match(self, player_id):
        return any(player_id in (m["host"], m["guest"]) for m in self._matches.values())

    def list_live(self):
        out = []
        for m in self._matches.values():
            out.append({
                "match_id": m["match_id"], "host": m["host"], "guest": m["guest"],
                "kit": dict(m["kit"]),
                "spectator_count": len(m["spectators"]),
                "max_spectators": self._max,
                "full": len(m["spectators"]) >= self._max,
                "started_at": m["started_at"],
            })
        out.sort(key=lambda r: r["started_at"])
        return out

    def get_spectators(self, match_id):
        m = self._matches.get(match_id)
        return list(m["spectators"]) if m else []

    def join(self, match_id, spectator, spectator_busy=False):
        """Returns (match_info, error). spectator_busy is True if queued / in a match."""
        if not spectator:
            return None, ERR_INVALID
        m = self._matches.get(match_id)
        if m is None:
            return None, ERR_NOT_FOUND
        if spectator in (m["host"], m["guest"]) or self.is_in_match(spectator):
            return None, ERR_PARTICIPANT
        if spectator_busy:
            return None, ERR_BUSY
        if spectator in self._spectating:
            return None, ERR_ALREADY
        if len(m["spectators"]) >= self._max:
            return None, ERR_FULL
        m["spectators"].append(spectator)
        self._spectating[spectator] = match_id
        return {
            "match_id": match_id, "role": "spectator", "host_ip": m["host_ip"],
            "kit": dict(m["kit"]), "host": m["host"], "guest": m["guest"],
        }, None

    def leave(self, spectator):
        mid = self._spectating.pop(spectator, None)
        if mid and mid in self._matches:
            try:
                self._matches[mid]["spectators"].remove(spectator)
            except ValueError:
                pass
            return True
        return False

    def spectating_match(self, spectator):
        return self._spectating.get(spectator)

    def match_ids(self):
        """Set of currently registered match ids."""
        return set(self._matches)

    def spectator_ids(self):
        """List of players currently spectating any match."""
        return list(self._spectating)
