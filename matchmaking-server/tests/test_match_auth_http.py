"""HTTP tests for the auth gate on /api/match/report and /api/player/register.
Temp accounts file; original store and strict flag restored afterwards."""
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import matchmaking_server as ms  # noqa: E402
import accounts  # noqa: E402


class MatchAuthTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ms.ThreadedHTTPServer(("127.0.0.1", 0), ms.MatchmakingHandler)
        cls.port = cls.server.server_address[1]
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.orig_store = ms.accounts_store
        cls.orig_strict = ms.AUTH_STRICT

    @classmethod
    def tearDownClass(cls):
        ms.accounts_store = cls.orig_store
        ms.AUTH_STRICT = cls.orig_strict
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        ms.accounts_store = accounts.AccountStore(os.path.join(self.dir, "accounts.json"))
        ms.AUTH_STRICT = False
        ms._register_log.clear()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def call(self, path, body, token=None):
        req = urllib.request.Request("http://127.0.0.1:%d%s" % (self.port, path),
                                     data=json.dumps(body).encode(), method="POST")
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def make(self, name):
        s, b = self.call("/api/account/register", {"username": name, "password": "password1"})
        self.assertEqual(s, 200, b)
        return b["token"]

    def test_report_by_non_participant_is_rejected(self):
        tok = self.make("Mallory_1")
        s, b = self.call("/api/match/report",
                         {"winner": "Alice_1", "loser": "Bob_1", "kit": "crystal", "reporter": "Mallory_1"}, tok)
        self.assertEqual(s, 403, b)
        self.assertEqual(b.get("code"), "not_your_match")

    def test_report_with_someone_elses_token_is_rejected(self):
        tok = self.make("Mallory_1")
        s, b = self.call("/api/match/report",
                         {"winner": "Alice_1", "loser": "Mallory_1", "kit": "crystal", "reporter": "Alice_1"}, tok)
        self.assertEqual(s, 403, b)
        self.assertEqual(b.get("code"), "wrong_account")

    def test_report_by_participant_passes_the_gate(self):
        tok = self.make("Alice_1")
        s, b = self.call("/api/match/report",
                         {"winner": "Alice_1", "loser": "Bob_1", "kit": "custom_x", "reporter": "Alice_1"}, tok)
        self.assertEqual(s, 200, b)

    def test_strict_mode_requires_reporter(self):
        ms.AUTH_STRICT = True
        s, b = self.call("/api/match/report", {"winner": "Alice_1", "loser": "Bob_1", "kit": "crystal"})
        self.assertIn(s, (401, 403), b)

    def test_register_stats_for_registered_name_needs_token(self):
        self.make("Alice_1")
        s, b = self.call("/api/player/register", {"player_id": "Alice_1"})
        self.assertEqual(s, 401, b)
        self.assertEqual(b.get("code"), "login_required")


if __name__ == "__main__":
    unittest.main()
