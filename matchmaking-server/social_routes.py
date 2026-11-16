"""Route-level service for presence, challenges and spectating (no HTTP imports).

Every method returns ``(http_status, body_dict)`` so the HTTP handler stays a thin
adapter and the logic can be tested without sockets.

Thread-safety: methods do NOT lock. The caller must hold the server-wide ``lock``
while calling them, because they read and mutate the shared ``matches`` / ``queue``.

Source of truth for player status is the server's own ``matches`` and ``queue``
(never what a client reports): in_match > queued > spectating > idle > offline.
"""
import time
import uuid

import challenges as ch
import kits
from layouts import sanitize_layouts
import spectate as sp
from presence import PresenceTracker

INFO_KEYS = ("name", "tailscale_ip", "perf_score")

CHALLENGE_HTTP = {
    ch.ERR_SELF: 400, ch.ERR_INVALID: 400, ch.ERR_COOLDOWN: 429,
    ch.ERR_OUTGOING_PENDING: 409, ch.ERR_TARGET_BUSY: 409,
    ch.ERR_NOT_FOUND: 404, ch.ERR_FORBIDDEN: 403,
}
SPECTATE_HTTP = {
    sp.ERR_INVALID: 400, sp.ERR_NOT_FOUND: 404, sp.ERR_FULL: 409,
    sp.ERR_PARTICIPANT: 409, sp.ERR_BUSY: 409, sp.ERR_ALREADY: 409,
}
CHALLENGEABLE = ("idle", "queued")


def _err(status, code, message=None, **extra):
    body = {"error": message or code, "code": code}
    body.update(extra)
    return status, body


class SocialService:
    def __init__(self, matches, queue, presence=None, challenges=None, registry=None,
                 player_last_match=None, get_points=None, clock=time.time,
                 resolve_custom_kit=None):
        self.matches = matches
        self.queue = queue
        self.presence = presence or PresenceTracker(clock=clock)
        self.challenges = challenges or ch.ChallengeManager(clock=clock)
        self.registry = registry or sp.LiveMatchRegistry(clock=clock)
        self.player_last_match = player_last_match if player_last_match is not None else {}
        self.get_points = get_points or (lambda pid: 100)
        # (kit_ref) -> custom_kit payload dict or None (community kit data lives in the server)
        self.resolve_custom_kit = resolve_custom_kit or (lambda ref: None)

    # -- helpers -----------------------------------------------------
    def _queue_entry(self, pid):
        for p in self.queue:
            if p.get("player_id") == pid:
                return p
        return None

    def status_of(self, pid):
        if pid in self.matches:
            return "in_match"
        if self._queue_entry(pid) is not None:
            return "queued"
        if self.registry.spectating_match(pid):
            return "spectating"
        if self.presence.is_online(pid):
            return "idle"
        return "offline"

    def _net_info(self, pid):
        """(tailscale_ip, perf_score) from queue entry first, then heartbeat info."""
        q = self._queue_entry(pid)
        if q:
            return q.get("tailscale_ip") or "127.0.0.1", q.get("perf_score", 0)
        entry = self.presence._players.get(pid, {})
        info = entry.get("info", {})
        return info.get("tailscale_ip") or "127.0.0.1", info.get("perf_score", 0)

    def sync(self):
        """Reconcile the live-match registry with the shared ``matches`` dict."""
        current = {}
        for pid, md in self.matches.items():
            if md.get("role") != "host":
                continue
            opp = md.get("opponent_id")
            if opp not in self.matches:
                continue
            kit = md.get("kit_ref") or kits.ref_from_kit_string(md.get("kit"))
            current[md["match_id"]] = (pid, opp, md.get("host_ip"), kit)
        for mid in self.registry.match_ids() - set(current):
            self.registry.unregister(mid)
        known = self.registry.match_ids()
        for mid, (host, guest, ip, kit) in current.items():
            if mid not in known:
                self.registry.register(mid, host, guest, ip, kit)
                self.challenges.drop_player(host)
                self.challenges.drop_player(guest)

    # -- presence ----------------------------------------------------
    def heartbeat(self, data):
        pid = (data or {}).get("player_id")
        raw = (data or {}).get("info")
        info = {k: raw[k] for k in INFO_KEYS if isinstance(raw, dict) and k in raw}
        if not self.presence.heartbeat(pid, info=info):
            return _err(400, "invalid", "player_id required")
        self.sync()
        return 200, {"status": self.status_of(pid)}

    def players_online(self, requester=None):
        self.sync()
        rows = []
        for row in self.presence.list_online(exclude=requester):
            pid = row["player_id"]
            status = self.status_of(pid)
            out = {"player_id": pid, "name": row.get("name", pid), "status": status,
                   "points": self.get_points(pid),
                   "challengeable": status in CHALLENGEABLE}
            if requester:
                out["cooldown"] = round(self.challenges.cooldown_remaining(requester, pid), 1)
            rows.append(out)
        return 200, {"players": rows}

    # -- challenges --------------------------------------------------
    def _store_layouts(self, pid, raw):
        if not hasattr(self, "_layouts"):
            self._layouts = {}
        clean = sanitize_layouts(raw)
        if clean:
            self._layouts[pid] = clean
        else:
            self._layouts.pop(pid, None)

    def _layouts_of(self, pid):
        return getattr(self, "_layouts", {}).get(pid) or {}

    def challenge_send(self, data):
        data = data or {}
        sender, target = data.get("sender"), data.get("target")
        kit, kit_err = kits.normalize_kit_ref(data.get("kit"))
        if kit_err:
            return _err(400, "invalid_kit", kit_err)
        self.sync()
        if not sender or not target:
            return _err(400, "invalid", "sender and target required")
        if sender == target:
            return _err(400, ch.ERR_SELF, "cannot challenge yourself")
        if self.status_of(sender) != "idle":
            return _err(409, "sender_busy", "finish what you are doing first")
        tstat = self.status_of(target)
        if tstat == "offline":
            return _err(404, "target_offline", "player is offline")
        if tstat not in CHALLENGEABLE:
            return _err(409, "target_unavailable", "player is already playing")
        c, code, wait = self.challenges.send(sender, target, kit)
        if code:
            extra = {"retry_after": round(wait, 1)} if code == ch.ERR_COOLDOWN else {}
            return _err(CHALLENGE_HTTP.get(code, 400), code, **extra)
        self._store_layouts(sender, data.get("layouts"))
        return 200, {"challenge_id": c["id"], "kit": c["kit"],
                     "expires_in": round(c["expires_at"] - c["created_at"], 1)}

    def challenge_poll(self, player_id):
        if not player_id:
            return _err(400, "invalid", "player_id required")
        self.sync()
        incoming = [{"challenge_id": c["id"], "sender": c["sender"], "kit": c["kit"],
                     "seconds_left": round(c["seconds_left"], 1)}
                    for c in self.challenges.inbox(player_id)]
        return 200, {"incoming": incoming, "outcomes": self.challenges.outcomes(player_id)}

    def challenge_cancel(self, data):
        data = data or {}
        ok, code = self.challenges.cancel(data.get("challenge_id"), data.get("player_id"))
        if not ok:
            return _err(CHALLENGE_HTTP.get(code, 400), code)
        return 200, {"status": "cancelled"}

    def challenge_respond(self, data):
        data = data or {}
        cid, pid, accept = data.get("challenge_id"), data.get("player_id"), bool(data.get("accept"))
        if accept:
            self._store_layouts(pid, data.get("layouts"))
        self.sync()
        if not accept:
            c, code = self.challenges.respond(cid, pid, False)
            if code:
                return _err(CHALLENGE_HTTP.get(code, 400), code)
            return 200, {"status": "declined"}
        pending = next((c for c in self.challenges.inbox(pid) if c["id"] == cid), None)
        if pending is None:
            _, code = self.challenges.respond(cid, pid, True)
            return _err(CHALLENGE_HTTP.get(code or ch.ERR_NOT_FOUND, 404), code or ch.ERR_NOT_FOUND)
        sender = pending["sender"]
        if self.status_of(sender) not in CHALLENGEABLE or self.status_of(pid) not in CHALLENGEABLE:
            self.challenges.cancel(cid, sender)
            return _err(409, "opponent_unavailable", "one of you is no longer available")
        c, code = self.challenges.respond(cid, pid, True)
        if code:
            return _err(CHALLENGE_HTTP.get(code, 400), code)
        return 200, self._create_match(c, pid)

    def _create_match(self, c, responder):
        sender, target = c["sender"], c["target"]
        s_ip, s_perf = self._net_info(sender)
        t_ip, t_perf = self._net_info(target)
        if s_perf >= t_perf:
            host, guest, host_ip = sender, target, s_ip
        else:
            host, guest, host_ip = target, sender, t_ip
        self.queue[:] = [p for p in self.queue if p.get("player_id") not in (sender, target)]
        match_id = str(uuid.uuid4())
        kit_name = kits.to_kit_string(c["kit"])
        custom_kit = self.resolve_custom_kit(c["kit"])
        for pid, role, opp in ((host, "host", guest), (guest, "guest", host)):
            self.matches[pid] = {
                "match_id": match_id, "role": role, "host_ip": host_ip,
                "opponent_id": opp, "kit": kit_name, "kit_ref": dict(c["kit"]),
                "custom_kit": custom_kit,
            }
            self.player_last_match[pid] = match_id
        self.matches[host]["opponent_layouts"] = self._layouts_of(guest)
        self.challenges.drop_player(sender)
        self.challenges.drop_player(target)
        self.sync()
        return {"status": "matched", "match_id": match_id,
                "role": "host" if responder == host else "guest",
                "host_ip": host_ip, "opponent_id": sender if responder == target else target,
                "kit": kit_name, "kit_ref": dict(c["kit"]), "custom_kit": custom_kit,
                "opponent_layouts": self.matches[host]["opponent_layouts"] if responder == host else None}

    # -- spectating --------------------------------------------------
    def matches_live(self):
        self.sync()
        return 200, {"matches": self.registry.list_live(),
                     "max_spectators": self.registry.max_spectators}

    def spectate_join(self, data):
        data = data or {}
        pid, mid = data.get("player_id"), data.get("match_id")
        self.sync()
        busy = pid in self.matches or self._queue_entry(pid) is not None
        info, code = self.registry.join(mid, pid, spectator_busy=busy)
        if code:
            return _err(SPECTATE_HTTP.get(code, 400), code)
        self.challenges.drop_player(pid)
        return 200, info

    def spectate_roster(self, player_id):
        """Spectators of the match the given participant is in (host polls this)."""
        if not player_id:
            return _err(400, "invalid", "player_id required")
        self.sync()
        md = self.matches.get(player_id)
        mid = md["match_id"] if md else None
        if mid is None or mid not in self.registry.match_ids():
            return 200, {"match_id": None, "max_spectators": self.registry.max_spectators, "spectators": []}
        rows = []
        for sid in self.registry.get_spectators(mid):
            info = self.presence._players.get(sid, {}).get("info", {})
            rows.append({"player_id": sid, "name": info.get("name", sid)})
        return 200, {"match_id": mid, "max_spectators": self.registry.max_spectators, "spectators": rows}

    def spectate_leave(self, data):
        pid = (data or {}).get("player_id")
        if not pid:
            return _err(400, "invalid", "player_id required")
        return 200, {"left": self.registry.leave(pid)}
