"""Online presence tracking (pure logic, no HTTP).

Statuses: idle, queued, in_match. A player is online while its last
heartbeat is younger than PRESENCE_TTL seconds.
"""
import time

PRESENCE_TTL = 15.0
STATUS_IDLE = "idle"
STATUS_QUEUED = "queued"
STATUS_IN_MATCH = "in_match"
VALID_STATUSES = (STATUS_IDLE, STATUS_QUEUED, STATUS_IN_MATCH)


class PresenceTracker:
    def __init__(self, clock=time.time, ttl=PRESENCE_TTL):
        self._clock = clock
        self._ttl = ttl
        self._players = {}

    def heartbeat(self, player_id, status=None, info=None):
        """Record a heartbeat. Returns False for an invalid player_id/status."""
        if not isinstance(player_id, str) or not player_id.strip():
            return False
        if status is not None and status not in VALID_STATUSES:
            return False
        entry = self._players.setdefault(
            player_id, {"status": STATUS_IDLE, "info": {}, "last_seen": 0.0}
        )
        entry["last_seen"] = self._clock()
        if status is not None:
            entry["status"] = status
        if isinstance(info, dict):
            entry["info"].update(info)
        return True

    def set_status(self, player_id, status):
        if status not in VALID_STATUSES or player_id not in self._players:
            return False
        self._players[player_id]["status"] = status
        return True

    def get_status(self, player_id):
        """Return the status of an online player, or None if offline."""
        entry = self._players.get(player_id)
        if entry is None or self._clock() - entry["last_seen"] >= self._ttl:
            return None
        return entry["status"]

    def is_online(self, player_id):
        return self.get_status(player_id) is not None

    def remove(self, player_id):
        self._players.pop(player_id, None)

    def prune(self):
        now = self._clock()
        stale = [p for p, e in self._players.items() if now - e["last_seen"] >= self._ttl]
        for p in stale:
            del self._players[p]
        return len(stale)

    def list_online(self, exclude=None):
        """Online players sorted by name: [{player_id, status, **info}]."""
        self.prune()
        out = []
        for pid in sorted(self._players):
            if pid == exclude:
                continue
            entry = self._players[pid]
            row = {"player_id": pid, "status": entry["status"]}
            row.update(entry["info"])
            out.append(row)
        return out
