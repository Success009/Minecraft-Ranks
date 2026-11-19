"""Accounts: register, login, sessions (pure logic, no HTTP).

Player-first rules (see docs/PLAN_ACCOUNTS_LOGIN.md):
- Register asks for username + password only. No email, no phone.
- A recovery code is returned once at register; the player may ignore it.
- Passwords: scrypt (stdlib), per-account random salt. Plain passwords are never stored or logged.
- Sessions: random token, only its SHA-256 is stored, so a leaked accounts.json holds no usable token.
- Each account already has room for later systems: status (bans), games_played / created_at
  (new-player pairing), optional email, flags.

Methods return (value, error_code). Error codes are short strings the HTTP layer maps to statuses.
Not thread-safe: the caller holds the server-wide lock.
"""
import hashlib
import hmac
import json
import os
import re
import secrets
import time

USERNAME_RE = re.compile(r"^[A-Za-z0-9_]{3,16}$")   # Minecraft name rules
PASSWORD_MIN = 6
PASSWORD_MAX = 128
SESSION_TTL = 60 * 60 * 24 * 90      # 90 days
LOGIN_MAX_FAILS = 5
LOGIN_WINDOW = 300.0                 # failures counted over 5 min
LOGIN_LOCK = 300.0                   # then locked 5 min

ERR_USERNAME = "bad_username"
ERR_PASSWORD = "bad_password"        # too short / too long
ERR_TAKEN = "name_taken"
ERR_CREDENTIALS = "wrong_credentials"
ERR_LOCKED = "too_many_attempts"
ERR_BANNED = "banned"
ERR_RECOVERY = "bad_recovery"

_SCRYPT = dict(n=2 ** 14, r=8, p=1, dklen=32)


def _hash_password(password, salt):
    return hashlib.scrypt(password.encode("utf-8"), salt=salt, **_SCRYPT).hex()


def _sha(token):
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _norm(name):
    return (name or "").strip().lower()


class AccountStore:
    def __init__(self, path=None, clock=time.time):
        self._path = path
        self._clock = clock
        self._accounts = {}     # account_id -> record
        self._by_name = {}      # lowercase username -> account_id
        self._sessions = {}     # sha256(token) -> {"account_id", "expires"}
        self._fails = {}        # (ip, name) -> [timestamps]
        self._locked = {}       # (ip, name) -> until
        self._load()

    # -- persistence ---------------------------------------------------
    def _load(self):
        if not self._path or not os.path.exists(self._path):
            return
        try:
            with open(self._path, "r", encoding="utf-8") as f:
                data = json.load(f)
            self._accounts = data.get("accounts", {})
            self._sessions = data.get("sessions", {})
            self._by_name = {_norm(a["username"]): aid for aid, a in self._accounts.items()}
        except Exception:
            self._accounts, self._sessions, self._by_name = {}, {}, {}

    def _save(self):
        if not self._path:
            return
        tmp = self._path + ".tmp"
        # 0o600: the file holds password hashes, so it must never be readable by other users.
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump({"accounts": self._accounts, "sessions": self._sessions}, f, indent=2)
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
        os.replace(tmp, self._path)   # a crash never leaves a half-written file

    # -- helpers -------------------------------------------------------
    @staticmethod
    def _public(acc):
        """What the client may see. Never hashes."""
        return {"account_id": acc["id"], "username": acc["username"],
                "created_at": acc["created_at"], "games_played": acc["games_played"],
                "has_email": bool(acc.get("email")), "has_recovery": bool(acc.get("recovery_hash"))}

    def _new_session(self, account_id):
        token = secrets.token_urlsafe(32)
        self._sessions[_sha(token)] = {"account_id": account_id,
                                       "expires": self._clock() + SESSION_TTL}
        return token

    def _check_lock(self, key):
        until = self._locked.get(key, 0.0)
        if self._clock() < until:
            return True
        self._locked.pop(key, None)
        return False

    def _record_fail(self, key):
        now = self._clock()
        recent = [t for t in self._fails.get(key, []) if now - t < LOGIN_WINDOW]
        recent.append(now)
        self._fails[key] = recent
        if len(recent) >= LOGIN_MAX_FAILS:
            self._locked[key] = now + LOGIN_LOCK
            self._fails.pop(key, None)

    @staticmethod
    def _valid_password(password):
        return isinstance(password, str) and PASSWORD_MIN <= len(password) <= PASSWORD_MAX

    # -- public API ----------------------------------------------------
    def username_taken(self, username):
        return _norm(username) in self._by_name

    def register(self, username, password):
        """Returns ({account, token, recovery_code}, None) or (None, error_code)."""
        if not isinstance(username, str) or not USERNAME_RE.match(username.strip()):
            return None, ERR_USERNAME
        if not self._valid_password(password):
            return None, ERR_PASSWORD
        username = username.strip()
        if _norm(username) in self._by_name:
            return None, ERR_TAKEN
        account_id = secrets.token_hex(8)
        salt = secrets.token_bytes(16)
        recovery = "-".join(secrets.token_hex(2).upper() for _ in range(3))
        rsalt = secrets.token_bytes(16)
        acc = {
            "id": account_id, "username": username,
            "salt": salt.hex(), "hash": _hash_password(password, salt),
            "recovery_salt": rsalt.hex(), "recovery_hash": _hash_password(recovery, rsalt),
            "created_at": self._clock(), "games_played": 0,
            "status": {"state": "active", "reason": "", "until": 0},   # bans later
            "email": None, "email_verified": False,                    # optional, later
            "premium_verified": False, "flags": [],
        }
        self._accounts[account_id] = acc
        self._by_name[_norm(username)] = account_id
        token = self._new_session(account_id)
        self._save()
        return {"account": self._public(acc), "token": token, "recovery_code": recovery}, None

    def login(self, username, password, ip="?"):
        """Returns ({account, token}, None) or (None, error_code)."""
        key = (ip, _norm(username))
        if self._check_lock(key):
            return None, ERR_LOCKED
        aid = self._by_name.get(_norm(username))
        acc = self._accounts.get(aid) if aid else None
        if acc is None or not isinstance(password, str):
            # same cost as a real check, so timing doesn't reveal which names exist
            _hash_password(password if isinstance(password, str) else "", b"\0" * 16)
            self._record_fail(key)
            return None, ERR_CREDENTIALS
        got = _hash_password(password, bytes.fromhex(acc["salt"]))
        if not hmac.compare_digest(got, acc["hash"]):
            self._record_fail(key)
            return None, ERR_CREDENTIALS
        if acc["status"].get("state") == "banned":
            return None, ERR_BANNED
        self._fails.pop(key, None)
        token = self._new_session(aid)
        self._save()
        return {"account": self._public(acc), "token": token}, None

    def verify(self, token):
        """Account record for a valid session token, else None."""
        if not isinstance(token, str) or not token:
            return None
        s = self._sessions.get(_sha(token))
        if s is None:
            return None
        if self._clock() >= s["expires"]:
            self._sessions.pop(_sha(token), None)
            return None
        acc = self._accounts.get(s["account_id"])
        if acc is None or acc["status"].get("state") == "banned":
            return None
        return acc

    def username_for(self, token):
        acc = self.verify(token)
        return acc["username"] if acc else None

    def logout(self, token):
        removed = self._sessions.pop(_sha(token), None) is not None if token else False
        if removed:
            self._save()
        return removed

    def reset_with_recovery(self, username, recovery_code, new_password, ip="?"):
        """Returns ({account, token}, None) or (None, error_code). Old sessions are revoked."""
        key = (ip, _norm(username))
        if self._check_lock(key):
            return None, ERR_LOCKED
        if not self._valid_password(new_password):
            return None, ERR_PASSWORD
        aid = self._by_name.get(_norm(username))
        acc = self._accounts.get(aid) if aid else None
        if acc is None or not acc.get("recovery_hash") or not isinstance(recovery_code, str):
            self._record_fail(key)
            return None, ERR_RECOVERY
        got = _hash_password(recovery_code.strip().upper(), bytes.fromhex(acc["recovery_salt"]))
        if not hmac.compare_digest(got, acc["recovery_hash"]):
            self._record_fail(key)
            return None, ERR_RECOVERY
        return self._set_password(acc, new_password), None

    def check_recovery(self, username, recovery_code, ip="?"):
        """Verifies the recovery code only (no password change). Returns ({"valid": True}, None) or (None, error_code)."""
        key = (ip, _norm(username))
        if self._check_lock(key):
            return None, ERR_LOCKED
        aid = self._by_name.get(_norm(username))
        acc = self._accounts.get(aid) if aid else None
        if acc is None or not acc.get("recovery_hash") or not isinstance(recovery_code, str):
            self._record_fail(key)
            return None, ERR_RECOVERY
        got = _hash_password(recovery_code.strip().upper(), bytes.fromhex(acc["recovery_salt"]))
        if not hmac.compare_digest(got, acc["recovery_hash"]):
            self._record_fail(key)
            return None, ERR_RECOVERY
        return {"valid": True}, None

    def owner_reset(self, username, new_password):
        """Fallback for the server owner (CLI). Returns True if the account exists."""
        aid = self._by_name.get(_norm(username))
        if not aid or not self._valid_password(new_password):
            return False
        self._set_password(self._accounts[aid], new_password)
        return True

    def _set_password(self, acc, new_password):
        salt = secrets.token_bytes(16)
        acc["salt"], acc["hash"] = salt.hex(), _hash_password(new_password, salt)
        for h in [h for h, s in self._sessions.items() if s["account_id"] == acc["id"]]:
            del self._sessions[h]
        token = self._new_session(acc["id"])
        self._save()
        return {"account": self._public(acc), "token": token}

    def prune_sessions(self):
        now = self._clock()
        dead = [h for h, s in self._sessions.items() if now >= s["expires"]]
        for h in dead:
            del self._sessions[h]
        if dead:
            self._save()
        return len(dead)
