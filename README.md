# Decentralized Pure P2P PvP Synchronization Framework

This workspace contains a highly optimized, pure peer-to-peer (P2P) player-vs-player (PvP) synchronization framework. 

By shifting 100% of game ticks, physical kinematics, and combat inputs directly to client machines, this architecture completely bypasses intermediate server compute overhead, hosting fees, and routing latency bottlenecks. A centralized matchmaking server is utilized strictly as a STUN/ICE signaling coordinator to pair players. Once paired, client processes communicate directly via a zero-relay, user-space virtual network interface.

---

## Workspace Structure

The project has a multi-language repository layout targeting version 26.1.2 environments:

- **`docs/`**: Comprehensive developer resources and operational instructions (see `docs/DEVELOPER_GUIDE.md`).
- **`backup_mcr/`**: Complete, self-contained source-code backup of the P2P framework, protecting against regressions.
- **`core-daemon/`**: Go module implementation built on Tailscale's `tsnet` framework, providing the user-space P2P network stack.
- **`fabric-bridge/`**: Java Fabric mod targeting Minecraft version 26.1.2, facilitating process lifecycle orchestration and direct P2P integrated server hook-ups.
- **`protocol-shared/`**: Common data structures defining the exact 64-byte binary offsets for 20-tick-per-second state streaming.

---

## Architectural Principles

1. **The Native Orchestration Layer:** The Java Fabric mod spawns the compiled native Go daemon binary (`core-daemon`) as an in-memory child process. This guarantees that the daemon inherits the exact same operating system permissions, User ID (UID), and Group ID (GID) as the host Minecraft instance.
2. **Sub-Millisecond Loopback IPC:** To pipe high-speed position and input vectors between Java and Go without JNI or JNA memory leaks, GC pauses, or JVM crashes, a local TCP loopback channel is used on port `5005`.
3. **Pure P2P UDP Hole Punching:** Clients utilize STUN matchmaking coordination to perform UDP hole punching through their respective NAT gateways, establishing direct, un-relayed connection paths with sub-20ms latency.
4. **Forward Error Correction (FEC):** Over UDP, each packet carries redundant history of the preceding 3 ticks. This isolates the match from packet loss without triggering TCP-style freezing or lagging.
5. **Tick Boundary Jitter Queue:** Incoming packets are enqueued in a sorted jitter buffer and applied precisely on 20-tick-per-second boundaries, matching version 26.1.2 combat mechanics (such as tiered spear actions).

---

## Getting Started

### Prerequisites
- Go Compiler (1.26.x or newer)
- Java JDK 25 & Gradle

### Compiling the Core Daemon
Navigate to the daemon directory and compile the native executable:
```bash
cd core-daemon
go build -o core-daemon main.go
```

### Compiling & Deploying (Cloud-Offloaded Pipeline)
To compile multiplatform binaries with automated GitHub Actions cross-compilation and local testing deployment, use `build_deploy.sh`:
```bash
# Check syntax & build Linux testing JAR
./build_deploy.sh -d

# Build multiplatform JARs and publish to GitHub Releases
./build_deploy.sh -p
```

---

## Recent Architectural Milestones & Fixes
- **Linux Post-Match Crash Elimination:** Fixed the file unlinking race condition where `saves/p2p_arena_cache` was wiped while `IntegratedServer` was saving chunks and checking `session.lock`. World cache restoration was decoupled from disconnects and bound to `ServerLifecycleEvents.SERVER_STOPPED`.
- **Screen Teardown Protection:** Replaced premature `TitleScreen` injection with clean disconnect completion and `DisconnectedScreen` cancellation.
- **Client Performance:** Added skin lookup caching (`cachedSkin` + 3s throttle) to eliminate 300+ AuthLib threads and texture re-allocations per second.
- **Multiplatform CI/CD Verification:** Automated two-node direct P2P WireGuard handshakes across macOS arm64, Linux x86_64, and Windows x64 runners.

---

## Upcoming Features (Active Roadmap)
- **Custom Kit Creator:** Dedicated in-game sandbox world at arena center `(0, -60, 0)` with clean `/save`, `/name`, and `/exit` commands preserving full DataComponent item properties.
- **Custom Kit Matchmaking & Solo Mock Support:** Sync kits to player accounts on the central backend, practice in solo mock mode, and queue in custom duel formats.
- **Ranked Leaderboard Overhaul:** Restructured tier ladders (Bronze through Champion), dynamic rating adjustments, and dedicated custom kit records.
- **Social & Friend System:** Friend requests, presence status, and direct P2P duel invitations.

## Production Server Deployment
The primary matchmaking signaling server is officially migrated to:
- **Server IP:** `100.120.244.95`
- **Port:** `8000`
- **Installation Path:** `/opt/p2p_matchmaking`
- **Daemon Management:** Managed dynamically on boot via systemd: `p2p-matchmaking.service`
