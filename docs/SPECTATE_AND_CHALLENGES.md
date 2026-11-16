# MCR: Players, Direct Challenges & Spectating

Status: implemented and unit-tested. **Not yet verified in game** (see "Verification status").

## What it does
- **Players screen** lists other online players with status and points. You pick a player and press **Challenge**.
- **Kit choice lives in Queue Settings.** The kit picker (Queue Settings) has a **Challenge Player...** button. The challenge uses the single kit you selected there (official or community). The Players screen shows the chosen kit read-only; clicking that row reopens the picker.
- **Spectate screen** lists live matches. Up to 5 spectators per match; a 6th is rejected.
- **Challenge flow:** sender challenges -> target gets a toast and accepts -> the server creates a match -> both clients pair through the normal queue-polling path.

## Entry points
- Everything lives in **Queue Settings**: **Challenge Player** and **Spectate** sit side by side above Done.
- The title screen has no Players/Spectate row (card is back to 220px).
- **Back** on the Players or Spectate screen returns to Queue Settings, not the title screen.

## Kit rules for challenges
The kit is chosen in Queue Settings **before** pressing Challenge Player. The Players screen has no kit button; it only shows a read-only line "Kit from Queue Settings: ...".

| Selection in Queue Settings | What the challenge uses |
|---|---|
| One official or community kit | That kit |
| Several kits | One of them, picked at random when you press Challenge |
| Random (or nothing) | One random official kit |
| Custom kit only | Not supported yet: Challenge is disabled and the line says so |

Logic: `client/ui/ChallengeKit.java` (`candidates`, `resolve`, `label`; tested by `ChallengeKitTest`).

## Empty and error states
- Server answered with an empty list: "No other players online."
- Spectate with no matches: "No live matches right now."
- Only a real network failure (nothing reachable) shows "Could not reach matchmaker." A server that answers with an error (for example, old server without the new routes) is shown as the empty state.
- Not loaded yet: "Loading..."

## Matchmaker address
`SocialApi` resolves its base URL through `P2PPvpMod.getMatchmakerUrl()` (env `P2P_MATCHMAKER_URL`, system property `p2p.matchmaker.url`, LAN, Tailscale, then loopback). Resolution runs off the render thread and is cached for 10 s. `SocialApi(String)` stays for tests.

## Server (matchmaking-server)
New modules: `kits.py`, `presence.py`, `challenges.py`, `spectate.py`, `social_routes.py`, wired into `matchmaking_server.py`.

Routes:
- `POST /api/presence/heartbeat`
- `GET /api/players/online?player_id=`
- `POST /api/challenge/send`, `GET /api/challenge/poll?player_id=`, plus accept/decline
- `GET /api/matches/live`
- spectate join/leave and host roster

MCR kit strings: official `Crystal`, else `community:<name>` / `custom:<name>`. Accepted challenges write `kit`, `kit_ref` and `custom_kit` into the match.

**The deployed server must be updated** with these files for the Players screen to work against the live matchmaker.

## Host side
- `SpectatorPolicy` decides who may join a hosted match as a spectator.
- `MatchCoordinator` counts only combatants for start/win/loss; spectators are still disconnected when the match ends.

## Tests
- Server: `matchmaking-server/tests/` (73 tests).
- Java: `fabric-bridge/src/test/` (99 tests, JUnit 5).

## Verification status
Verified by tests only:
- Server routes, kit conversion, challenge and spectate rules.
- Kit choice logic, layout helpers, spectator policy.

**Not verified in game:**
- Queue Settings layout (Challenge Player / Spectate row) at different GUI scales.
- Back navigation: Players/Spectate -> Queue Settings.
- Challenge toasts, accept flow, spectator join and leave.
- Spectator viewpoint coordinates (a guess).
- Tailnet reachability for a third node joining a host.
- Custom kits in challenges: intentionally unsupported.

## Known pitfall: duplicate MCR jars
`AutoUpdater.initializeObliterator()` deletes every other `mcr-*`, `nmlpvp-*` and `fabric-bridge-*` jar in the mods folder at startup. Keep exactly one MCR jar there.
