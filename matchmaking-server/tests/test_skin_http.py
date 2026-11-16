"""HTTP-level tests for the skin routes. Starts the real MatchmakingHandler on an ephemeral port
and swaps the module's skin store for a temp one."""
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
import skins  # noqa: E402


def make_png(color=(10, 20, 30, 255)):
    rows = [[color for _ in range(64)] for _ in range(64)]
    return skins.encode_png(64, 64, rows)


class SkinHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ms.ThreadedHTTPServer(("127.0.0.1", 0), ms.MatchmakingHandler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.orig_store = ms.skin_store

    @classmethod
    def tearDownClass(cls):
        ms.skin_store = cls.orig_store
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        ms.skin_store = skins.SkinStore(self.dir, rate_limit=3, rate_window=3600.0)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def call(self, method, path, data=None, ctype="application/octet-stream"):
        req = urllib.request.Request("http://127.0.0.1:%d%s" % (self.port, path), data=data,
                                     method=method, headers={"Content-Type": ctype})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, r.headers.get("Content-Type"), r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.headers.get("Content-Type"), e.read()

    def upload(self, name, data=None):
        return self.call("POST", "/api/skin/upload?name=" + name, data if data is not None else make_png())

    def test_upload_and_download(self):
        status, _, body = self.upload("Steve_1")
        self.assertEqual(status, 200)
        token = json.loads(body)["token"]
        self.assertEqual(len(token), 8)
        status, ctype, blob = self.call("GET", "/api/skin/full?name=steve_1")
        self.assertEqual((status, ctype), (200, "image/png"))
        self.assertEqual(blob, make_png())
        status, ctype, blob = self.call("GET", "/api/skin/head?name=STEVE_1")
        self.assertEqual((status, ctype), (200, "image/png"))
        self.assertTrue(blob.startswith(skins.PNG_SIG))

    def test_unknown_player_404(self):
        self.assertEqual(self.call("GET", "/api/skin/head?name=nobody")[0], 404)
        self.assertEqual(self.call("GET", "/api/skin/full?name=")[0], 404)

    def test_tokens_batch(self):
        token = json.loads(self.upload("a1")[2])["token"]
        status, _, body = self.call("GET", "/api/skin/tokens?names=a1,nobody,../bad")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {"tokens": {"a1": token}})

    def test_tokens_empty(self):
        status, _, body = self.call("GET", "/api/skin/tokens")
        self.assertEqual((status, json.loads(body)), (200, {"tokens": {}}))

    def test_reupload_changes_token(self):
        t1 = json.loads(self.upload("alex")[2])["token"]
        t2 = json.loads(self.upload("alex", make_png((1, 2, 3, 255)))[2])["token"]
        self.assertNotEqual(t1, t2)

    def test_bad_png_rejected(self):
        status, _, body = self.upload("steve", b"not a png")
        self.assertEqual(status, 400)
        self.assertIn("error", json.loads(body))

    def test_invalid_name_rejected(self):
        self.assertEqual(self.upload("bad%2Fname")[0], 400)
        self.assertEqual(self.upload("")[0], 400)

    def test_empty_and_oversized_body(self):
        self.assertEqual(self.upload("steve", b"")[0], 400)
        self.assertEqual(self.upload("steve", b"\x00" * (skins.MAX_BYTES + 1))[0], 400)

    def test_rate_limit(self):
        for _ in range(3):
            self.assertEqual(self.upload("steve")[0], 200)
        self.assertEqual(self.upload("steve")[0], 429)

    def test_reset_from_localhost(self):
        self.upload("steve")
        status, _, body = self.call("POST", "/api/skin/reset", json.dumps({"name": "steve"}).encode(),
                                    "application/json")
        self.assertEqual((status, json.loads(body)["removed"]), (200, True))
        self.assertEqual(self.call("GET", "/api/skin/head?name=steve")[0], 404)

    def test_reset_invalid_name(self):
        status, _, _ = self.call("POST", "/api/skin/reset", json.dumps({"name": "../x"}).encode(),
                                 "application/json")
        self.assertEqual(status, 400)


if __name__ == "__main__":
    unittest.main()
