import io
import json
import os
import threading
import time
import uuid
import sys
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

# Global in-memory matchmaking states
queue = list()
matches = dict()
player_last_match = dict() # Tracks player_id -> last match_id to handle late report lookups
lock = threading.Lock()
custom_kit_pool = dict() # Stores custom_kit_name -> custom_kit_json for random pool matches

# Consensus match reports & Match History
pending_reports = dict()
resolved_matches = set()
HISTORY_FILE = "match_history.json"
match_history = list()
def load_history():
    global match_history
    try:
        import os
        if os.path.exists(HISTORY_FILE):
            with open(HISTORY_FILE, "r") as f:
                match_history = json.load(f)
    except Exception as e:
        sys.stderr.write(f"[Matchmaker Server] Error loading history: {e}\n")

def save_history():
    try:
        with open(HISTORY_FILE, "w") as f:
            json.dump(match_history, f, indent=2)
    except Exception as e:
        sys.stderr.write(f"[Matchmaker Server] Error saving history: {e}\n")

# Community / Unofficial Categories Registry
COMMUNITY_CATEGORIES_FILE = "community_categories.json"
default_community_categories = [
    {
        "id": "fist",
        "name": "Fist",
        "description": "Hand combat duel with Nether Star",
        "difficulty": "normal",
        "kit": {
            "name": "Fist",
            "category": "community",
            "difficulty": "normal",
            "items": [
                {
                    "slot": 13,
                    "id": "minecraft:nether_star",
                    "count": 1
                }
            ]
        }
    },
    {
        "id": "speardrift",
        "name": "SpearDrift",
        "description": "Lunge spear duel on peaceful difficulty with 9999 attack speed",
        "difficulty": "peaceful",
        "commands": [
            "difficulty peaceful",
            "execute as @e run attribute @s minecraft:attack_speed base set 9999",
            "attribute @a minecraft:attack_speed base set 9999"
        ],
        "kit": {
            "name": "SpearDrift",
            "category": "community",
            "difficulty": "peaceful",
            "commands": [
                "difficulty peaceful",
                "execute as @e run attribute @s minecraft:attack_speed base set 9999",
                "attribute @a minecraft:attack_speed base set 9999"
            ],
            "items": [
                {
                    "slot": 4,
                    "id": "minecraft:wooden_spear",
                    "count": 1,
                    "enchantments": [
                        {"id": "minecraft:lunge", "lvl": 3}
                    ]
                },
                {
                    "slot": 12,
                    "id": "minecraft:cobblestone",
                    "count": 64
                },
                {
                    "slot": 13,
                    "id": "minecraft:netherite_spear",
                    "count": 1,
                    "enchantments": [
                        {"id": "minecraft:lunge", "lvl": 3}
                    ]
                },
                {
                    "slot": 14,
                    "id": "minecraft:cobblestone",
                    "count": 64
                }
            ]
        }
    }
]
community_categories = list(default_community_categories)

def load_community_categories():
    global community_categories
    try:
        import os
        if os.path.exists(COMMUNITY_CATEGORIES_FILE):
            with open(COMMUNITY_CATEGORIES_FILE, "r") as f:
                saved = json.load(f)
                if isinstance(saved, list) and len(saved) > 0:
                    saved_ids = {c.get("id"): c for c in saved if isinstance(c, dict)}
                    merged = []
                    for d in default_community_categories:
                        did = d.get("id")
                        if did in saved_ids:
                            sc = saved_ids.pop(did)
                            for k, v in d.items():
                                if k not in sc or (k in ("commands", "difficulty", "description") and sc.get(k) != v):
                                    sc[k] = v
                                if k == "kit" and isinstance(v, dict):
                                    if "kit" not in sc or not isinstance(sc["kit"], dict):
                                        sc["kit"] = v
                                    else:
                                        for kk, vv in v.items():
                                            if kk not in sc["kit"] or (kk in ("commands", "difficulty") and sc["kit"].get(kk) != vv):
                                                sc["kit"][kk] = vv
                            merged.append(sc)
                        else:
                            merged.append(d)
                    for rem in saved_ids.values():
                        merged.append(rem)
                    community_categories = merged
    except Exception as e:
        sys.stderr.write(f"[Matchmaker Server] Error loading community categories: {e}\n")
def save_community_categories():
    try:
        with open(COMMUNITY_CATEGORIES_FILE, "w") as f:
            json.dump(community_categories, f, indent=2)
    except Exception as e:
        sys.stderr.write(f"[Matchmaker Server] Error saving community categories: {e}\n")

load_community_categories()

def get_global_rank(player_id, kit=None):
    players_list = []
    for pid, data in stats_db.items():
        if kit and kit != "overall" and kit != "Random":
            k_name = kit.lower()
            kit_data = data.get("kits", {}).get(k_name, {})
            elo = kit_data.get("elo", 100)
        else:
            elo = data.get("elo", 100)
        players_list.append((pid, elo))

    players_list.sort(key=lambda x: x[1], reverse=True)
    for idx, (pid, elo) in enumerate(players_list):
        if pid == player_id:
            return idx + 1
    return 1

def compute_elo_change(winner, loser, kit_played):
    # Retrieve current stats
    wk = get_player_stats(winner, kit_played)
    lk = get_player_stats(loser, kit_played)

    wk_elo = wk.get("elo", 100)
    lk_elo = lk.get("elo", 100)

    expected_wk = 1.0 / (1.0 + 10.0 ** ((lk_elo - wk_elo) / 400.0))
    expected_lk = 1.0 / (1.0 + 10.0 ** ((wk_elo - lk_elo) / 400.0))

    # Asymmetric K-factors (K_win = 32, K_loss = 20)
    change_wk = int(32 * (1.0 - expected_wk))
    change_lk = -int(20 * expected_lk)

    # Enforce minimum positive gain and minimum loss
    if change_wk < 4: change_wk = 4
    if change_lk > -2: change_lk = -2

    # Repeat for overall
    w_overall = get_player_stats(winner, "overall")
    l_overall = get_player_stats(loser, "overall")

    w_elo = w_overall.get("elo", 100)
    l_elo = l_overall.get("elo", 100)

    expected_w = 1.0 / (1.0 + 10.0 ** ((l_elo - w_elo) / 400.0))
    expected_l = 1.0 / (1.0 + 10.0 ** ((w_elo - l_elo) / 400.0))

    change_w = int(32 * (1.0 - expected_w))
    change_l = -int(20 * expected_l)

    if change_w < 4: change_w = 4
    if change_l > -2: change_l = -2

    return change_wk, change_lk, change_w, change_l

def process_elo_and_stats(winner, loser, kit_played):
    # Calculate ELO changes
    wk_change, lk_change, w_overall_change, l_overall_change = compute_elo_change(winner, loser, kit_played)

    # 1. Update kit-specific stats
    if kit_played and kit_played != "overall":
        wk = get_player_stats(winner, kit_played)
        lk = get_player_stats(loser, kit_played)
        wk["elo"] = wk.get("elo", 100) + wk_change
        wk["wins"] = wk.get("wins", 0) + 1
        lk["elo"] = lk.get("elo", 100) + lk_change
        lk["losses"] = lk.get("losses", 0) + 1

    # 2. Update overall stats
    w_overall = get_player_stats(winner, "overall")
    l_overall = get_player_stats(loser, "overall")
    w_overall["elo"] = w_overall.get("elo", 100) + w_overall_change
    w_overall["wins"] = w_overall.get("wins", 0) + 1
    l_overall["elo"] = l_overall.get("elo", 100) + l_overall_change
    l_overall["losses"] = l_overall.get("losses", 0) + 1

    return wk_change, lk_change, w_overall_change, l_overall_change

# Load history on startup
load_history()

# ELO and Stats Database
STATS_FILE = "stats.json"
stats_db = {}

def load_stats():
    global stats_db
    try:
        import os
        if os.path.exists(STATS_FILE):
            with open(STATS_FILE, "r") as f:
                stats_db = json.load(f)
        else:
            save_stats()
    except Exception as e:
        sys.stderr.write(f"[Matchmaker Server] Error loading stats: {e}\n")

def save_stats():
    try:
        with open(STATS_FILE, "w") as f:
            json.dump(stats_db, f, indent=2)
    except Exception as e:
        sys.stderr.write(f"[Matchmaker Server] Error saving stats: {e}\n")

def get_player_stats(player_id, kit=None):
    if player_id not in stats_db:
        stats_db[player_id] = {"elo": 100, "wins": 0, "losses": 0, "kits": {}}
    
    player_data = stats_db[player_id]
    
    # Ensure nested keys exist
    if "kits" not in player_data:
        player_data["kits"] = {}
        
    if kit and kit != "overall":
        kit_name = kit.lower()
        if kit_name not in player_data["kits"]:
            player_data["kits"][kit_name] = {"elo": 100, "wins": 0, "losses": 0}
        return player_data["kits"][kit_name]
    else:
        return player_data

# Load stats on startup
load_stats()

# Presence, challenges and live-match spectating (see docs/SPECTATE_AND_CHALLENGES.md)
try:
    import social_routes
    import kits as social_kits
except ImportError:
    from . import social_routes
    from . import kits as social_kits


def _resolve_custom_kit(ref):
    """Kit payload for an accepted challenge: community kits come from the server list."""
    if ref.get("type") == "community":
        name = ref.get("name", "").lower()
        for c in community_categories:
            if c.get("name", "").lower() == name or c.get("id", "").lower() == name:
                return c.get("kit")
    elif ref.get("type") == "custom":
        return custom_kit_pool.get(ref.get("name"))
    return None


social = social_routes.SocialService(
    matches, queue, player_last_match=player_last_match,
    get_points=lambda pid: stats_db.get(pid, {}).get("elo", 100),
    resolve_custom_kit=_resolve_custom_kit,
)
try:
    import skins as skin_mod
except ImportError:
    from . import skins as skin_mod

skin_store = skin_mod.SkinStore(os.path.join(os.path.dirname(os.path.abspath(__file__)), "skin_data"))

# Accounts (see docs/PLAN_ACCOUNTS_LOGIN.md). Soft mode by default: names that have an account
# need a valid token; names without one stay open. MCR_REQUIRE_AUTH=1 makes tokens mandatory.
try:
    import accounts as accounts_mod
except ImportError:
    from . import accounts as accounts_mod

accounts_store = accounts_mod.AccountStore(
    os.environ.get("MCR_ACCOUNTS_FILE")
    or os.path.join(os.path.dirname(os.path.abspath(__file__)), "accounts.json"))
AUTH_STRICT = os.environ.get("MCR_REQUIRE_AUTH") == "1"
# path -> JSON body field holding the player name the request acts as
AUTH_POST_FIELDS = {
    "/api/queue/join": "player_id", "/api/queue/leave": "player_id",
    "/api/presence/heartbeat": "player_id",
    "/api/challenge/send": "sender", "/api/challenge/respond": "player_id",
    "/api/challenge/cancel": "player_id",
    "/api/spectate/join": "player_id", "/api/spectate/leave": "player_id",
    "/api/player/register": "player_id", "/api/match/report": "reporter",
}
AUTH_POST_QUERY = {"/api/skin/upload": "name"}
AUTH_GET_QUERY = {"/api/challenge/poll": "player_id", "/api/players/online": "player_id"}
ACCOUNT_HTTP = {
    "bad_username": 400, "bad_password": 400, "name_taken": 409,
    "wrong_credentials": 401, "too_many_attempts": 429, "banned": 403, "bad_recovery": 401,
}
_register_log = {}   # ip -> [timestamps]
_recover_log = {}   # ('ip'|'dev', value) -> failed reset timestamps (24h window)
_exists_log = {}     # ip -> [timestamps] for /api/account/exists

SOCIAL_POST_ROUTES = {
    "/api/presence/heartbeat": "heartbeat",
    "/api/challenge/send": "challenge_send",
    "/api/challenge/respond": "challenge_respond",
    "/api/challenge/cancel": "challenge_cancel",
    "/api/spectate/join": "spectate_join",
    "/api/spectate/leave": "spectate_leave",
}

class MatchmakingHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Suppress standard logging to keep console clean, but print server logs
        sys.stderr.write(f"[Matchmaker Server] {format % args}\n")

    def do_GET(self):
        parsed_url = urlparse(self.path)
        path = parsed_url.path
        if path in ("/api/account/me", "/api/account/exists"):
            self._account_route(path)
            return
        if path in AUTH_GET_QUERY:
            claimed = (parse_qs(parsed_url.query).get(AUTH_GET_QUERY[path]) or [""])[0]
            if not self._gate(claimed):
                return

        if path == "/":
            # Health check endpoint
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "healthy", "service": "p2p-matchmaker"}).encode("utf-8"))
            return

        elif path == "/api/queue/status":
            query = parse_qs(parsed_url.query)
            player_ids = query.get("player_id", [ ])
            
            if not player_ids:
                self.send_error_response(400, "Missing player_id query parameter")
                return
                
            player_id = player_ids[0]
            
            import time
            with lock:
                now = time.time()
                
                # OPTIMIZATION: Prune inactive "ghost" players who have stopped polling for more than 15 seconds
                # This prevents players who crashed or disconnected from lingering in the matchmaking pool
                queue[:] = [p for p in queue if now - p.get("last_seen", now) < 15.0]

                # Update the last-seen timestamp for the currently polling player to keep them active in the queue
                for p in queue:
                    if p["player_id"] == player_id:
                        p["last_seen"] = now
                        break

                if player_id in matches:
                    match_data = matches[player_id]
                    self.send_json_response(200, {
                        "status": "matched",
                        "match_id": match_data["match_id"],
                        "role": match_data["role"],
                        "host_ip": match_data["host_ip"],
                        "opponent_id": match_data["opponent_id"],
                        "kit": match_data.get("kit", "Crystal"),
                        "custom_kit": match_data.get("custom_kit"),
                        "opponent_layouts": match_data.get("opponent_layouts")
                    })
                else:
                    is_in_queue = any(p["player_id"] == player_id for p in queue)
                    if is_in_queue:
                        self.send_json_response(200, {"status": "searching"})
                    else:
                        self.send_json_response(200, {"status": "idle"})
            return
        elif path == "/api/players/online":
            self._social(social.players_online, (parse_qs(parsed_url.query).get("player_id") or [None])[0])
            return

        elif path == "/api/challenge/poll":
            self._social(social.challenge_poll, (parse_qs(parsed_url.query).get("player_id") or [None])[0])
            return

        elif path == "/api/spectate/roster":
            self._social(social.spectate_roster, (parse_qs(parsed_url.query).get("player_id") or [None])[0])
            return

        elif path == "/api/matches/live":
            self._social(social.matches_live)
            return

        elif path in ("/api/skin/full", "/api/skin/head"):
            name = (parse_qs(parsed_url.query).get("name") or [""])[0]
            blob = skin_store.get("full" if path.endswith("full") else "head", name)
            if blob is None:
                self.send_error_response(404, "No skin")
            else:
                self.send_png_response(blob)
            return

        elif path == "/api/skin/tokens":
            names = (parse_qs(parsed_url.query).get("names") or [""])[0].split(",")
            self.send_json_response(200, {"tokens": skin_store.tokens_for([n for n in names if n])})
            return

        elif path == "/api/leaderboard":
            query = parse_qs(parsed_url.query)
            category = query.get("category", ["elo"])[0]
            kit = query.get("kit", ["overall"])[0].lower()
            
            with lock:
                leaderboard_list = []
                for pid in stats_db.keys():
                    stats = get_player_stats(pid, kit)
                    leaderboard_list.append({
                        "player_id": pid,
                        "elo": stats.get("elo", 100),
                        "wins": stats.get("wins", 0),
                        "losses": stats.get("losses", 0)
                    })
                
                if category == "wins":
                    # Sort by wins descending
                    leaderboard_list.sort(key=lambda x: x.get("wins", 0), reverse=True)
                elif category == "matches":
                    # Sort by total matches (wins + losses) descending
                    leaderboard_list.sort(key=lambda x: x.get("wins", 0) + x.get("losses", 0), reverse=True)
                else:
                    # Sort by ELO descending
                    leaderboard_list.sort(key=lambda x: x.get("elo", 100), reverse=True)
                
                top_100_leaderboard = leaderboard_list[:100]
                self.send_json_response(200, top_100_leaderboard)
            return

        elif path == "/api/match/history":
            query = parse_qs(parsed_url.query)
            player_ids = query.get("player_id", [])
            if not player_ids:
                self.send_error_response(400, "Missing player_id query parameter")
                return
            player_id = player_ids[0].lower()
            
            with lock:
                player_history = []
                for entry in match_history:
                    winner = entry.get("winner", "")
                    loser = entry.get("loser", "")
                    if winner.lower() == player_id or loser.lower() == player_id:
                        player_history.append(entry)
                
                                # Return history sorted by timestamp descending (newest first)
                player_history.sort(key=lambda x: x.get("timestamp", ""), reverse=True)
                self.send_json_response(200, player_history)
            return

        elif path in ("/api/categories/community", "/api/categories/unofficial"):
            with lock:
                self.send_json_response(200, community_categories)
            return

        self.send_error_response(404, "Endpoint not found")

    def do_POST(self):
        parsed_url = urlparse(self.path)
        path = parsed_url.path
        if path.startswith("/api/account/"):
            self._account_route(path)
            return
        if not self._gate_post(path, parsed_url):
            return

        if path in ("/api/categories/community", "/api/categories/unofficial"):
            content_length = int(self.headers.get("Content-Length", 0))
            if content_length == 0:
                self.send_error_response(400, "Empty payload")
                return
            post_data = self.rfile.read(content_length)
            try:
                cat_data = json.loads(post_data.decode("utf-8"))
            except Exception:
                self.send_error_response(400, "Malformed JSON")
                return
            
            c_name = cat_data.get("name")
            if not c_name:
                self.send_error_response(400, "name is required")
                return
            c_id = cat_data.get("id") or c_name.lower().replace(" ", "_")
            c_diff = cat_data.get("difficulty", "normal")
            c_kit = cat_data.get("kit") or {"name": c_name, "category": "community", "difficulty": c_diff, "items": []}
            if "difficulty" not in c_kit:
                c_kit["difficulty"] = c_diff
            
            new_cat = {
                "id": c_id,
                "name": c_name,
                "description": cat_data.get("description", ""),
                "difficulty": c_diff,
                "kit": c_kit
            }
            with lock:
                existing_idx = next((i for i, c in enumerate(community_categories) if c.get("id", "").lower() == c_id.lower() or c.get("name", "").lower() == c_name.lower()), -1)
                if existing_idx >= 0:
                    community_categories[existing_idx] = new_cat
                else:
                    community_categories.append(new_cat)
                save_community_categories()
            self.send_json_response(200, {"status": "ok", "category": new_cat})
            return

        elif path == "/api/player/register":
            content_length = int(self.headers.get("Content-Length", 0))
            if content_length == 0:
                self.send_error_response(400, "Empty payload")
                return

            post_data = self.rfile.read(content_length)
            try:
                data = json.loads(post_data.decode("utf-8"))
            except Exception:
                self.send_error_response(400, "Malformed JSON")
                return

            player_id = data.get("player_id")
            if not player_id:
                self.send_error_response(400, "player_id is required")
                return

            with lock:
                if player_id not in stats_db:
                    stats_db[player_id] = {"elo": 100, "wins": 0, "losses": 0, "kits": {}}
                    save_stats()
                    sys.stderr.write(f"[Matchmaker] Registered new player: {player_id} with 100 ELO\n")
                else:
                    sys.stderr.write(f"[Matchmaker] Player {player_id} already registered\n")
                
                player_data = stats_db[player_id]
                if "kits" not in player_data:
                    player_data["kits"] = {}
                
                kits_response = {}
                for k in ["crystal", "uhc", "pot", "mace", "sword"]:
                    if k not in player_data["kits"]:
                        player_data["kits"][k] = {"elo": 100, "wins": 0, "losses": 0}
                    k_data = player_data["kits"][k]
                    kits_response[k] = {
                        "elo": k_data.get("elo", 100),
                        "wins": k_data.get("wins", 0),
                        "losses": k_data.get("losses", 0),
                        "rank": get_global_rank(player_id, k)
                    }
                
                overall_rank = get_global_rank(player_id, "overall")
            
            self.send_json_response(200, {
                "status": "success", 
                "player_id": player_id, 
                "elo": player_data["elo"],
                "wins": player_data.get("wins", 0),
                "losses": player_data.get("losses", 0),
                "rank": overall_rank,
                "kits": kits_response
            })
            return

        elif path == "/api/queue/join":
            content_length = int(self.headers.get("Content-Length", 0))
            if content_length == 0:
                self.send_error_response(400, "Empty payload")
                return

            post_data = self.rfile.read(content_length)
            try:
                data = json.loads(post_data.decode("utf-8"))
            except Exception:
                self.send_error_response(400, "Malformed JSON")
                return

            player_id = data.get("player_id")
            tailscale_ip = data.get("tailscale_ip")
            ping_limit = data.get("ping_limit", 150)
            selected_kits = data.get("selected_kits", ["Random"])
            custom_kit_name = data.get("custom_kit_name")
            custom_kit_json = data.get("custom_kit_json")
            from layouts import sanitize_layouts
            layouts = sanitize_layouts(data.get("layouts"))
            perf_score = data.get("perf_score", 1.0)
            solo_test = data.get("solo_test", False)

            if not player_id or not tailscale_ip:
                self.send_error_response(400, "player_id and tailscale_ip are required")
                return
            if custom_kit_name and custom_kit_json:
                custom_kit_pool[custom_kit_name] = custom_kit_json

            is_player_community = any(k.startswith("Community:") or k.startswith("Unofficial:") for k in selected_kits)
            community_kit_name = None
            if is_player_community:
                for k in selected_kits:
                    if k.startswith("Community:"):
                        community_kit_name = k[len("Community:"):].strip()
                        break
                    elif k.startswith("Unofficial:"):
                        community_kit_name = k[len("Unofficial:"):].strip()
                        break
            if not community_kit_name and custom_kit_name:
                for c in community_categories:
                    if c.get("name", "").lower() == custom_kit_name.lower() or c.get("id", "").lower() == custom_kit_name.lower():
                        is_player_community = True
                        community_kit_name = c["name"]
                        break

            is_player_custom = (not is_player_community) and (any(k.startswith("Custom") or k == "Custom" for k in selected_kits) or (custom_kit_name is not None))

            if solo_test:
                match_id = str(uuid.uuid4())
                chosen_kit = "Crystal"
                custom_kit_data = None
                if is_player_community:
                    target_name = (community_kit_name or "SpearDrift").lower()
                    cat = next((c for c in community_categories if c.get("name", "").lower() == target_name or c.get("id", "").lower() == target_name), community_categories[0])
                    chosen_kit = f"community:{cat['name']}"
                    custom_kit_data = cat["kit"]
                elif is_player_custom:
                    c_name = custom_kit_name or "Random"
                    chosen_kit = f"custom:{c_name}"
                    custom_kit_data = custom_kit_json
                    if not custom_kit_data and custom_kit_pool:
                        import random
                        pool_cname = random.choice(list(custom_kit_pool.keys()))
                        chosen_kit = f"custom:{pool_cname}"
                        custom_kit_data = custom_kit_pool[pool_cname]
                elif selected_kits:
                    valid_kits = [k for k in selected_kits if k != "Random"]
                    if valid_kits:
                        chosen_kit = valid_kits[0]
                    else:
                        import random
                        chosen_kit = random.choice(["Crystal", "UHC", "Pot", "Mace", "Sword"])
                with lock:
                    # Remove player from queue if present
                    queue[ : ] = [p for p in queue if p["player_id"] != player_id]
                    matches[player_id] = {
                        "match_id": match_id,
                        "role": "host",
                        "host_ip": "127.0.0.1",
                        "opponent_id": "Mock_Opponent",
                        "kit": chosen_kit,
                        "custom_kit": custom_kit_data
                    }
                sys.stderr.write(f"[Matchmaker] [SOLO TEST] Created immediate mock match for Host {player_id} with Kit {chosen_kit}\n")
                self.send_json_response(200, {
                    "status": "matched",
                    "match_id": match_id,
                    "role": "host",
                    "host_ip": "127.0.0.1",
                    "opponent_id": "Mock_Opponent",
                    "kit": chosen_kit,
                    "custom_kit": custom_kit_data
                })
                return
            with lock:
                import time
                now = time.time()
                
                # OPTIMIZATION: Clean up inactive "ghost" players from the matchmaking queue upon new player arrival
                queue[:] = [p for p in queue if now - p.get("last_seen", now) < 15.0]

                # Remove player from queue or previous matches if already present
                queue[:] = [p for p in queue if p["player_id"] != player_id]
                if player_id in matches:
                    del matches[player_id]

                # Scan through queue for the best candidate match based on kit-specific ELO difference
                match_index = -1
                best_match_index = -1
                best_matched_kit = None
                matched_custom_kit_data = None
                best_elo_diff = float("inf")

                all_kits = ["Crystal", "UHC", "Pot", "Mace", "Sword"]

                for idx, opponent in enumerate(queue):
                    opp_id = opponent["player_id"]
                    opp_kits = opponent.get("selected_kits", ["Random"])
                    opp_is_community = any(k.startswith("Community:") or k.startswith("Unofficial:") for k in opp_kits) or any(c.get("name", "").lower() == (opponent.get("custom_kit_name") or "").lower() for c in community_categories)
                    is_opp_custom = (not opp_is_community) and (any(k.startswith("Custom") or k == "Custom" for k in opp_kits) or (opponent.get("custom_kit_name") is not None))

                    if is_player_community and opp_is_community:
                        for k in opp_kits:
                            if k.startswith("Community:"):
                                opp_cname = k[len("Community:"):].strip()
                                break
                            elif k.startswith("Unofficial:"):
                                opp_cname = k[len("Unofficial:"):].strip()
                                break
                        if not opp_cname and opponent.get("custom_kit_name"):
                            opp_cname = opponent.get("custom_kit_name")

                        p_target = (community_kit_name or "").strip()
                        o_target = (opp_cname or "").strip()
                        if p_target and o_target and (p_target.lower() == o_target.lower() or p_target.lower() == "random" or o_target.lower() == "random"):
                            target_cname = p_target if p_target.lower() != "random" else o_target
                            cat = next((c for c in community_categories if c.get("name", "").lower() == target_cname.lower() or c.get("id", "").lower() == target_cname.lower()), community_categories[0])
                            best_match_index = idx
                            best_matched_kit = f"community:{cat['name']}"
                            matched_custom_kit_data = cat["kit"]
                            sys.stderr.write(f"[Matchmaker] Community Kit Match: {player_id} <-> {opp_id} on {cat['name']}\n")
                            break
                        else:
                            continue
                    elif is_player_custom and is_opp_custom:
                        # Custom Kit Pairing Integrity Rules:
                        # 1. A valid match ONLY occurs between one player who selected their OWN custom kit
                        #    (Kit Provider) and one player who selected "Random Custom Kit" (Kit Receiver).
                        # 2. Two players who BOTH selected Random DO NOT match (neither provided a kit).
                        # 3. Two players who BOTH selected their own custom kits DO NOT match (different kits).
                        p_kname = custom_kit_name
                        p_kjson = custom_kit_json
                        o_kname = opponent.get("custom_kit_name")
                        o_kjson = opponent.get("custom_kit_json")

                        p_has_kit = bool(p_kjson) and (p_kname is None or p_kname.strip().lower() != "random")
                        o_has_kit = bool(o_kjson) and (o_kname is None or o_kname.strip().lower() != "random")

                        if p_has_kit and not o_has_kit:
                            # Current player provided their own kit; waiting opponent is seeking a random kit
                            best_match_index = idx
                            best_matched_kit = f"custom:{p_kname or 'Custom'}"
                            matched_custom_kit_data = p_kjson
                            sys.stderr.write(f"[Matchmaker] Custom Kit Match: Provider {player_id} (Kit: {p_kname}) <-> Receiver {opp_id} (Random)\n")
                            break
                        elif o_has_kit and not p_has_kit:
                            # Waiting opponent provided their own kit; current player is seeking a random kit
                            best_match_index = idx
                            best_matched_kit = f"custom:{o_kname or 'Custom'}"
                            matched_custom_kit_data = o_kjson
                            sys.stderr.write(f"[Matchmaker] Custom Kit Match: Provider {opp_id} (Kit: {o_kname}) <-> Receiver {player_id} (Random)\n")
                            break
                        else:
                            # Incompatible: Both provided custom kits, or both are seeking random.
                            # Keep scanning the queue for a compatible opponent.
                            continue
                    elif not is_player_custom and not is_opp_custom and not is_player_community and not opp_is_community:
                        # Standard Official Ranked Matchmaking
                        p_official = [k for k in selected_kits if not k.startswith("Custom") and k != "Custom"]
                        o_official = [k for k in opp_kits if not k.startswith("Custom") and k != "Custom"]

                        overlapping_kits = []
                        if "Random" in selected_kits or "Random" in opp_kits:
                            player_specific = [k for k in p_official if k != "Random"]
                            opp_specific = [k for k in o_official if k != "Random"]
                            if player_specific and opp_specific:
                                overlapping_kits = list(set(player_specific).intersection(set(opp_specific)))
                                if not overlapping_kits:
                                    overlapping_kits = list(set(player_specific + opp_specific))
                            elif player_specific:
                                overlapping_kits = player_specific
                            elif opp_specific:
                                overlapping_kits = opp_specific
                            else:
                                overlapping_kits = all_kits
                        else:
                            overlapping_kits = list(set(p_official).intersection(set(o_official)))

                        for kit in overlapping_kits:
                            kit_name = kit.lower()
                            p_elo = get_player_stats(player_id, kit_name).get("elo", 100)
                            o_elo = get_player_stats(opp_id, kit_name).get("elo", 100)

                            diff = abs(p_elo - o_elo)
                            if diff < best_elo_diff:
                                best_elo_diff = diff
                                best_match_index = idx
                                best_matched_kit = kit

                if best_match_index != -1:
                    match_index = best_match_index
                    matched_kit = best_matched_kit

                if match_index != -1:
                    opponent = queue.pop(match_index)
                    match_id = str(uuid.uuid4())

                    try:
                        player_perf = float(perf_score)
                    except Exception:
                        player_perf = 1.0

                    try:
                        opponent_perf = float(opponent.get("perf_score", 1.0))
                    except Exception:
                        opponent_perf = 1.0

                    # Stronger hardware performance score becomes the host
                    if player_perf >= opponent_perf:
                        host_id = player_id
                        host_ip = tailscale_ip
                        guest_id = opponent["player_id"]
                        guest_ip = opponent["tailscale_ip"]
                    else:
                        host_id = opponent["player_id"]
                        host_ip = opponent["tailscale_ip"]
                        guest_id = player_id
                        guest_ip = tailscale_ip

                    matches[host_id] = {
                        "match_id": match_id,
                        "role": "host",
                        "host_ip": host_ip,
                        "opponent_id": guest_id,
                        "kit": matched_kit,
                        "custom_kit": matched_custom_kit_data
                    }
                    matches[guest_id] = {
                        "match_id": match_id,
                        "role": "guest",
                        "host_ip": host_ip,
                        "opponent_id": host_id,
                        "kit": matched_kit,
                        "custom_kit": matched_custom_kit_data
                    }
                    
                    guest_layouts = layouts if guest_id == player_id else opponent.get("layouts")
                    matches[host_id]["opponent_layouts"] = guest_layouts or {}
                    # Record player to last active match ID mapping
                    player_last_match[host_id] = match_id
                    player_last_match[guest_id] = match_id

                    role_for_joining = "host" if host_id == player_id else "guest"
                    self.send_json_response(200, {
                        "status": "matched",
                        "match_id": match_id,
                        "role": role_for_joining,
                        "host_ip": host_ip,
                        "opponent_id": opponent["player_id"] if host_id == player_id else host_id,
                        "opponent_layouts": matches[host_id].get("opponent_layouts") if host_id == player_id else None,
                        "kit": matched_kit,
                        "custom_kit": matched_custom_kit_data
                    })
                else:
                    # Nobody waiting with compatible kits, add to queue
                    queue.append({
                        "player_id": player_id,
                        "selected_kits": selected_kits,
                        "custom_kit_name": custom_kit_name,
                        "custom_kit_json": custom_kit_json,
                        "layouts": layouts,
                        "ping_limit": ping_limit,
                        "tailscale_ip": tailscale_ip,
                        "perf_score": perf_score,
                        "last_seen": now # Initialize last_seen with current server timestamp
                    })
                    sys.stderr.write(f"[Matchmaker] Player {player_id} entered queue (Kits: {selected_kits})\n")
                    self.send_json_response(200, {"status": "searching"})
            return

        elif path in SOCIAL_POST_ROUTES:
            data = self._read_json_body()
            if data is None:
                self.send_error_response(400, "Invalid JSON body")
                return
            self._social(getattr(social, SOCIAL_POST_ROUTES[path]), data)
            return

        elif path == "/api/skin/upload":
            name = (parse_qs(parsed_url.query).get("name") or [""])[0]
            try:
                n = int(self.headers.get("Content-Length", 0))
            except ValueError:
                n = 0
            if n <= 0 or n > skin_mod.MAX_BYTES:
                self.send_error_response(400, "Skin file empty or too large")
                return
            data = self.rfile.read(n)
            if not skin_store.check_rate(self.client_address[0]):
                self.send_error_response(429, "Too many uploads, try again later")
                return
            try:
                token = skin_store.store(name, data)
            except skin_mod.SkinError as e:
                self.send_error_response(400, str(e))
                return
            self.send_json_response(200, {"status": "ok", "token": token})
            return

        elif path == "/api/skin/reset":
            if self.client_address[0] not in ("127.0.0.1", "::1"):
                self.send_error_response(403, "Local requests only")
                return
            data = self._read_json_body() or {}
            try:
                had = skin_store.reset(data.get("name"))
            except skin_mod.SkinError as e:
                self.send_error_response(400, str(e))
                return
            self.send_json_response(200, {"status": "ok", "removed": had})
            return

        elif path == "/api/queue/leave":
            content_length = int(self.headers.get("Content-Length", 0))
            if content_length > 0:
                try:
                    data = json.loads(self.rfile.read(content_length).decode("utf-8"))
                    player_id = data.get("player_id")
                    if player_id:
                        with lock:
                            queue[:] = [p for p in queue if p["player_id"] != player_id]
                            if player_id in matches:
                                del matches[player_id]
                        sys.stderr.write(f"[Matchmaker] Player {player_id} left queue\n")
                except Exception:
                    pass
            self.send_json_response(200, {"status": "left"})
            return

        elif path == "/api/match/report":
            content_length = int(self.headers.get("Content-Length", 0))
            if content_length > 0:
                try:
                    data = json.loads(self.rfile.read(content_length).decode("utf-8"))
                    winner = data.get("winner")
                    loser = data.get("loser")
                    kit_played = data.get("kit", "overall").lower()
                    reporter = data.get("reporter")
                    if AUTH_STRICT or reporter:
                        if not isinstance(reporter, str) or reporter.lower() not in (str(winner).lower(), str(loser).lower()):
                            self.send_json_response(403, {"error": "You can only report your own match", "code": "not_your_match"})
                            return

                    if winner and loser:
                        with lock:
                                                        # Try to extract matched kit from match cache if not sent directly
                            if kit_played == "overall" or not kit_played:
                                if winner in matches:
                                    kit_played = matches[winner].get("kit", "overall").lower()
                                elif loser in matches:
                                    kit_played = matches[loser].get("kit", "overall").lower()

                            if kit_played.startswith("custom"):
                                sys.stderr.write(f"[Matchmaker] Match completed with custom kit '{kit_played}'. Unranked match: ELO unchanged.\n")
                                match_id = None
                                for pid, session in matches.items():
                                    if pid == winner or pid == loser:
                                        match_id = session.get("match_id")
                                        break
                                if not match_id:
                                    match_id = player_last_match.get(winner) or player_last_match.get(loser) or str(uuid.uuid4())

                                if match_id and match_id not in resolved_matches:
                                    resolved_matches.add(match_id)
                                    import datetime
                                    match_history.append({
                                        "match_id": match_id,
                                        "timestamp": datetime.datetime.now().isoformat(),
                                        "winner": winner,
                                        "loser": loser,
                                        "kit": kit_played,
                                        "winner_elo_change": 0,
                                        "loser_elo_change": 0,
                                        "unranked": True
                                    })
                                    save_history()

                                if winner in matches: del matches[winner]
                                if loser in matches: del matches[loser]

                                self.send_json_response(200, {
                                    "status": "success",
                                    "winner_elo": stats_db.get(winner, {}).get("elo", 100),
                                    "loser_elo": stats_db.get(loser, {}).get("elo", 100),
                                    "winner_elo_change": 0,
                                    "loser_elo_change": 0,
                                    "winner_kit_elo_change": 0,
                                    "loser_kit_elo_change": 0,
                                    "winner_wins": stats_db.get(winner, {}).get("wins", 0),
                                    "winner_losses": stats_db.get(winner, {}).get("losses", 0),
                                    "loser_wins": stats_db.get(loser, {}).get("wins", 0),
                                    "loser_losses": stats_db.get(loser, {}).get("losses", 0),
                                    "winner_kit_elo": 100,
                                    "winner_kit_wins": 0,
                                    "winner_kit_losses": 0,
                                    "loser_kit_elo": 100,
                                    "loser_kit_wins": 0,
                                    "loser_kit_losses": 0,
                                    "winner_rank": get_global_rank(winner, "overall"),
                                    "loser_rank": get_global_rank(loser, "overall"),
                                    "winner_kit_rank": 1,
                                    "loser_kit_rank": 1,
                                    "unranked": True
                                })
                                return

                            # 1. Identify match_id from active sessions
                            match_id = None
                            for pid, session in matches.items():
                                if pid == winner or pid == loser:
                                    match_id = session.get("match_id")
                                    break

                            if not match_id:
                                # Fallback to player's last active match if the session was already cleaned up by consensus
                                match_id = player_last_match.get(winner) or player_last_match.get(loser)

                            # Prevent processing already-resolved matches multiple times
                            if match_id and match_id in resolved_matches:
                                sys.stderr.write(f"[Matchmaker] Received duplicate report for already-resolved match: {match_id}. Returning current stats.\n")
                                wk_change, lk_change, w_overall_change, l_overall_change = compute_elo_change(winner, loser, kit_played)
                                self.send_json_response(200, {
                                    "status": "success",
                                    "winner_elo": stats_db.get(winner, {}).get("elo", 100),
                                    "loser_elo": stats_db.get(loser, {}).get("elo", 100),
                                    "winner_elo_change": w_overall_change,
                                    "loser_elo_change": l_overall_change,
                                    "winner_kit_elo_change": wk_change,
                                    "loser_kit_elo_change": lk_change,
                                    "winner_wins": stats_db.get(winner, {}).get("wins", 0),
                                    "winner_losses": stats_db.get(winner, {}).get("losses", 0),
                                    "loser_wins": stats_db.get(loser, {}).get("wins", 0),
                                    "loser_losses": stats_db.get(loser, {}).get("losses", 0),
                                    "winner_kit_elo": get_player_stats(winner, kit_played).get("elo", 100),
                                    "winner_kit_wins": get_player_stats(winner, kit_played).get("wins", 0),
                                    "winner_kit_losses": get_player_stats(winner, kit_played).get("losses", 0),
                                    "loser_kit_elo": get_player_stats(loser, kit_played).get("elo", 100),
                                    "loser_kit_wins": get_player_stats(loser, kit_played).get("wins", 0),
                                    "loser_kit_losses": get_player_stats(loser, kit_played).get("losses", 0),
                                    "winner_rank": get_global_rank(winner, "overall"),
                                    "loser_rank": get_global_rank(loser, "overall"),
                                    "winner_kit_rank": get_global_rank(winner, kit_played),
                                    "loser_kit_rank": get_global_rank(loser, kit_played)
                                })
                                return

                            is_solo = winner.startswith("Mock") or loser.startswith("Mock") or "Mock" in winner or "Mock" in loser

                            # If solo mock test or session not found, resolve immediately without consensus
                            # If solo mock test or session not found, resolve immediately without consensus
                            # If solo mock test or session not found, resolve immediately without consensus
                            # If solo mock test or session not found, resolve immediately without consensus
                            if is_solo or not match_id:
                                wk_change, lk_change, w_overall_change, l_overall_change = process_elo_and_stats(winner, loser, kit_played)
                                save_stats()

                                final_mid = match_id or str(uuid.uuid4())
                                if final_mid:
                                    resolved_matches.add(final_mid)

                                import datetime
                                history_entry = {
                                    "match_id": final_mid,
                                    "timestamp": datetime.datetime.now().isoformat(),
                                    "winner": winner,
                                    "loser": loser,
                                    "kit": kit_played,
                                    "winner_elo_change": w_overall_change,
                                    "loser_elo_change": l_overall_change,
                                    "solo_test": is_solo
                                }
                                match_history.append(history_entry)
                                save_history()

                                # Clean up traces
                                if winner in matches: del matches[winner]
                                if loser in matches: del matches[loser]
                                self.send_json_response(200, {
                                    "status": "success",
                                    "winner_elo": stats_db.get(winner, {}).get("elo", 100),
                                    "loser_elo": stats_db.get(loser, {}).get("elo", 100),
                                    "winner_elo_change": w_overall_change,
                                    "loser_elo_change": l_overall_change,
                                    "winner_kit_elo_change": wk_change,
                                    "loser_kit_elo_change": lk_change,
                                    "winner_wins": stats_db.get(winner, {}).get("wins", 0),
                                    "winner_losses": stats_db.get(winner, {}).get("losses", 0),
                                    "loser_wins": stats_db.get(loser, {}).get("wins", 0),
                                    "loser_losses": stats_db.get(loser, {}).get("losses", 0),
                                    "winner_kit_elo": get_player_stats(winner, kit_played).get("elo", 100),
                                    "winner_kit_wins": get_player_stats(winner, kit_played).get("wins", 0),
                                    "winner_kit_losses": get_player_stats(winner, kit_played).get("losses", 0),
                                    "loser_kit_elo": get_player_stats(loser, kit_played).get("elo", 100),
                                    "loser_kit_wins": get_player_stats(loser, kit_played).get("wins", 0),
                                    "loser_kit_losses": get_player_stats(loser, kit_played).get("losses", 0),
                                    "winner_rank": get_global_rank(winner, "overall"),
                                    "loser_rank": get_global_rank(loser, "overall"),
                                    "winner_kit_rank": get_global_rank(winner, kit_played),
                                    "loser_kit_rank": get_global_rank(loser, kit_played)
                                })
                                return
                            # Extract reporter's ID (or default if absent)
                            reporter = data.get("reporter", "Unknown")

                            if match_id not in pending_reports:
                                pending_reports[match_id] = {}

                            # Save report indexed by the reporter's player ID to ignore duplicates!
                            pending_reports[match_id][reporter] = {
                                "winner": winner,
                                "loser": loser,
                                "kit": kit_played
                            }

                            # Compute ELO changes based on current stats
                            wk_change, lk_change, w_overall_change, l_overall_change = compute_elo_change(winner, loser, kit_played)

                            consensus_reached = False
                            if len(pending_reports[match_id]) >= 2:
                                pids = list(pending_reports[match_id].keys())
                                r1 = pending_reports[match_id][pids[0]]
                                r2 = pending_reports[match_id][pids[1]]

                                if r1["winner"] == r2["winner"] and r1["loser"] == r2["loser"]:
                                    # Consensus validated! Apply and save database
                                    process_elo_and_stats(winner, loser, kit_played)
                                    save_stats()

                                    import datetime
                                    history_entry = {
                                        "match_id": match_id,
                                        "timestamp": datetime.datetime.now().isoformat(),
                                        "winner": winner,
                                        "loser": loser,
                                        "kit": kit_played,
                                        "winner_elo_change": w_overall_change,
                                        "loser_elo_change": l_overall_change,
                                        "solo_test": False
                                    }
                                    match_history.append(history_entry)
                                    save_history()

                                    # Cleanup match & reports
                                    if winner in matches: del matches[winner]
                                    if loser in matches: del matches[loser]
                                    if match_id in pending_reports: del pending_reports[match_id]
                                    if match_id:
                                        resolved_matches.add(match_id)
                                    consensus_reached = True

                                    sys.stderr.write(f"[Matchmaker] [CONSENSUS_RESOLVED] Winner: {winner} (+{w_overall_change}), Loser: {loser} ({l_overall_change})\n")
                                else:
                                    sys.stderr.write(f"[Matchmaker ERROR] Match reports desync/conflict for match {match_id}!\n")
                                    if match_id in pending_reports: del pending_reports[match_id]

                            # Determine what stats to return. 
                            # If consensus is reached, the database is updated, so read from stats_db directly.
                            # If this is the first report, we add the computed changes so they get the accurate post-match ELO immediately!
                            if consensus_reached:
                                winner_elo = stats_db.get(winner, {}).get("elo", 100)
                                loser_elo = stats_db.get(loser, {}).get("elo", 100)
                                winner_wins = stats_db.get(winner, {}).get("wins", 0)
                                winner_losses = stats_db.get(winner, {}).get("losses", 0)
                                loser_wins = stats_db.get(loser, {}).get("wins", 0)
                                loser_losses = stats_db.get(loser, {}).get("losses", 0)

                                winner_kit_elo = get_player_stats(winner, kit_played).get("elo", 100)
                                winner_kit_wins = get_player_stats(winner, kit_played).get("wins", 0)
                                winner_kit_losses = get_player_stats(winner, kit_played).get("losses", 0)
                                loser_kit_elo = get_player_stats(loser, kit_played).get("elo", 100)
                                loser_kit_wins = get_player_stats(loser, kit_played).get("wins", 0)
                                loser_kit_losses = get_player_stats(loser, kit_played).get("losses", 0)
                            else:
                                # First report: feedforward calculated values
                                winner_elo = stats_db.get(winner, {}).get("elo", 100) + w_overall_change
                                loser_elo = stats_db.get(loser, {}).get("elo", 100) + l_overall_change
                                winner_wins = stats_db.get(winner, {}).get("wins", 0) + 1
                                winner_losses = stats_db.get(winner, {}).get("losses", 0)
                                loser_wins = stats_db.get(loser, {}).get("wins", 0)
                                loser_losses = stats_db.get(loser, {}).get("losses", 0) + 1

                                winner_kit_elo = get_player_stats(winner, kit_played).get("elo", 100) + wk_change
                                winner_kit_wins = get_player_stats(winner, kit_played).get("wins", 0) + 1
                                winner_kit_losses = get_player_stats(winner, kit_played).get("losses", 0)
                                loser_kit_elo = get_player_stats(loser, kit_played).get("elo", 100) + lk_change
                                loser_kit_wins = get_player_stats(loser, kit_played).get("wins", 0) + 1
                                loser_kit_losses = get_player_stats(loser, kit_played).get("losses", 0) + 1

                            self.send_json_response(200, {
                                "status": "success",
                                "winner_elo": winner_elo,
                                "loser_elo": loser_elo,
                                "winner_elo_change": w_overall_change,
                                "loser_elo_change": l_overall_change,
                                "winner_kit_elo_change": wk_change,
                                "loser_kit_elo_change": lk_change,
                                "winner_wins": winner_wins,
                                "winner_losses": winner_losses,
                                "loser_wins": loser_wins,
                                "loser_losses": loser_losses,
                                "winner_kit_elo": winner_kit_elo,
                                "winner_kit_wins": winner_kit_wins,
                                "winner_kit_losses": winner_kit_losses,
                                "winner_kit_rank": get_global_rank(winner, kit_played),
                                "loser_kit_elo": loser_kit_elo,
                                "loser_kit_wins": loser_kit_wins,
                                "loser_kit_losses": loser_kit_losses,
                                "winner_rank": get_global_rank(winner, "overall"),
                                "loser_rank": get_global_rank(loser, "overall"),
                                "winner_kit_rank": get_global_rank(winner, kit_played),
                                "loser_kit_rank": get_global_rank(loser, kit_played)
                            })
                except Exception as e:
                    sys.stderr.write(f"[Matchmaker] Error reporting match: {e}\n")
            self.send_error_response(400, "Invalid report payload")
            return

        self.send_error_response(404, "Endpoint not found")

    # -- accounts / auth ---------------------------------------------
    def _bearer(self):
        h = self.headers.get("Authorization", "")
        return h[7:].strip() if h.lower().startswith("bearer ") else ""

    def _auth_denied(self, claimed):
        """None if the request may act as `claimed`, else (status, code, message)."""
        if not claimed or not isinstance(claimed, str):
            return None   # missing ids are rejected by the route itself
        token = self._bearer()
        with lock:
            who = accounts_store.username_for(token) if token else None
            registered = accounts_store.username_taken(claimed)
        if token and who is None:
            return 401, "session_expired", "Please sign in again"
        if who is not None:
            if who.lower() != claimed.lower():
                return 403, "wrong_account", "This request is for a different account"
            return None
        if registered:
            return 401, "login_required", "Sign in to use this name"
        if AUTH_STRICT:
            return 401, "login_required", "Sign in to play online"
        return None

    def _gate(self, claimed):
        denied = self._auth_denied(claimed)
        if denied:
            self.send_json_response(denied[0], {"error": denied[2], "code": denied[1]})
            return False
        return True

    def _gate_post(self, path, parsed_url):
        """Auth check for protected POST routes. The body is read once and put back for the route."""
        if path in AUTH_POST_QUERY:
            claimed = (parse_qs(parsed_url.query).get(AUTH_POST_QUERY[path]) or [""])[0]
            return self._gate(claimed)
        field = AUTH_POST_FIELDS.get(path)
        if field is None:
            return True
        try:
            n = int(self.headers.get("Content-Length", 0))
        except ValueError:
            n = 0
        raw = self.rfile.read(n) if n > 0 else b""
        self.rfile = io.BytesIO(raw)
        try:
            data = json.loads(raw.decode("utf-8")) if raw else {}
        except Exception:
            return True   # the route answers Malformed JSON itself
        claimed = data.get(field) if isinstance(data, dict) else None
        return self._gate(claimed)

    def _account_route(self, path):
        ip = self.client_address[0]
        if path == "/api/account/me":
            with lock:
                acc = accounts_store.verify(self._bearer())
                body = accounts_mod.AccountStore._public(acc) if acc else None
            if body is None:
                self.send_json_response(401, {"error": "Please sign in again", "code": "session_expired"})
            else:
                self.send_json_response(200, {"account": body})
            return
        if path == "/api/account/exists":
            name = (parse_qs(urlparse(self.path).query).get("name") or [""])[0]
            now = time.time()
            with lock:
                recent = [t for t in _exists_log.get(ip, []) if now - t < 60]
                limited = len(recent) >= 60
                if not limited:
                    recent.append(now)
                _exists_log[ip] = recent
                exists = (not limited) and accounts_store.username_taken(name)
            if limited:
                self.send_json_response(429, {"error": "too_many_attempts", "code": "too_many_attempts"})
            else:
                self.send_json_response(200, {"exists": bool(exists)})
            return
        data = self._read_json_body()
        if data is None:
            self.send_error_response(400, "Malformed JSON")
            return
        if path == "/api/account/logout":
            with lock:
                accounts_store.logout(self._bearer())
            self.send_json_response(200, {"status": "ok"})
            return
        result = err = None
        with lock:
            if path == "/api/account/register":
                now = time.time()
                recent = [t for t in _register_log.get(ip, []) if now - t < 3600]
                if len(recent) >= 10:
                    err = "too_many_attempts"
                else:
                    result, err = accounts_store.register(data.get("username"), data.get("password"))
                    if err is None:
                        recent.append(now)
                _register_log[ip] = recent
            elif path == "/api/account/login":
                result, err = accounts_store.login(data.get("username"), data.get("password"), ip=ip)
            elif path in ("/api/account/recover", "/api/account/recover_check"):
                now = time.time()
                dev = str(data.get("device_id") or "")[:64]
                rkeys = [("ip", ip)] + ([("dev", dev)] if dev else [])
                for k in rkeys:
                    _recover_log[k] = [t for t in _recover_log.get(k, []) if now - t < 86400]
                if any(len(_recover_log[k]) >= 5 for k in rkeys):
                    err = "too_many_attempts"
                else:
                    if path == "/api/account/recover_check":
                        result, err = accounts_store.check_recovery(data.get("username"), data.get("recovery_code"), ip=ip)
                    else:
                        result, err = accounts_store.reset_with_recovery(
                            data.get("username"), data.get("recovery_code"), data.get("new_password"), ip=ip)
                    if err == "bad_recovery":
                        for k in rkeys:
                            _recover_log[k].append(now)
            else:
                self.send_error_response(404, "Endpoint not found")
                return
        if err:
            self.send_json_response(ACCOUNT_HTTP.get(err, 400), {"error": err, "code": err})
            return
        out = {"status": "ok"}
        out.update(result)
        self.send_json_response(200, out)

    def send_json_response(self, status_code, data):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)
    def send_png_response(self, blob):
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Content-Length", str(len(blob)))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(blob)

    def _read_json_body(self):
        try:
            n = int(self.headers.get("Content-Length", 0))
            data = json.loads(self.rfile.read(n).decode("utf-8")) if n > 0 else {}
            return data if isinstance(data, dict) else None
        except Exception:
            return None

    def _social(self, fn, *args):
        """Run a SocialService method under the global lock and send its reply."""
        with lock:
            status, body = fn(*args)
        self.send_json_response(status, body)

    def send_error_response(self, status_code, message):
        self.send_json_response(status_code, {"error": message})

class ThreadedHTTPServer(HTTPServer):
    # Enable threading to handle concurrent polling requests cleanly
    def process_request(self, request, client_address):
        t = threading.Thread(target=self.process_request_thread, args=(request, client_address))
        t.daemon = True
        t.start()

    def process_request_thread(self, request, client_address):
        try:
            self.finish_request(request, client_address)
        except Exception:
            self.handle_error(request, client_address)
        finally:
            self.shutdown_request(request)

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    server = ThreadedHTTPServer(("0.0.0.0", port), MatchmakingHandler)
    sys.stderr.write(f"[Matchmaker Server] Bound and running on 0.0.0.0:{port}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        sys.stderr.write("[Matchmaker Server] Shutting down...\n")
        server.server_close()
