import os
import sys
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

import kits
import presence
import challenges
import spectate


class FakeClock:
    def __init__(self, t=1000.0):
        self.t = t

    def __call__(self):
        return self.t

    def advance(self, s):
        self.t += s


OFFICIAL = {"type": "official", "name": "Crystal"}


class TestKits(unittest.TestCase):
    def test_default_when_none(self):
        ref, err = kits.normalize_kit_ref(None)
        self.assertIsNone(err)
        self.assertEqual(ref, {"type": "official", "name": "Crystal"})

    def test_string_official_case_insensitive(self):
        ref, err = kits.normalize_kit_ref("uhc")
        self.assertIsNone(err)
        self.assertEqual(ref, {"type": "official", "name": "UHC"})

    def test_unknown_official_rejected(self):
        ref, err = kits.normalize_kit_ref({"type": "official", "name": "Banana"})
        self.assertIsNone(ref)
        self.assertEqual(err, "unknown official kit")

    def test_custom_and_community_allowed(self):
        for t in ("custom", "community"):
            ref, err = kits.normalize_kit_ref({"type": t, "name": "My Kit 1"})
            self.assertIsNone(err)
            self.assertEqual(ref, {"type": t, "name": "My Kit 1"})

    def test_unknown_name_without_type_is_custom(self):
        ref, err = kits.normalize_kit_ref("Homebrew")
        self.assertIsNone(err)
        self.assertEqual(ref["type"], "custom")

    def test_invalid_type_and_empty_name(self):
        self.assertIsNotNone(kits.normalize_kit_ref({"type": "evil", "name": "x"})[1])
        self.assertIsNotNone(kits.normalize_kit_ref({"type": "custom", "name": "  "})[1])
        self.assertIsNotNone(kits.normalize_kit_ref(42)[1])

    def test_sanitize_strips_symbols_and_truncates(self):
        self.assertEqual(kits.sanitize_kit_name("<b>Hi</b>;"), "bHib")
        self.assertEqual(len(kits.sanitize_kit_name("a" * 100)), kits.MAX_KIT_NAME_LEN)


class TestPresence(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.p = presence.PresenceTracker(clock=self.clock)

    def test_heartbeat_and_list(self):
        self.assertTrue(self.p.heartbeat("b"))
        self.assertTrue(self.p.heartbeat("a", status="queued"))
        rows = self.p.list_online()
        self.assertEqual([r["player_id"] for r in rows], ["a", "b"])
        self.assertEqual(rows[0]["status"], "queued")
        self.assertEqual(rows[1]["status"], "idle")

    def test_exclude_self(self):
        self.p.heartbeat("a")
        self.p.heartbeat("b")
        self.assertEqual([r["player_id"] for r in self.p.list_online(exclude="a")], ["b"])

    def test_ttl_expiry(self):
        self.p.heartbeat("a")
        self.clock.advance(presence.PRESENCE_TTL - 1)
        self.assertTrue(self.p.is_online("a"))
        self.clock.advance(2)
        self.assertFalse(self.p.is_online("a"))
        self.assertEqual(self.p.list_online(), [])

    def test_invalid_inputs(self):
        self.assertFalse(self.p.heartbeat(""))
        self.assertFalse(self.p.heartbeat(None))
        self.assertFalse(self.p.heartbeat("a", status="flying"))

    def test_set_status_and_remove(self):
        self.p.heartbeat("a")
        self.assertTrue(self.p.set_status("a", "in_match"))
        self.assertEqual(self.p.get_status("a"), "in_match")
        self.assertFalse(self.p.set_status("ghost", "idle"))
        self.p.remove("a")
        self.assertIsNone(self.p.get_status("a"))

    def test_info_merged(self):
        self.p.heartbeat("a", info={"elo": 120})
        self.assertEqual(self.p.list_online()[0]["elo"], 120)


class TestChallenges(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.m = challenges.ChallengeManager(clock=self.clock)

    def test_send_and_inbox(self):
        c, err, _ = self.m.send("a", "b", OFFICIAL)
        self.assertIsNone(err)
        inbox = self.m.inbox("b")
        self.assertEqual(len(inbox), 1)
        self.assertEqual(inbox[0]["sender"], "a")
        self.assertEqual(inbox[0]["kit"], OFFICIAL)
        self.assertAlmostEqual(inbox[0]["seconds_left"], challenges.CHALLENGE_TTL)

    def test_self_challenge_rejected(self):
        self.assertEqual(self.m.send("a", "a", OFFICIAL)[1], challenges.ERR_SELF)

    def test_invalid_payload(self):
        self.assertEqual(self.m.send("a", "", OFFICIAL)[1], challenges.ERR_INVALID)
        self.assertEqual(self.m.send("a", "b", "Crystal")[1], challenges.ERR_INVALID)

    def test_only_one_outgoing_pending(self):
        self.m.send("a", "b", OFFICIAL)
        self.clock.advance(challenges.SEND_COOLDOWN + 0.1)
        self.assertEqual(self.m.send("a", "c", OFFICIAL)[1], challenges.ERR_OUTGOING_PENDING)

    def test_global_send_cooldown(self):
        c, _, _ = self.m.send("a", "b", OFFICIAL)
        self.m.cancel(c["id"], "a")
        _, err, wait = self.m.send("a", "c", OFFICIAL)
        self.assertEqual(err, challenges.ERR_COOLDOWN)
        self.assertGreater(wait, 0)
        self.clock.advance(challenges.SEND_COOLDOWN + 0.1)
        self.assertIsNone(self.m.send("a", "c", OFFICIAL)[1])

    def test_pair_cooldown_after_decline(self):
        c, _, _ = self.m.send("a", "b", OFFICIAL)
        self.m.respond(c["id"], "b", False)
        self.clock.advance(challenges.SEND_COOLDOWN + 0.1)
        _, err, wait = self.m.send("a", "b", OFFICIAL)
        self.assertEqual(err, challenges.ERR_COOLDOWN)
        self.assertGreater(wait, 20)
        self.clock.advance(challenges.PAIR_COOLDOWN)
        self.assertIsNone(self.m.send("a", "b", OFFICIAL)[1])

    def test_decline_outcome_for_sender(self):
        c, _, _ = self.m.send("a", "b", OFFICIAL)
        self.m.respond(c["id"], "b", False)
        out = self.m.outcomes("a")
        self.assertEqual(out[0]["result"], "declined")
        self.assertEqual(self.m.outcomes("a"), [])  # consumed

    def test_accept_returns_challenge_and_outcome(self):
        c, _, _ = self.m.send("a", "b", OFFICIAL)
        got, err = self.m.respond(c["id"], "b", True)
        self.assertIsNone(err)
        self.assertEqual(got["status"], "accepted")
        self.assertEqual(self.m.outcomes("a")[0]["result"], "accepted")
        self.assertEqual(self.m.inbox("b"), [])

    def test_respond_wrong_player_forbidden(self):
        c, _, _ = self.m.send("a", "b", OFFICIAL)
        self.assertEqual(self.m.respond(c["id"], "evil", True)[1], challenges.ERR_FORBIDDEN)
        self.assertEqual(len(self.m.inbox("b")), 1)

    def test_respond_unknown_not_found(self):
        self.assertEqual(self.m.respond("nope", "b", True)[1], challenges.ERR_NOT_FOUND)

    def test_expiry_notifies_sender(self):
        self.m.send("a", "b", OFFICIAL)
        self.clock.advance(challenges.CHALLENGE_TTL + 1)
        self.assertEqual(self.m.inbox("b"), [])
        self.assertEqual(self.m.outcomes("a")[0]["result"], "expired")

    def test_expired_cannot_be_accepted(self):
        c, _, _ = self.m.send("a", "b", OFFICIAL)
        self.clock.advance(challenges.CHALLENGE_TTL + 1)
        self.assertEqual(self.m.respond(c["id"], "b", True)[1], challenges.ERR_NOT_FOUND)

    def test_target_incoming_limit(self):
        for s in ("a", "b", "c"):
            self.assertIsNone(self.m.send(s, "t", OFFICIAL)[1])
        self.assertEqual(self.m.send("d", "t", OFFICIAL)[1], challenges.ERR_TARGET_BUSY)

    def test_cancel_rules(self):
        c, _, _ = self.m.send("a", "b", OFFICIAL)
        self.assertEqual(self.m.cancel(c["id"], "b"), (False, challenges.ERR_FORBIDDEN))
        self.assertEqual(self.m.cancel(c["id"], "a"), (True, None))
        self.assertEqual(self.m.inbox("b"), [])
        self.assertEqual(self.m.cancel(c["id"], "a"), (False, challenges.ERR_NOT_FOUND))

    def test_drop_player(self):
        self.m.send("a", "b", OFFICIAL)
        self.m.drop_player("b")
        self.assertEqual(self.m.inbox("b"), [])


class TestSpectate(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.r = spectate.LiveMatchRegistry(clock=self.clock)
        self.r.register("m1", "host", "guest", "100.1.1.1", OFFICIAL)

    def test_list_live(self):
        rows = self.r.list_live()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["spectator_count"], 0)
        self.assertEqual(rows[0]["max_spectators"], 5)
        self.assertFalse(rows[0]["full"])

    def test_join_returns_connection_info(self):
        info, err = self.r.join("m1", "s1")
        self.assertIsNone(err)
        self.assertEqual(info["role"], "spectator")
        self.assertEqual(info["host_ip"], "100.1.1.1")
        self.assertEqual(self.r.list_live()[0]["spectator_count"], 1)

    def test_cap_enforced(self):
        for i in range(5):
            self.assertIsNone(self.r.join("m1", "s%d" % i)[1])
        self.assertTrue(self.r.list_live()[0]["full"])
        self.assertEqual(self.r.join("m1", "s5")[1], spectate.ERR_FULL)

    def test_custom_cap(self):
        r = spectate.LiveMatchRegistry(max_spectators=3)
        r.register("m", "h", "g", "ip", OFFICIAL)
        for i in range(3):
            self.assertIsNone(r.join("m", "s%d" % i)[1])
        self.assertEqual(r.join("m", "s3")[1], spectate.ERR_FULL)

    def test_participants_cannot_spectate(self):
        self.assertEqual(self.r.join("m1", "host")[1], spectate.ERR_PARTICIPANT)
        self.assertEqual(self.r.join("m1", "guest")[1], spectate.ERR_PARTICIPANT)
        self.r.register("m2", "x", "y", "ip", OFFICIAL)
        self.assertEqual(self.r.join("m1", "x")[1], spectate.ERR_PARTICIPANT)

    def test_busy_spectator_rejected(self):
        self.assertEqual(self.r.join("m1", "s", spectator_busy=True)[1], spectate.ERR_BUSY)

    def test_unknown_match(self):
        self.assertEqual(self.r.join("nope", "s")[1], spectate.ERR_NOT_FOUND)

    def test_cannot_spectate_two_matches(self):
        self.r.register("m2", "x", "y", "ip", OFFICIAL)
        self.assertIsNone(self.r.join("m1", "s")[1])
        self.assertEqual(self.r.join("m2", "s")[1], spectate.ERR_ALREADY)

    def test_leave_frees_slot(self):
        for i in range(5):
            self.r.join("m1", "s%d" % i)
        self.assertTrue(self.r.leave("s0"))
        self.assertFalse(self.r.leave("s0"))
        self.assertIsNone(self.r.join("m1", "late")[1])

    def test_unregister_releases_spectators(self):
        self.r.join("m1", "s1")
        removed = self.r.unregister("m1")
        self.assertEqual(removed, ["s1"])
        self.assertEqual(self.r.list_live(), [])
        self.assertIsNone(self.r.spectating_match("s1"))
        self.assertEqual(self.r.unregister("m1"), [])

    def test_unregister_player(self):
        self.assertEqual(self.r.unregister_player("guest"), ["m1"])
        self.assertFalse(self.r.is_in_match("host"))

    def test_register_validation(self):
        self.assertFalse(self.r.register("", "a", "b", "ip", OFFICIAL))
        self.assertFalse(self.r.register("m", "", "b", "ip", OFFICIAL))


if __name__ == "__main__":
    unittest.main()
