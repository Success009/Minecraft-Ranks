"""Kit layout sanitising for the matchmaker. Layout shape: {kit: {"defaultSlot": currentSlot}}, slots 0-35, no shared target slot."""


def sanitize_layouts(raw):
    out = {}
    if not isinstance(raw, dict) or len(raw) > 16:
        return out
    for kit, mapping in raw.items():
        if not isinstance(kit, str) or len(kit) > 32 or not isinstance(mapping, dict) or len(mapping) > 36:
            continue
        clean, used, ok = {}, set(), True
        for k, v in mapping.items():
            try:
                ks, vs = int(k), int(v)
            except (TypeError, ValueError):
                ok = False
                break
            if not (0 <= ks < 36 and 0 <= vs < 36) or vs in used:
                ok = False
                break
            used.add(vs)
            clean[str(ks)] = vs
        if ok and clean:
            out[kit.lower()] = clean
    return out
