"""Player skin storage for the matchmaker.

Pure Python, standard library only (no Pillow), so it runs on the live server as is.
One full 64x64 skin per player, plus a separately stored 8x8 head (face + hat layer).
Every upload gets a new token; clients cache heads by name + token.

All skin writes go through SkinStore.store(): an account system can later plug in there.
"""
import os
import re
import secrets
import struct
import threading
import time
import zlib

PNG_SIG = b"\x89PNG\r\n\x1a\n"
SKIN_W = 64
SKIN_H = 64
MAX_BYTES = 65536
NAME_RE = re.compile(r"^[A-Za-z0-9_]{1,16}$")
SEP = "-"


class SkinError(ValueError):
    pass


def clean_name(name):
    """Return the lowercase player name, or raise SkinError if it is not a valid Minecraft-style name."""
    if not isinstance(name, str) or not NAME_RE.match(name):
        raise SkinError("invalid player name")
    return name.lower()


def _chunks(data):
    pos = len(PNG_SIG)
    while pos + 8 <= len(data):
        length, ctype = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if len(body) != length:
            raise SkinError("truncated PNG")
        yield ctype, body
        pos += 12 + length
        if ctype == b"IEND":
            return


def decode_png(data):
    """Decode a non-interlaced 8-bit RGB/RGBA PNG. Returns (width, height, rows of RGBA tuples)."""
    if not isinstance(data, (bytes, bytearray)) or not data.startswith(PNG_SIG):
        raise SkinError("not a PNG file")
    ihdr = None
    idat = bytearray()
    for ctype, body in _chunks(bytes(data)):
        if ctype == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", body)
        elif ctype == b"IDAT":
            idat += body
    if ihdr is None:
        raise SkinError("missing PNG header")
    w, h, depth, ctype, _comp, _filt, interlace = ihdr
    if depth != 8 or ctype not in (2, 6) or interlace != 0:
        raise SkinError("PNG must be 8-bit RGB or RGBA, not interlaced")
    if (w, h) != (SKIN_W, SKIN_H):
        raise SkinError("skin must be exactly 64x64")
    bpp = 4 if ctype == 6 else 3
    try:
        raw = zlib.decompress(bytes(idat))
    except zlib.error:
        raise SkinError("corrupt PNG data")
    stride = w * bpp
    if len(raw) != (stride + 1) * h:
        raise SkinError("corrupt PNG data")
    rows = []
    prev = bytearray(stride)
    pos = 0
    for _ in range(h):
        ft = raw[pos]
        line = bytearray(raw[pos + 1:pos + 1 + stride])
        pos += 1 + stride
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if ft == 1:
                line[i] = (line[i] + a) & 255
            elif ft == 2:
                line[i] = (line[i] + b) & 255
            elif ft == 3:
                line[i] = (line[i] + ((a + b) >> 1)) & 255
            elif ft == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 255
            elif ft != 0:
                raise SkinError("corrupt PNG data")
        prev = line
        row = []
        for x in range(w):
            o = x * bpp
            row.append((line[o], line[o + 1], line[o + 2], line[o + 3] if bpp == 4 else 255))
        rows.append(row)
    return w, h, rows


def encode_png(width, height, rows):
    """Encode rows of RGBA tuples as an 8-bit RGBA PNG."""
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for px in row:
            raw += bytes(px)

    def chunk(t, b):
        return struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b) & 0xFFFFFFFF)

    return (PNG_SIG + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def validate(data):
    """Raise SkinError unless data is an acceptable skin. Returns the decoded rows."""
    if not isinstance(data, (bytes, bytearray)) or len(data) == 0:
        raise SkinError("empty upload")
    if len(data) > MAX_BYTES:
        raise SkinError("skin file too large")
    return decode_png(data)[2]


def extract_head(rows):
    """8x8 face at (8,8) with the 8x8 hat layer at (40,8) drawn over it. Returns an 8x8 RGBA PNG."""
    out = []
    for y in range(8):
        line = []
        for x in range(8):
            fr, fg, fb, fa = rows[8 + y][8 + x]
            hr, hg, hb, ha = rows[8 + y][40 + x]
            if ha == 255:
                line.append((hr, hg, hb, 255))
            elif ha == 0:
                line.append((fr, fg, fb, 255))
            else:
                t = ha / 255.0
                line.append((int(hr * t + fr * (1 - t)), int(hg * t + fg * (1 - t)),
                             int(hb * t + fb * (1 - t)), 255))
        out.append(line)
    return encode_png(8, 8, out)


class SkinStore:
    """Files: <root>/full/<name>.png, <root>/head/<name>.png, <root>/tokens.txt (name<TAB>token)."""

    def __init__(self, root, rate_limit=5, rate_window=3600.0, clock=time.time):
        self.root = root
        self.rate_limit = rate_limit
        self.rate_window = rate_window
        self._clock = clock
        self._lock = threading.Lock()
        self._hits = {}
        os.makedirs(os.path.join(root, "full"), exist_ok=True)
        os.makedirs(os.path.join(root, "head"), exist_ok=True)
        self._tokens = self._load_tokens()

    def _tokens_path(self):
        return os.path.join(self.root, "tokens.txt")

    def _load_tokens(self):
        out = {}
        try:
            with open(self._tokens_path(), "r", encoding="utf-8") as f:
                for line in f:
                    parts = line.rstrip("\n").split("\t")
                    if len(parts) == 2 and parts[0] and parts[1]:
                        out[parts[0]] = parts[1]
        except FileNotFoundError:
            pass
        return out

    def _save_tokens(self):
        tmp = self._tokens_path() + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            for n, t in self._tokens.items():
                f.write(n + "\t" + t + "\n")
        os.replace(tmp, self._tokens_path())

    def _path(self, kind, name):
        return os.path.join(self.root, kind, name + ".png")

    def check_rate(self, ip):
        """Return True if this IP may upload now, and count the attempt. Per-IP, sliding window."""
        now = self._clock()
        with self._lock:
            hits = [t for t in self._hits.get(ip, []) if now - t < self.rate_window]
            if len(hits) >= self.rate_limit:
                self._hits[ip] = hits
                return False
            hits.append(now)
            self._hits[ip] = hits
            return True

    def store(self, name, data):
        """The single write path for skins. Validates, replaces the old skin, returns the new token."""
        key = clean_name(name)
        rows = validate(data)
        head = extract_head(rows)
        token = secrets.token_hex(4)
        with self._lock:
            for kind, blob in (("full", bytes(data)), ("head", head)):
                tmp = self._path(kind, key) + ".tmp"
                with open(tmp, "wb") as f:
                    f.write(blob)
                os.replace(tmp, self._path(kind, key))
            self._tokens[key] = token
            self._save_tokens()
        return token

    def token_of(self, name):
        try:
            return self._tokens.get(clean_name(name))
        except SkinError:
            return None

    def tokens_for(self, names, limit=64):
        """{name: token} for the names that have a skin. Invalid names are skipped. At most `limit` names."""
        out = {}
        for n in list(names)[:limit]:
            try:
                key = clean_name(n)
            except SkinError:
                continue
            if key in self._tokens:
                out[key] = self._tokens[key]
        return out

    def stored_name(self, name):
        """The '<name><SEP><token>' form used as the cache key, or None if the player has no skin."""
        key = clean_name(name)
        tok = self._tokens.get(key)
        return key + SEP + tok if tok else None

    def get(self, kind, name):
        """PNG bytes of 'full' or 'head' for a player, or None."""
        if kind not in ("full", "head"):
            return None
        try:
            key = clean_name(name)
        except SkinError:
            return None
        if key not in self._tokens:
            return None
        try:
            with open(self._path(kind, key), "rb") as f:
                return f.read()
        except FileNotFoundError:
            return None

    def reset(self, name):
        """Manual reset of one player's skin. Returns True if something was removed."""
        key = clean_name(name)
        with self._lock:
            had = key in self._tokens
            self._tokens.pop(key, None)
            for kind in ("full", "head"):
                try:
                    os.remove(self._path(kind, key))
                except FileNotFoundError:
                    pass
            if had:
                self._save_tokens()
        return had
