"""Guest-layout sync for matches created from accepted challenges."""
import unittest

from test_social_http import HttpTestBase


class TestChallengeLayouts(HttpTestBase):
    def beat(self, pid, perf):
        st, _ = self.post("/api/presence/heartbeat",
                          {"player_id": pid, "info": {"perf_score": perf, "tailscale_ip": "100.0.0.%d" % perf}})
        self.assertEqual(st, 200)

    def challenge(self, sender, target, send_layouts, resp_layouts, accept=True):
        body = {"sender": sender, "target": target, "kit": "Crystal"}
        if send_layouts is not None:
            body["layouts"] = send_layouts
        st, sent = self.post("/api/challenge/send", body)
        self.assertEqual(st, 200, sent)
        rb = {"challenge_id": sent["challenge_id"], "player_id": target, "accept": accept}
        if resp_layouts is not None:
            rb["layouts"] = resp_layouts
        st, res = self.post("/api/challenge/respond", rb)
        self.assertEqual(st, 200, res)
        return res

    def test_sender_hosts_gets_target_layouts(self):
        self.beat("A", 9)
        self.beat("B", 1)
        res = self.challenge("A", "B", {"Crystal": {"0": 4}}, {"Crystal": {"1": 6}})
        self.assertEqual(res["role"], "guest")  # responder B is the guest
        self.assertIsNone(res["opponent_layouts"])
        st, poll = self.get("/api/queue/status?player_id=A")
        self.assertEqual(poll["role"], "host")
        self.assertEqual(poll["opponent_layouts"], {"crystal": {"1": 6}})

    def test_target_hosts_gets_sender_layouts(self):
        self.beat("A", 1)
        self.beat("B", 9)
        res = self.challenge("A", "B", {"Crystal": {"0": 4}}, {"Crystal": {"1": 6}})
        self.assertEqual(res["role"], "host")
        self.assertEqual(res["opponent_layouts"], {"crystal": {"0": 4}})

    def test_decline_stores_nothing(self):
        self.beat("A", 9)
        self.beat("B", 1)
        self.challenge("A", "B", {"Crystal": {"0": 4}}, {"Crystal": {"1": 6}}, accept=False)
        self.assertEqual(self.server_layouts("B"), {})

    def test_invalid_layouts_dropped(self):
        self.beat("A", 1)
        self.beat("B", 9)
        res = self.challenge("A", "B", {"Crystal": {"0": 3, "1": 3}}, None)
        self.assertEqual(res["opponent_layouts"], {})

    def server_layouts(self, pid):
        import matchmaking_server as ms
        return ms.social._layouts_of(pid)


if __name__ == "__main__":
    unittest.main()
