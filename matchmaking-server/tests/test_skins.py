"""Tests for skins.py: PNG validation, head extraction, tokens, storage, rate limit and reset."""
import os
import shutil
import struct
import sys
import tempfile
import unittest
import zlib

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import skins  # noqa: E402


def make_rows(fn=None):
    fn = fn or (lambda x, y: (x * 4 % 256, y * 4 % 256, 100, 255))
    return [[fn(x, y) for x in range(64)] for y in range(64)]


def make_png(rows=None):
    return skins.encode_png(64, 64, rows or make_rows())


def raw_png(w, h, ctype, depth=8, interlace=0, idat=None):
    def chunk(t, b):
        return struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b) & 0xFFFFFFFF)
    bpp = 4 if ctype == 6 else 3
    if idat is None:
        idat = zlib.compress((b"\x00" + b"\x00" * (w * bpp)) * h)
    return (skins.PNG_SIG + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, depth, ctype, 0, 0, interlace))
            + chunk(b"IDAT", idat) + chunk(b"IEND", b""))


class ValidateTests(unittest.TestCase):
    def test_valid_roundtrip(self):
        rows = make_rows()
        self.assertEqual(skins.validate(make_png(rows)), rows)

    def test_rgb_png_accepted(self):
        data = raw_png(64, 64, 2)
        rows = skins.validate(data)
        self.assertEqual(rows[0][0], (0, 0, 0, 255))

    def test_bad_signature(self):
        with self.assertRaises(skins.SkinError):
            skins.validate(b"not a png at all")

    def test_empty_rejected(self):
        with self.assertRaises(skins.SkinError):
            skins.validate(b"")

    def test_wrong_size(self):
        with self.assertRaises(skins.SkinError):
            skins.validate(raw_png(32, 32, 6))

    def test_too_big(self):
        with self.assertRaises(skins.SkinError):
            skins.validate(make_png() + b"\x00" * skins.MAX_BYTES)

    def test_corrupt_data(self):
        with self.assertRaises(skins.SkinError):
            skins.validate(raw_png(64, 64, 6, idat=b"garbage"))

    def test_truncated(self):
        with self.assertRaises(skins.SkinError):
            skins.validate(make_png()[:60])

    def test_unsupported_formats(self):
        for data in (raw_png(64, 64, 3), raw_png(64, 64, 6, depth=16), raw_png(64, 64, 6, interlace=1)):
            with self.assertRaises(skins.SkinError):
                skins.validate(data)


class HeadTests(unittest.TestCase):
    def head_pixels(self, rows):
        png = skins.extract_head(rows)
        # decode manually: 8x8 RGBA, filter 0
        pos = len(skins.PNG_SIG)
        idat = b""
        while pos < len(png):
            ln, t = struct.unpack(">I4s", png[pos:pos + 8])
            if t == b"IDAT":
                idat += png[pos + 8:pos + 8 + ln]
            pos += 12 + ln
        raw = zlib.decompress(idat)
        px = {}
        for y in range(8):
            for x in range(8):
                o = y * 33 + 1 + x * 4
                px[(x, y)] = tuple(raw[o:o + 4])
        return px

    def test_head_is_8x8_png(self):
        png = skins.extract_head(make_rows())
        self.assertTrue(png.startswith(skins.PNG_SIG))
        _, ihdr = next(skins._chunks(png))
        self.assertEqual(struct.unpack(">II", ihdr[:8]), (8, 8))

    def test_face_pixels_used(self):
        rows = make_rows(lambda x, y: (10, 20, 30, 255) if (8 <= x < 16 and 8 <= y < 16) else (0, 0, 0, 0))
        px = self.head_pixels(rows)
        self.assertEqual(px[(0, 0)], (10, 20, 30, 255))
        self.assertEqual(px[(7, 7)], (10, 20, 30, 255))

    def test_solid_hat_wins(self):
        def fn(x, y):
            if 8 <= x < 16 and 8 <= y < 16:
                return (10, 20, 30, 255)
            if 40 <= x < 48 and 8 <= y < 16:
                return (200, 100, 50, 255) if (x, y) == (41, 9) else (0, 0, 0, 0)
            return (0, 0, 0, 0)
        px = self.head_pixels(make_rows(fn))
        self.assertEqual(px[(1, 1)], (200, 100, 50, 255))
        self.assertEqual(px[(2, 2)], (10, 20, 30, 255))

    def test_transparent_hat_shows_face_opaque(self):
        def fn(x, y):
            if 8 <= x < 16 and 8 <= y < 16:
                return (10, 20, 30, 0)
            return (0, 0, 0, 0)
        px = self.head_pixels(make_rows(fn))
        self.assertEqual(px[(3, 3)], (10, 20, 30, 255))


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.now = [1000.0]
        self.store = skins.SkinStore(self.dir, rate_limit=3, rate_window=60.0, clock=lambda: self.now[0])

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_store_and_get(self):
        tok = self.store.store("Steve_1", make_png())
        self.assertEqual(len(tok), 8)
        self.assertEqual(self.store.token_of("steve_1"), tok)
        self.assertEqual(self.store.stored_name("STEVE_1"), "steve_1" + skins.SEP + tok)
        self.assertTrue(self.store.get("full", "Steve_1").startswith(skins.PNG_SIG))
        self.assertTrue(self.store.get("head", "steve_1").startswith(skins.PNG_SIG))

    def test_reupload_replaces_and_changes_token(self):
        t1 = self.store.store("alex", make_png())
        other = make_png(make_rows(lambda x, y: (1, 2, 3, 255)))
        t2 = self.store.store("alex", other)
        self.assertNotEqual(t1, t2)
        self.assertEqual(self.store.get("full", "alex"), other)
        self.assertEqual(len(os.listdir(os.path.join(self.dir, "full"))), 1)

    def test_invalid_names_rejected(self):
        for bad in ("../evil", "a/b", "", "x" * 17, "sp ace", None, 5):
            with self.assertRaises(skins.SkinError):
                self.store.store(bad, make_png())
        self.assertEqual(os.listdir(os.path.join(self.dir, "full")), [])

    def test_bad_png_not_stored(self):
        with self.assertRaises(skins.SkinError):
            self.store.store("steve", b"nope")
        self.assertIsNone(self.store.token_of("steve"))

    def test_unknown_player_and_kind(self):
        self.assertIsNone(self.store.get("full", "nobody"))
        self.store.store("steve", make_png())
        self.assertIsNone(self.store.get("other", "steve"))
        self.assertIsNone(self.store.get("full", "../steve"))
        self.assertIsNone(self.store.stored_name("nobody"))

    def test_tokens_for_batch(self):
        t = self.store.store("a1", make_png())
        out = self.store.tokens_for(["A1", "nobody", "../bad", "a1"])
        self.assertEqual(out, {"a1": t})

    def test_tokens_for_limit(self):
        for i in range(5):
            self.store.store("p%d" % i, make_png())
        out = self.store.tokens_for(["p%d" % i for i in range(5)], limit=2)
        self.assertEqual(len(out), 2)

    def test_tokens_persist_across_instances(self):
        t = self.store.store("steve", make_png())
        again = skins.SkinStore(self.dir)
        self.assertEqual(again.token_of("steve"), t)

    def test_rate_limit_sliding_window(self):
        for _ in range(3):
            self.assertTrue(self.store.check_rate("1.2.3.4"))
        self.assertFalse(self.store.check_rate("1.2.3.4"))
        self.assertTrue(self.store.check_rate("5.6.7.8"))
        self.now[0] += 61
        self.assertTrue(self.store.check_rate("1.2.3.4"))

    def test_reset(self):
        self.store.store("steve", make_png())
        self.assertTrue(self.store.reset("Steve"))
        self.assertIsNone(self.store.token_of("steve"))
        self.assertIsNone(self.store.get("full", "steve"))
        self.assertFalse(self.store.reset("steve"))
        self.assertEqual(os.listdir(os.path.join(self.dir, "head")), [])


if __name__ == "__main__":
    unittest.main()
