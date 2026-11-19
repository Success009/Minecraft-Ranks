"""HTTP-level tests for accounts and the auth gate. Real handler on an ephemeral port,
temp accounts file, original store and strict flag restored afterwards."""
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


class AccountHttpTests(unittest.TestCase):
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

    def call(self, method, path, body=None, token=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request("http://127.0.0.1:%d%s" % (self.port, path), data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def test_exists_route_ignores_case_and_is_rate_limited(self):
        s, b = self.call("GET", "/api/account/exists?name=Steve_1")
        self.assertEqual((s, b.get("exists")), (200, False))
        self.register("Steve_1")
        s, b = self.call("GET", "/api/account/exists?name=sTEVE_1")
        self.assertEqual((s, b.get("exists")), (200, True))
        ms._exists_log.clear()
        for _ in range(60):
            self.call("GET", "/api/account/exists?name=x")
        s, b = self.call("GET", "/api/account/exists?name=x")
        self.assertEqual(s, 429)
        ms._exists_log.clear()

    def register(self, name="Steve_1", pw="password1"):
        s, b = self.call("POST", "/api/account/register", {"username": name, "password": pw})
        self.assertEqual(s, 200, b)
        return b

    def heartbeat(self, name, token=None):
        return self.call("POST", "/api/presence/heartbeat", {"player_id": name}, token)

    def test_register_login_me_logout(self):
        r = self.register()
        self.assertTrue(r["token"])
        self.assertTrue(r["recovery_code"])
        self.assertNotIn("hash", json.dumps(r))
        s, b = self.call("GET", "/api/account/me", token=r["token"])
        self.assertEqual((s, b["account"]["username"]), (200, "Steve_1"))
        s, b = self.call("POST", "/api/account/login", {"username": "steve_1", "password": "password1"})
        self.assertEqual(s, 200)
        t2 = b["token"]
        self.assertEqual(self.call("POST", "/api/account/logout", {}, t2)[0], 200)
        self.assertEqual(self.call("GET", "/api/account/me", token=t2)[0], 401)

    def test_error_codes(self):
        self.register()
        s, b = self.call("POST", "/api/account/register", {"username": "steve_1", "password": "password1"})
        self.assertEqual((s, b["code"]), (409, "name_taken"))
        s, b = self.call("POST", "/api/account/register", {"username": "ab", "password": "password1"})
        self.assertEqual((s, b["code"]), (400, "bad_username"))
        s, b = self.call("POST", "/api/account/register", {"username": "Alex", "password": "short"})
        self.assertEqual((s, b["code"]), (400, "bad_password"))
        s, b = self.call("POST", "/api/account/login", {"username": "Steve_1", "password": "wrongwrong"})
        self.assertEqual((s, b["code"]), (401, "wrong_credentials"))

    def test_registered_name_needs_token(self):
        r = self.register()
        s, b = self.heartbeat("Steve_1")
        self.assertEqual((s, b["code"]), (401, "login_required"))
        self.assertEqual(self.heartbeat("Steve_1", r["token"])[0], 200)

    def test_token_for_other_name_is_rejected(self):
        a = self.register("Alice_1")
        self.register("Bob_22")
        s, b = self.heartbeat("Bob_22", a["token"])
        self.assertEqual((s, b["code"]), (403, "wrong_account"))

    def test_bad_token_is_session_expired(self):
        self.register()
        s, b = self.heartbeat("Steve_1", "garbage")
        self.assertEqual((s, b["code"]), (401, "session_expired"))

    def test_unregistered_name_open_in_soft_mode_closed_in_strict(self):
        self.assertEqual(self.heartbeat("Nobody_9")[0], 200)
        ms.AUTH_STRICT = True
        s, b = self.heartbeat("Nobody_9")
        self.assertEqual((s, b["code"]), (401, "login_required"))

    def test_get_routes_are_gated(self):
        r = self.register()
        self.assertEqual(self.call("GET", "/api/challenge/poll?player_id=Steve_1")[0], 401)
        self.assertEqual(self.call("GET", "/api/challenge/poll?player_id=Steve_1", token=r["token"])[0], 200)

    def test_recovery_over_http(self):
        r = self.register()
        s, b = self.call("POST", "/api/account/recover",
                         {"username": "Steve_1", "recovery_code": r["recovery_code"], "new_password": "newpassword"})
        self.assertEqual(s, 200)
        self.assertEqual(self.call("GET", "/api/account/me", token=r["token"])[0], 401)  # old session revoked
        self.assertEqual(self.call("GET", "/api/account/me", token=b["token"])[0], 200)

    def test_register_rate_limit_per_ip(self):
        for i in range(10):
            self.register("User_%02d" % i)
        s, b = self.call("POST", "/api/account/register", {"username": "User_99", "password": "password1"})
        self.assertEqual((s, b["code"]), (429, "too_many_attempts"))


if __name__ == "__main__":
    unittest.main()
