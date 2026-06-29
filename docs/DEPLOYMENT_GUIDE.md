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

We have provided an automated deployment script `deploy_server.sh` in the project root directory.

### To push local changes to the server:
1. Make your edits to `matchmaking-server/matchmaking_server.py` locally.
2. Run the deployment script from the project root folder:
   ```bash
   ./deploy_server.sh
   ```
This script will:
- Securely copy the updated python file using `scp` to `root@100.120.244.95`.
- Trigger `systemctl restart p2p-matchmaking.service` on the server over SSH.
- Query and display the running status and latest log lines of the restarted service.
