# Plan: Friends, Friendly Matches and a Bigger Title Screen

Status: PLAN ONLY. Nothing here is implemented. Purpose: agree on scope, then rank and vote on
which feature to build first. We build ONE feature at a time.

## 1. Goals (from the discussion)
1. Add a friend request + friend list system.
2. Let friends 1v1 each other with NO Elo change ("friendly match"), without opening the active
   player list and challenging someone every time.
3. Upgrade the title screen with more buttons/utilities driven by what players need
   (friend list, mod settings, music later, option to disable random challenges, accept/ignore
   challenges from friends only, and more).
4. Later: a lightweight "play with your friend repeatedly" space with rematch. NOT a team, NOT a
   party in the "playing together" sense. (Maybe duos much later.) Working name: see section 6.
5. Grow the system step by step.

## 2. What exists today (verified by reading code)
| Area | Current behaviour | Where |
|---|---|---|
| Player identity | `player_id` = Minecraft username (`client.getUser().getName()`). No auth; server trusts what the client sends. | `P2PPvpModClient.java` ~line 187 |
| Presence | In-memory, heartbeat, 15 s TTL. Statuses: idle, queued, in_match, spectating, offline. Server derives status from its own `matches` and `queue`. | `presence.py`, `social_routes.py` |
| Challenges | In-memory. TTL 30 s, pair cooldown 30 s, send cooldown 3 s, max 1 outgoing, max 3 incoming per target. Target must be `idle` or `queued`; sender must be `idle`. | `challenges.py`, `social_routes.py` |
| Challenge accept | `_create_match` picks host by `perf_score`, writes `matches[pid]` for both players, drops other challenges. | `social_routes.py` |
| Persistence | Flat JSON files next to the server: `stats.json`, `match_history.json`, `community_categories.json`, `skin_data/`. | `matchmaking_server.py` |
| Unranked matches | `/api/match/report` already has an "unranked" branch: Elo changes 0, history entry with `unranked: true`. Currently triggered only when the reported kit name starts with `custom`. | `matchmaking_server.py` (match report handler) |
| Client social code | `SocialApi`, `SocialPoller` (heartbeat + challenge poll), `SocialJson`, `SocialModels`. | `client/social/` |
| Client UI | `PlayersScreen`, `SpectateScreen`, `ChallengeToast`, `ToastQueue`, `TitleLayout`. Challenge Player and Spectate live in Queue Settings; the title card is 220px with no Players row. | `client/`, `client/ui/` |

NOT verified yet (read before building): `TitleLayout.java`, the button code in `TitleScreenMixin`,
`PlayersScreen`, `ChallengeToast`, and how `/api/challenge/*` routes are wired inside
`matchmaking_server.py`.

## 3. Design decisions to settle first

### D1. Friend identity
Today `player_id` is the username. Usernames can change and anyone can send any name.
| Option | Pros | Cons |
|---|---|---|
| A. Keep username as id | Zero change, matches everything else | Rename breaks friendships; spoofable |
| B. Use Minecraft UUID as the key, store username as display name | Rename-safe, matches how Minecraft identifies players | Needs the client to send UUID; server still trusts the client |
| C. B + Mojang session verification (`hasJoined`) | Real anti-spoofing | More work, needs outbound HTTPS from server |

Recommendation: B for friends (key = UUID, show current name). Treat C as a separate "security"
feature later. Changing the id of existing stats/skins is NOT part of this; only the friends file
uses the UUID.

### D2. Friend request model
- Request is created by name or by picking from the Players list.
- Statuses: pending, accepted, declined, blocked.
- Requests must work when the target is OFFLINE (stored, shown on next login). This is the main
  reason friends need a persistent file instead of the in-memory challenge manager.
- Limits: max friends (e.g. 100), max pending outgoing (e.g. 20), per-target cooldown after a
  decline, so nobody can be spammed.

### D3. Friendly match (no Elo)
- Add a `friendly: true` field to the server's match record, set in `_create_match` when the
  challenge was a friend challenge. Do NOT rely on the client-reported kit name for this.
- In `/api/match/report`, check the server-side match record first: if `friendly`, take the
  existing unranked branch (Elo 0, history entry with `unranked: true`, plus a `friendly` marker).
- Result: friendly wins/losses never touch `stats_db`. Whether to keep a separate
  head-to-head record (see F5) is a later decision.
- Open question: the report is sent by clients. Today consensus reporting protects against one
  lying client; friendly flag read from the server match record keeps that protection.

### D4. Challenge rules for friends
Reuse `ChallengeManager`, with these differences:
- Target can be picked straight from the friend list (no need to be on the active list).
- Friend challenges skip the per-pair cooldown (rematch must be quick) but keep the global send
  cooldown (3 s).
- Target must still be `idle` or `queued`. Friend offline: show "offline", disable the button.
- A per-player privacy setting decides who may challenge them (see F4).

### D5. Storage
New file `friends.json` in the server folder, same load/save pattern as `stats.json`:
```
{ "<uuid>": { "name": "...", "friends": ["<uuid>"], "incoming": ["<uuid>"],
              "outgoing": ["<uuid>"], "blocked": ["<uuid>"],
              "settings": { "allow_challenges": "friends|everyone|nobody" } } }
```
New server module `friends.py` (pure logic, no HTTP, like `challenges.py`), routes added through
`social_routes.py`. Unit-tested with a fake clock the same way as the existing modules.

## 4. Feature candidates (for ranking and voting)
Effort: S = about a day, M = a few days, L = a week or more. Estimates are guesses, not measured.

| # | Feature | What the player gets | Effort | Depends on |
|---|---|---|---|---|
| F1 | Friend requests + friend list (server + minimal screen) | Add by name, accept/decline, remove, see online status | M | D1, D2, D5 |
| F2 | Friend challenge, no Elo ("friendly match") | One click on a friend to duel, results don't change Elo | M | F1, D3, D4 |
| F3 | Rematch button | After a match, one click to play the same friend again, same kit | S | F2 (works for non-friends too if allowed) |
| F4 | Challenge privacy settings | Disable random challenges; friends-only; nobody | S | F1 (the friends-only value), a settings store |
| F5 | Head-to-head record vs a friend | "You 7 - 5 Alex" shown in the friend list, no Elo | S | F2 |
| F6 | Title screen upgrade: buttons row + Friends, Settings entry points | Place to put the new features | M | none (can go first or alongside F1) |
| F7 | Mod settings screen (music later, toasts, challenge filters) | One place for mod-specific options | M | F6 |
| F8 | Join-notifications: "Alex is online" toast, "friend request" toast | Awareness without opening menus | S | F1, existing `ToastQueue` |
| F9 | Invite to a "Sparring Room" (section 6) | Stay with a friend and play many rounds | L | F2, F3 |
| F10 | Duos / 2v2 | Real team play | L+ | far later, not planned now |

### Suggested dependency order (not a decision)
F6 (empty shell) -> F1 -> F2 -> F3 -> F4 -> F5 -> F8 -> F7 -> F9.
Smallest useful first slice: F1 + F2 (add a friend, duel them with no Elo).

## 5. Title screen upgrade (F6, F7)
Current state: the title card is 220px tall; Players and Spectate were moved into Queue Settings.
Needs a layout pass (not verified in game at different GUI scales, see
`SPECTATE_AND_CHALLENGES.md`).

Proposed additions, each optional and ranked later:
- **Friends** button (opens friend list; badge with pending request count).
- **Mod Settings** button (F7).
- A compact icon row instead of more full-width buttons, so the card does not keep growing.
  The skin profile avatar (already on the title screen) can sit in the same area.
- Future slots: music toggle, Sparring Room, notifications.

Constraints to respect:
- `TitleLayout` is pure logic (tested), keep new layout math there so it stays unit-testable.
- Anything new in `TitleScreenMixin` follows the existing mixin rules (`defaultRequire: 1` crashes
  the game if a target is missing).
- Check GUI scales 1-4 and the smallest window size.

## 6. "Sparring Room" (placeholder name, F9)
Not a team and not a party. Two players stay linked after a match so they can repeat without
re-challenging. Name candidates: Sparring Room, Series, Rival Room, Dojo. The name is open.

Possible first version (when we get there):
- Created automatically after a friendly match, or manually from the friend list.
- Holds: the two players, current kit, round counter, running score.
- Buttons: Rematch, Change kit, Leave.
- Ends when either player leaves or goes offline; nothing persistent except the optional F5 record.
- Can be extended to more than 2 players later (rotation, spectating friends) without being
  called a team.

Design rule: all of this stays unranked. Ranked play stays on the existing queue.

## 7. Server API sketch (not final)
```
POST /api/friends/request     {player_id, target}
POST /api/friends/respond     {player_id, requester, accept}
POST /api/friends/remove      {player_id, friend}
GET  /api/friends/list?player_id=      -> friends (+ status), incoming, outgoing
POST /api/friends/block       {player_id, target}
POST /api/friends/settings    {player_id, allow_challenges}
POST /api/challenge/send      existing, add optional  "friendly": true  (server validates friendship)
```
Rules: server verifies the two players are friends before accepting `friendly: true`. Status for
the friend list comes from `SocialService.status_of()`, so it matches the Players screen.
Client side: new methods in `SocialApi`, new models in `SocialModels`, parsing in `SocialJson`,
polling folded into the existing `SocialPoller` (no extra threads).

## 8. Risks and things to check
1. Name-based identity (D1). Decide before F1 ships; changing it later is painful.
2. The deployed server must be updated with the new files (same pitfall as the Players screen).
3. `friends.json` write safety: use write-to-temp then rename so a crash cannot corrupt it.
4. Request spam and harassment: limits, block list, and "friends only" setting (F4).
5. Status privacy: showing "in match" to friends only vs. everyone is an open choice.
6. Friendly flag must come from the server's match record, not from the client report.
7. Nothing here has been tested in game.

## 9. Questions for the vote discussion
1. Which is the first slice: F1 alone, F1+F2, or the title screen shell (F6) first?
2. D1: stay on usernames for now, or move friends to UUIDs from the start?
3. Should friends be allowed to challenge each other while one is `queued` (ranked queue)? Current
   challenge rules allow target `queued`.
4. Rematch (F3): friends only, or any opponent?
5. Should friendly matches keep a head-to-head record (F5), or be fully forgotten?
6. Sparring Room name and whether it should ever be more than 2 players.
7. Which title screen buttons matter most, in order?

## 10. Voting template
Score each feature 1-5 for Need and 1-5 for Excitement, then divide by Effort (S=1, M=2, L=3).
| # | Feature | Need | Excitement | Effort | Score |
|---|---|---|---|---|---|
| F1 | Friend requests + list | | | 2 | |
| F2 | Friendly match | | | 2 | |
| F3 | Rematch | | | 1 | |
| F4 | Challenge privacy | | | 1 | |
| F5 | Head-to-head record | | | 1 | |
| F6 | Title screen shell | | | 2 | |
| F7 | Mod settings | | | 2 | |
| F8 | Online/request toasts | | | 1 | |
| F9 | Sparring Room | | | 3 | |
