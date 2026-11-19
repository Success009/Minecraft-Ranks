import os, sys, tempfile, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import accounts as ac


class Clock:
    def __init__(self): self.t = 1000.0
    def __call__(self): return self.t


class AccountTests(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "accounts.json")
        self.s = ac.AccountStore(self.path, clock=self.clock)

    def reg(self, name="Steve_1", pw="password1"):
        r, e = self.s.register(name, pw)
        self.assertIsNone(e)
        return r

    def test_register_returns_token_and_recovery(self):
        r = self.reg()
        self.assertEqual(r["account"]["username"], "Steve_1")
        self.assertTrue(r["token"])
        self.assertRegex(r["recovery_code"], r"^[0-9A-F]{4}(-[0-9A-F]{4}){2}$")
        self.assertNotIn("hash", r["account"])

    def test_accounts_file_is_private(self):
        self.reg()
        self.assertTrue(os.path.exists(self.path))
        self.assertEqual(os.stat(self.path).st_mode & 0o077, 0)

    def test_bad_username_and_password(self):
        for bad in ("ab", "has space", "x" * 17, "", None, "bad-name"):
            self.assertEqual(self.s.register(bad, "password1")[1], ac.ERR_USERNAME)
        self.assertEqual(self.s.register("Steve", "short")[1], ac.ERR_PASSWORD)
        self.assertEqual(self.s.register("Steve", None)[1], ac.ERR_PASSWORD)

    def test_name_taken_is_case_insensitive(self):
        self.reg("Steve")
        self.assertEqual(self.s.register("sTEVE", "password1")[1], ac.ERR_TAKEN)

    def test_login_ok_and_wrong(self):
        self.reg()
        r, e = self.s.login("steve_1", "password1")
        self.assertIsNone(e)
        self.assertEqual(self.s.username_for(r["token"]), "Steve_1")
        self.assertEqual(self.s.login("Steve_1", "nope-nope")[1], ac.ERR_CREDENTIALS)
        self.assertEqual(self.s.login("ghost", "password1")[1], ac.ERR_CREDENTIALS)

    def test_lockout_then_recovers(self):
        self.reg()
        for _ in range(ac.LOGIN_MAX_FAILS):
            self.s.login("Steve_1", "wrongwrong", ip="1.1.1.1")
        self.assertEqual(self.s.login("Steve_1", "password1", ip="1.1.1.1")[1], ac.ERR_LOCKED)
        self.assertIsNone(self.s.login("Steve_1", "password1", ip="2.2.2.2")[1])  # other ip unaffected
        self.clock.t += ac.LOGIN_LOCK + 1
        self.assertIsNone(self.s.login("Steve_1", "password1", ip="1.1.1.1")[1])

    def test_session_expiry_and_logout(self):
        t = self.reg()["token"]
        self.assertIsNotNone(self.s.verify(t))
        self.assertTrue(self.s.logout(t))
        self.assertIsNone(self.s.verify(t))
        t2 = self.s.login("Steve_1", "password1")[0]["token"]
        self.clock.t += ac.SESSION_TTL + 1
        self.assertIsNone(self.s.verify(t2))

    def test_verify_rejects_garbage(self):
        for bad in (None, "", "abc", 5):
            self.assertIsNone(self.s.verify(bad))

    def test_recovery_reset_revokes_old_sessions(self):
        r = self.reg()
        out, e = self.s.reset_with_recovery("Steve_1", r["recovery_code"].lower(), "newpassword")
        self.assertIsNone(e)
        self.assertIsNone(self.s.verify(r["token"]))
        self.assertIsNotNone(self.s.verify(out["token"]))
        self.assertIsNone(self.s.login("Steve_1", "newpassword")[1])
        self.assertEqual(self.s.login("Steve_1", "password1")[1], ac.ERR_CREDENTIALS)
        self.assertEqual(self.s.reset_with_recovery("Steve_1", "0000-0000-0000-0000", "newpassword")[1], ac.ERR_RECOVERY)

    def test_owner_reset(self):
        self.reg()
        self.assertTrue(self.s.owner_reset("steve_1", "ownerset99"))
        self.assertIsNone(self.s.login("Steve_1", "ownerset99")[1])
        self.assertFalse(self.s.owner_reset("ghost", "ownerset99"))

    def test_banned_cannot_login_or_verify(self):
        r = self.reg()
        self.s._accounts[r["account"]["account_id"]]["status"]["state"] = "banned"
        self.assertEqual(self.s.login("Steve_1", "password1")[1], ac.ERR_BANNED)
        self.assertIsNone(self.s.verify(r["token"]))

    def test_persistence_and_no_plaintext(self):
        r = self.reg()
        s2 = ac.AccountStore(self.path, clock=self.clock)
        self.assertEqual(s2.username_for(r["token"]), "Steve_1")
        self.assertIsNone(s2.login("Steve_1", "password1")[1])
        raw = open(self.path).read()
        self.assertNotIn("password1", raw)
        self.assertNotIn(r["token"], raw)
        self.assertNotIn(r["recovery_code"], raw)

    def test_corrupt_file_starts_empty(self):
        open(self.path, "w").write("{not json")
        self.assertEqual(ac.AccountStore(self.path).username_taken("x"), False)


if __name__ == "__main__":
    unittest.main()
