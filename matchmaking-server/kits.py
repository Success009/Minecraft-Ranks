"""Kit reference helpers (pure logic, no HTTP).

A kit reference is {"type": "official"|"community"|"custom", "name": str}.
"""
import re

OFFICIAL_KITS = ("Crystal", "UHC", "Pot", "Mace", "Sword")
KIT_TYPES = ("official", "community", "custom")
MAX_KIT_NAME_LEN = 32
_NAME_RE = re.compile(r"[^A-Za-z0-9 _\-\.]")


def sanitize_kit_name(name):
    if not isinstance(name, str):
        return ""
    return _NAME_RE.sub("", name).strip()[:MAX_KIT_NAME_LEN]


def normalize_kit_ref(raw):
    """Return (kit_ref, error). kit_ref is None when invalid."""
    if raw is None:
        return {"type": "official", "name": "Crystal"}, None
    if isinstance(raw, str):
        raw = {"name": raw}
    if not isinstance(raw, dict):
        return None, "kit must be an object or string"
    name = sanitize_kit_name(raw.get("name", ""))
    if not name:
        return None, "kit name is required"
    ktype = str(raw.get("type", "")).lower()
    official = {k.lower(): k for k in OFFICIAL_KITS}
    if not ktype:
        ktype = "official" if name.lower() in official else "custom"
    if ktype not in KIT_TYPES:
        return None, "invalid kit type"
    if ktype == "official":
        if name.lower() not in official:
            return None, "unknown official kit"
        name = official[name.lower()]
    return {"type": ktype, "name": name}, None


def to_kit_string(ref):
    """MCR kit string: official -> 'Crystal', else 'community:<name>' / 'custom:<name>'."""
    if ref["type"] == "official":
        return ref["name"]
    return f"{ref['type']}:{ref['name']}"


def ref_from_kit_string(kit):
    """Inverse of to_kit_string; tolerant of unknown values (e.g. Tournament, Random)."""
    kit = str(kit or "Crystal")
    for t in ("community", "custom"):
        if kit.lower().startswith(t + ":"):
            name = sanitize_kit_name(kit.split(":", 1)[1]) or "Unknown"
            return {"type": t, "name": name}
    return {"type": "official", "name": kit[:MAX_KIT_NAME_LEN]}
