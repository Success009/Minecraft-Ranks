"""HTTP-level tests for presence / challenge / spectate routes.

Starts the real MatchmakingHandler on an ephemeral port. Each test gets a fresh
SocialService bound to the module's shared ``matches`` / ``queue``.
"""
import json
import os
import sys
import threading
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import matchmaking_server as ms  # noqa: E402


class HttpTestBase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ms.ThreadedHTTPServer(("127.0.0.1", 0), ms.MatchmakingHandler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        with ms.lock:
            ms.matches.clear()
            ms.queue.clear()
            ms.player_last_match.clear()
            ms.social = ms.social_routes.SocialService(
                ms.matches, ms.queue, player_last_match=ms.player_last_match,
                get_points=lambda pid: 100,
            )

    def call(self, method, path, body=None, raw=None):
        url = f"http://127.0.0.1:{self.port}{path}"
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(url, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read().decode() or "{}")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode() or "{}")

    def post(self, path, body):
        return self.call("POST", path, body)

    def get(self, path):
        return self.call("GET", path)

    def online(self, *pids):
        for p in pids:
            st, _ = self.post("/api/presence/heartbeat", {"player_id": p})
            self.assertEqual(st, 200)

    def make_match(self, a="A", b="B", kit="Crystal"):
        self.online(a, b)
        st, sent = self.post("/api/challenge/send", {"sender": a, "target": b, "kit": kit})
        self.assertEqual(st, 200, sent)
        st, res = self.post("/api/challenge/respond",
                            {"challenge_id": sent["challenge_id"], "player_id": b, "accept": True})
        self.assertEqual(st, 200, res)
        return res


class TestPresenceRoutes(HttpTestBase):
    def test_heartbeat_requires_player_id(self):
        st, body = self.post("/api/presence/heartbeat", {})
        self.assertEqual(st, 400)
        self.assertEqual(body["code"], "invalid")

    def test_invalid_json_is_400(self):
        st, _ = self.call("POST", "/api/presence/heartbeat", raw=b"{not json")
        self.assertEqual(st, 400)

    def test_online_list_excludes_requester_and_shows_status(self):
        self.online("A", "B")
        st, body = self.get("/api/players/online?player_id=A")
        self.assertEqual(st, 200)
        ids = [p["player_id"] for p in body["players"]]
        self.assertEqual(ids, ["B"])
        self.assertEqual(body["players"][0]["status"], "idle")
        self.assertTrue(body["players"][0]["challengeable"])

    def test_in_match_player_not_challengeable(self):
        self.make_match("A", "B")
        self.online("C")
        st, body = self.get("/api/players/online?player_id=C")
        by_id = {p["player_id"]: p for p in body["players"]}
        self.assertEqual(by_id["A"]["status"], "in_match")
        self.assertFalse(by_id["A"]["challengeable"])


class TestChallengeRoutes(HttpTestBase):
    def test_send_and_poll(self):
        self.online("A", "B")
        st, sent = self.post("/api/challenge/send", {"sender": "A", "target": "B", "kit": "UHC"})
        self.assertEqual(st, 200)
        st, poll = self.get("/api/challenge/poll?player_id=B")
        self.assertEqual(st, 200)
        self.assertEqual(len(poll["incoming"]), 1)
        self.assertEqual(poll["incoming"][0]["sender"], "A")
        self.assertEqual(poll["incoming"][0]["kit"], {"type": "official", "name": "UHC"})

    def test_poll_requires_player_id(self):
        st, _ = self.get("/api/challenge/poll")
        self.assertEqual(st, 400)

    def test_self_challenge_400(self):
        self.online("A")
        st, body = self.post("/api/challenge/send", {"sender": "A", "target": "A", "kit": "Crystal"})
        self.assertEqual(st, 400)
        self.assertEqual(body["code"], "self_challenge")

    def test_invalid_kit_400(self):
        self.online("A", "B")
        st, body = self.post("/api/challenge/send", {"sender": "A", "target": "B", "kit": {"type": "bogus", "name": "X"}})
        self.assertEqual(st, 400)
        self.assertEqual(body["code"], "invalid_kit")

    def test_offline_target_404(self):
        self.online("A")
        st, body = self.post("/api/challenge/send", {"sender": "A", "target": "Ghost", "kit": "Crystal"})
        self.assertEqual(st, 404)
        self.assertEqual(body["code"], "target_offline")

    def test_target_in_match_409(self):
        self.make_match("A", "B")
        self.online("C")
        st, body = self.post("/api/challenge/send", {"sender": "C", "target": "A", "kit": "Crystal"})
        self.assertEqual(st, 409)
        self.assertEqual(body["code"], "target_unavailable")

    def test_cooldown_429_with_retry_after(self):
        self.online("A", "B")
        st, sent = self.post("/api/challenge/send", {"sender": "A", "target": "B", "kit": "Crystal"})
        self.assertEqual(st, 200)
        self.post("/api/challenge/cancel", {"challenge_id": sent["challenge_id"], "player_id": "A"})
        st, body = self.post("/api/challenge/send", {"sender": "A", "target": "B", "kit": "Crystal"})
        self.assertEqual(st, 429)
        self.assertEqual(body["code"], "cooldown")
        self.assertGreater(body["retry_after"], 0)

    def test_outgoing_pending_409(self):
        self.online("A", "B", "C")
        self.post("/api/challenge/send", {"sender": "A", "target": "B", "kit": "Crystal"})
        st, body = self.post("/api/challenge/send", {"sender": "A", "target": "C", "kit": "Crystal"})
        # global send cooldown (429) fires before the pending check
        self.assertIn(st, (409, 429))

    def test_decline_notifies_sender(self):
        self.online("A", "B")
        _, sent = self.post("/api/challenge/send", {"sender": "A", "target": "B", "kit": "Mace"})
        st, res = self.post("/api/challenge/respond",
                            {"challenge_id": sent["challenge_id"], "player_id": "B", "accept": False})
        self.assertEqual((st, res["status"]), (200, "declined"))
        _, poll = self.get("/api/challenge/poll?player_id=A")
        self.assertEqual([o["result"] for o in poll["outcomes"]], ["declined"])
        _, poll2 = self.get("/api/challenge/poll?player_id=A")
        self.assertEqual(poll2["outcomes"], [])

    def test_respond_forbidden_and_unknown(self):
        self.online("A", "B", "C")
        _, sent = self.post("/api/challenge/send", {"sender": "A", "target": "B", "kit": "Crystal"})
        st, _ = self.post("/api/challenge/respond",
                          {"challenge_id": sent["challenge_id"], "player_id": "C", "accept": True})
        self.assertEqual(st, 403)
        st, _ = self.post("/api/challenge/respond",
                          {"challenge_id": "nope", "player_id": "B", "accept": True})
        self.assertEqual(st, 404)

    def test_accept_creates_match_visible_to_both(self):
        res = self.make_match("A", "B", kit="Pot")
        self.assertEqual(res["status"], "matched")
        self.assertEqual(res["kit_ref"], {"type": "official", "name": "Pot"})
        for pid in ("A", "B"):
            st, body = self.get(f"/api/queue/status?player_id={pid}")
            self.assertEqual(st, 200)
            self.assertEqual(body["status"], "matched")
            self.assertEqual(body["match_id"], res["match_id"])
            self.assertEqual(body["kit"], "Pot")
        roles = {self.get(f"/api/queue/status?player_id={p}")[1]["role"] for p in ("A", "B")}
        self.assertEqual(roles, {"host", "guest"})


class TestSpectateRoutes(HttpTestBase):
    def test_live_list_empty_then_populated(self):
        st, body = self.get("/api/matches/live")
        self.assertEqual((st, body["matches"]), (200, []))
        res = self.make_match("A", "B", kit="Sword")
        _, body = self.get("/api/matches/live")
        self.assertEqual(len(body["matches"]), 1)
        m = body["matches"][0]
        self.assertEqual(m["match_id"], res["match_id"])
        self.assertEqual(m["kit"], {"type": "official", "name": "Sword"})
        self.assertEqual((m["spectator_count"], m["max_spectators"]), (0, 5))

    def test_five_spectators_ok_sixth_rejected(self):
        res = self.make_match("A", "B")
        mid = res["match_id"]
        specs = [f"S{i}" for i in range(6)]
        self.online(*specs)
        for s in specs[:5]:
            st, body = self.post("/api/spectate/join", {"player_id": s, "match_id": mid})
            self.assertEqual(st, 200, body)
            self.assertEqual(body["role"], "spectator")
            self.assertIn("host_ip", body)
        st, body = self.post("/api/spectate/join", {"player_id": specs[5], "match_id": mid})
        self.assertEqual((st, body["code"]), (409, "match_full"))
        _, live = self.get("/api/matches/live")
        self.assertTrue(live["matches"][0]["full"])

    def test_leave_frees_slot(self):
        mid = self.make_match("A", "B")["match_id"]
        self.online("S1")
        self.post("/api/spectate/join", {"player_id": "S1", "match_id": mid})
        st, body = self.post("/api/spectate/leave", {"player_id": "S1"})
        self.assertEqual((st, body["left"]), (200, True))
        _, live = self.get("/api/matches/live")
        self.assertEqual(live["matches"][0]["spectator_count"], 0)

    def test_participant_cannot_spectate(self):
        mid = self.make_match("A", "B")["match_id"]
        st, body = self.post("/api/spectate/join", {"player_id": "A", "match_id": mid})
        self.assertEqual((st, body["code"]), (409, "participant"))

    def test_unknown_match_404(self):
        self.online("S1")
        st, body = self.post("/api/spectate/join", {"player_id": "S1", "match_id": "nope"})
        self.assertEqual((st, body["code"]), (404, "match_not_found"))

    def test_queued_player_cannot_spectate(self):
        mid = self.make_match("A", "B")["match_id"]
        self.online("Q")
        with ms.lock:
            ms.queue.append({"player_id": "Q", "last_seen": 1e18})
        st, body = self.post("/api/spectate/join", {"player_id": "Q", "match_id": mid})
        self.assertEqual((st, body["code"]), (409, "spectator_busy"))

    def test_match_end_removes_it_and_frees_spectators(self):
        mid = self.make_match("A", "B")["match_id"]
        self.online("S1")
        self.post("/api/spectate/join", {"player_id": "S1", "match_id": mid})
        with ms.lock:  # what /api/match/report does
            ms.matches.pop("A", None)
            ms.matches.pop("B", None)
        _, live = self.get("/api/matches/live")
        self.assertEqual(live["matches"], [])
        st, body = self.post("/api/spectate/leave", {"player_id": "S1"})
        self.assertEqual(body["left"], False)


class TestRosterRoute(HttpTestBase):
    def test_roster_requires_player_id(self):
        st, _ = self.get("/api/spectate/roster")
        self.assertEqual(st, 400)

    def test_roster_empty_when_not_in_match(self):
        self.online("A")
        st, body = self.get("/api/spectate/roster?player_id=A")
        self.assertEqual((st, body["spectators"], body["match_id"]), (200, [], None))

    def test_roster_lists_spectators_for_both_participants(self):
        mid = self.make_match("A", "B")["match_id"]
        self.post("/api/presence/heartbeat", {"player_id": "S1", "info": {"name": "SpecOne"}})
        self.online("S2")
        self.post("/api/spectate/join", {"player_id": "S1", "match_id": mid})
        self.post("/api/spectate/join", {"player_id": "S2", "match_id": mid})
        for p in ("A", "B"):
            st, body = self.get(f"/api/spectate/roster?player_id={p}")
            self.assertEqual(st, 200)
            self.assertEqual(body["match_id"], mid)
            self.assertEqual(body["max_spectators"], 5)
            self.assertEqual([r["name"] for r in body["spectators"]], ["SpecOne", "S2"])

    def test_roster_shrinks_after_leave(self):
        mid = self.make_match("A", "B")["match_id"]
        self.online("S1")
        self.post("/api/spectate/join", {"player_id": "S1", "match_id": mid})
        self.post("/api/spectate/leave", {"player_id": "S1"})
        _, body = self.get("/api/spectate/roster?player_id=A")
        self.assertEqual(body["spectators"], [])


if __name__ == "__main__":
    unittest.main()


class TestEndToEndScenario(HttpTestBase):
    def test_full_flow_online_challenge_accept_spectate_cleanup(self):
        # two fake clients come online and see each other
        self.online("A", "B")
        _, online = self.get("/api/players/online?player_id=A")
        self.assertIn("B", [p["player_id"] for p in online["players"]])

        # A challenges B, B accepts -> both matched with the challenge kit
        st, sent = self.post("/api/challenge/send", {"sender": "A", "target": "B", "kit": "Mace"})
        self.assertEqual(st, 200, sent)
        st, res = self.post("/api/challenge/respond",
                            {"challenge_id": sent["challenge_id"], "player_id": "B", "accept": True})
        self.assertEqual((st, res["status"]), (200, "matched"))
        mid = res["match_id"]

        # match is live with 0/5 spectators
        _, live = self.get("/api/matches/live")
        self.assertEqual(len(live["matches"]), 1)
        self.assertEqual(live["matches"][0]["kit"], {"type": "official", "name": "Mace"})

        # five spectators join, the sixth is rejected
        specs = [f"S{i}" for i in range(6)]
        self.online(*specs)
        for s in specs[:5]:
            st, body = self.post("/api/spectate/join", {"player_id": s, "match_id": mid})
            self.assertEqual((st, body["role"]), (200, "spectator"))
        st, body = self.post("/api/spectate/join", {"player_id": specs[5], "match_id": mid})
        self.assertEqual((st, body["code"]), (409, "match_full"))

        # host roster lists exactly the five spectators
        host = next(p for p in ("A", "B")
                    if self.get(f"/api/queue/status?player_id={p}")[1]["role"] == "host")
        st, roster = self.get(f"/api/spectate/roster?player_id={host}")
        self.assertEqual(st, 200, roster)
        self.assertEqual(len(roster["spectators"]), 5)

        # match ends (what /api/match/report does) -> live list and spectators clean up
        with ms.lock:
            ms.matches.pop("A", None)
            ms.matches.pop("B", None)
        _, live = self.get("/api/matches/live")
        self.assertEqual(live["matches"], [])
        st, body = self.post("/api/spectate/leave", {"player_id": "S0"})
        self.assertEqual(body["left"], False)
