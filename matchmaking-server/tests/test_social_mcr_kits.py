"""MCR-specific tests: community/custom kit strings and custom_kit payload on accepted challenges."""
import unittest

from test_social_http import HttpTestBase, ms


class TestMcrKitStrings(HttpTestBase):
    def setUp(self):
        super().setUp()
        # Rebuild the service exactly as the production server does (real kit resolver + ELO lookup).
        with ms.lock:
            ms.social = ms.social_routes.SocialService(
                ms.matches, ms.queue, player_last_match=ms.player_last_match,
                get_points=lambda pid: ms.stats_db.get(pid, {}).get("elo", 100),
                resolve_custom_kit=ms._resolve_custom_kit,
            )
    def _accept(self, kit):
        self.online("A", "B")
        st, sent = self.post("/api/challenge/send", {"sender": "A", "target": "B", "kit": kit})
        self.assertEqual(st, 200, sent)
        st, res = self.post("/api/challenge/respond",
                            {"challenge_id": sent["challenge_id"], "player_id": "B", "accept": True})
        self.assertEqual(st, 200, res)
        return res

    def test_official_kit_is_bare_name_without_payload(self):
        res = self._accept("Mace")
        self.assertEqual(res["kit"], "Mace")
        self.assertIsNone(res["custom_kit"])
        for pid in ("A", "B"):
            self.assertEqual(ms.matches[pid]["kit"], "Mace")

    def test_community_kit_uses_prefix_and_server_payload(self):
        name = ms.community_categories[0]["name"]
        res = self._accept({"type": "community", "name": name})
        self.assertEqual(res["kit"], f"community:{name}")
        self.assertEqual(res["kit_ref"], {"type": "community", "name": name})
        self.assertEqual(res["custom_kit"], ms.community_categories[0]["kit"])
        for pid in ("A", "B"):
            self.assertEqual(ms.matches[pid]["custom_kit"], ms.community_categories[0]["kit"])

    def test_unknown_community_kit_has_no_payload(self):
        res = self._accept({"type": "community", "name": "DoesNotExist"})
        self.assertEqual(res["kit"], "community:DoesNotExist")
        self.assertIsNone(res["custom_kit"])

    def test_custom_kit_payload_from_pool(self):
        ms.custom_kit_pool["MyKit"] = {"name": "MyKit", "items": []}
        try:
            res = self._accept({"type": "custom", "name": "MyKit"})
            self.assertEqual(res["kit"], "custom:MyKit")
            self.assertEqual(res["custom_kit"], {"name": "MyKit", "items": []})
        finally:
            ms.custom_kit_pool.pop("MyKit", None)

    def test_live_list_reports_kit_type_for_queue_style_matches(self):
        with ms.lock:
            for pid, role, opp in (("A", "host", "B"), ("B", "guest", "A")):
                ms.matches[pid] = {"match_id": "m1", "role": role, "host_ip": "1.2.3.4",
                                   "opponent_id": opp, "kit": "community:SpearDrift",
                                   "custom_kit": None}
        _, live = self.get("/api/matches/live")
        self.assertEqual(live["matches"][0]["kit"], {"type": "community", "name": "SpearDrift"})

    def test_elo_is_used_for_points(self):
        ms.stats_db["EloGuy"] = {"elo": 321, "wins": 0, "losses": 0, "kits": {}}
        try:
            self.assertEqual(ms.social.get_points("EloGuy"), 321)
        finally:
            ms.stats_db.pop("EloGuy", None)


if __name__ == "__main__":
    unittest.main()
