# MCR Matchmaking Server: Deployment and Operation Guide

This guide describes the operations, endpoints, and deployment procedures for the centralized P2P matchmaking and signaling server running at `100.120.244.95`.

---

## 1. Core Functions & Mechanics

The matchmaking backend is a lightweight HTTP service written in Python. It handles three primary duties:
1. **Player Registration & Querying:** Stores persistent player records (ELO, Wins, Losses) in `/opt/p2p_matchmaking/stats.json`.
2. **Matchmaking Queue:** Handles player registrations for queues, matches compatible players based on kit formats and performance profiles, and assigns host/guest roles.
3. **Consensus Match Reporting:** To prevent spoofing and client cheats, both players must report match results. Once both reports are received and validated for consensus, the backend processes and updates the persistent ELO ratings.

---

## 2. Remote Server Directories & Management

- **Installation Folder:** `/opt/p2p_matchmaking`
- **Primary Source Code:** `/opt/p2p_matchmaking/matchmaking_server.py`
- **Persistent Database:** `/opt/p2p_matchmaking/stats.json`
- **Match History Ledger:** `/opt/p2p_matchmaking/match_history.json`
- **Systemd Daemon Service:** `/etc/systemd/system/p2p-matchmaking.service`

### Managing Server Process via SSH:
To inspect or control the service directly on the server:
```bash
# Check status
ssh root@100.120.244.95 "systemctl status p2p-matchmaking.service"

# Restart service
ssh root@100.120.244.95 "systemctl restart p2p-matchmaking.service"

# Read real-time server logs
ssh root@100.120.244.95 "journalctl -u p2p-matchmaking.service -f"
```

---

## 3. How to Update Code on the Server (1-Click Deploy)

Run `./deploy_server.sh` from the project root. The server imports several modules, so the script uploads all of
them: `matchmaking_server.py accounts.py challenges.py kits.py layouts.py presence.py skins.py social_routes.py spectate.py`.

It will:
1. Compile every module locally and stop on a syntax error.
2. Back up the live code and data (`stats.json`, `match_history.json`, `accounts.json`) to `backups/pre-deploy-<time>/` on the server.
3. Upload the modules with `scp`, compile them on the server, then restart `p2p-matchmaking.service`.
4. Check that the service is active and answers on port 8000.

To roll back, copy the files from the newest `backups/pre-deploy-*` folder over `/opt/p2p_matchmaking` and restart.

### Accounts on the server
- Accounts are stored in `/opt/p2p_matchmaking/accounts.json` (mode 600; it holds password hashes). Never publish or share it.
- Auth runs in soft mode by default. Add `Environment=MCR_REQUIRE_AUTH=1` to the systemd unit to make tokens mandatory for every name.
- The server has no delete-account route. To remove an account, stop the service, edit `accounts.json`, then start it.

## 4. Client Auto-Updater
- Every 60 seconds until an update is installed, the client checks the latest GitHub release.
- It downloads `MCR-<version>-<os>.jar` to a `.part` file (not a mod extension), throttled to 500 KB/s.
- The file is checked: it opens as a jar, `fabric.mod.json` has id `mcr`, and the version equals the release tag and is newer than the running one. Failed files are deleted.
- Only then it is renamed to the real jar name, and the old jar is deleted (or renamed to `.bak` if locked).
- A pre-launch janitor (`JanitorPreLaunch`) runs before mods load: it removes `.part`, `.bak` and corrupt jars; the lesser jar disables itself and the greater jar deletes the lesser one. Versions come from each jar's `fabric.mod.json`.
- A file named `noupdate.txt` in the mods folder turns the updater off.
