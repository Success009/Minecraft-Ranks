"""Tests for kit layout sanitising and guest-layout sync through the queue."""
import json
import os
import sys
import threading
import unittest
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import matchmaking_server as ms  # noqa: E402
from layouts import sanitize_layouts  # noqa: E402


class SanitizeTests(unittest.TestCase):
    def test_valid_layout_kept_and_kit_lowercased(self):
        out = sanitize_layouts({"Crystal": {"0": 5, "1": 0}})
        self.assertEqual(out, {"crystal": {"0": 5, "1": 0}})

    def test_shared_target_slot_dropped(self):
        self.assertEqual(sanitize_layouts({"crystal": {"0": 3, "1": 3}}), {})

    def test_out_of_range_dropped(self):
        self.assertEqual(sanitize_layouts({"crystal": {"0": 36}}), {})
        self.assertEqual(sanitize_layouts({"crystal": {"40": 1}}), {})
        self.assertEqual(sanitize_layouts({"crystal": {"0": -1}}), {})

    def test_bad_types_dropped(self):
        self.assertEqual(sanitize_layouts("nope"), {})
        self.assertEqual(sanitize_layouts(None), {})
        self.assertEqual(sanitize_layouts({"crystal": {"a": 1}}), {})
        self.assertEqual(sanitize_layouts({"crystal": "x"}), {})

    def test_one_bad_kit_does_not_drop_good_one(self):
        out = sanitize_layouts({"crystal": {"0": 3, "1": 3}, "uhc": {"2": 4}})
        self.assertEqual(out, {"uhc": {"2": 4}})

    def test_empty_mapping_dropped(self):
        self.assertEqual(sanitize_layouts({"crystal": {}}), {})


class LayoutSyncTests(unittest.TestCase):
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

    def _join(self, pid, perf, layouts):
        body = {"player_id": pid, "tailscale_ip": "100.0.0.%d" % perf, "selected_kits": ["Crystal"],
                "perf_score": perf, "layouts": layouts}
        req = urllib.request.Request(
            "http://127.0.0.1:%d/api/queue/join" % self.port,
            data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.loads(r.read())

    def _poll(self, pid):
        url = "http://127.0.0.1:%d/api/queue/status?player_id=%s" % (self.port, pid)
        with urllib.request.urlopen(url, timeout=5) as r:
            return json.loads(r.read())

    def test_host_receives_guest_layouts_only(self):
        # Guest joins first with low perf; host joins second with higher perf and becomes host.
        self._join("guest", 1, {"Crystal": {"0": 7}})
        host_resp = self._join("host", 5, {"Crystal": {"1": 8}})
        self.assertEqual(host_resp["status"], "matched")
        self.assertEqual(host_resp["role"], "host")
        self.assertEqual(host_resp["opponent_layouts"], {"crystal": {"0": 7}})
        guest_poll = self._poll("guest")
        self.assertEqual(guest_poll["role"], "guest")
        self.assertIsNone(guest_poll["opponent_layouts"])

    def test_host_poll_also_has_guest_layouts(self):
        self._join("guest", 1, {"Crystal": {"0": 7}})
        self._join("host", 5, {})
        host_poll = self._poll("host")
        self.assertEqual(host_poll["opponent_layouts"], {"crystal": {"0": 7}})

    def test_guest_joining_second_still_syncs(self):
        # Host queues first; guest (lower perf) joins second and triggers the match.
        self._join("host", 5, {})
        resp = self._join("guest", 1, {"Crystal": {"2": 9}})
        self.assertEqual(resp["role"], "guest")
        host_poll = self._poll("host")
        self.assertEqual(host_poll["opponent_layouts"], {"crystal": {"2": 9}})

    def test_invalid_layout_not_forwarded(self):
        self._join("guest", 1, {"Crystal": {"0": 3, "1": 3}})
        host_resp = self._join("host", 5, {})
        self.assertEqual(host_resp["opponent_layouts"], {})


if __name__ == "__main__":
    unittest.main()
