# MCR (Minecraft Ranks): Developer & AI Integration Guide

> **MANDATORY PROTOCOL FOR ALL FUTURE AI MODELS & ASSISTANTS:**
> * **DO NOT** make assumptions about this project, its method signatures, mappings, or fields based solely on this guide or any other documentation.
> * Before proposing, editing, or executing any changes, you are **strictly required** to perform active file-reading (using `sys_read_file` or search tools) on the live source files (Java, Go, Python). Documentation can drift and become stale; the live source code is the single source of truth. Always inspect the live implementation first to prevent compilation, runtime, and mixin injection failures.
> * **DO NOT** attempt to run manual Gradle or Go compile commands from scratch. Always utilize the existing build script `/build_multiplatform.sh` from the project root to automate, optimize, and bundle all OS-specific binaries cleanly.

---

## 1. Project Vision & Philosophy

**Minecraft Ranks (MCR)** standardizes competitive Minecraft PvP by introducing a zero-latency, zero-server-compute-cost, decentralized matchmaking and ELO ranking system.

### 1.1 Dynamic Low-Latency Hosting
Competitive PvP is highly sensitive to connection quality. Traditional centralized servers introduce geographical latency bottlenecks. MCR solves this by pairing players via a central matchmaking coordinator and then establishing a direct, peer-to-peer (P2P) virtual network tunnel using a native Go daemon (`core-daemon`) running Tailscale's user-space virtual mesh.
- The player with the stronger machine (calculated via an automated hardware performance score) acts as the **Host** utilizing Minecraft's integrated singleplayer server.
- The other player joins as the **Guest** connecting over the virtual IP.
- This guarantees sub-millisecond local tick performance and the lowest possible connection ping between geographically close players.

---

## 2. Key Orchestration Layers & "Why" Behind Architectural Decisions

Below is the blueprint of our primary architectural decisions:

### 2.1 Process Isolation (Why We Avoid JNI/JNA)
Instead of using JNI (Java Native Interface) or JNA (Java Native Access) to load Go libraries directly into the JVM memory space, we use a **child-process model**.
*   **The Reason:** JNI/JNA are prone to fatal memory leaks, garbage collection (GC) pauses, and JVM crashes which would degrade client performance. 
*   **The Solution:** The Fabric mod spawns the precompiled native `core-daemon` binary as an in-memory child process on launch. This guarantees that the daemon inherits the exact same operating system permissions, security context, and UID/GID as the parent JVM.
*   **Communication:** Sub-millisecond IPC is maintained over a local TCP loopback channel on port `5005`, bypassing standard JNI-overhead and protecting the rendering thread.

### 2.2 Go Native Daemon (Why We Use Tailscale & tsnet)
Rather than writing raw socket networking or relying on heavy Java networking packages, we offload the direct connection layer to a compiled Go daemon built on Tailscale's user-space network stack.
* **Pure P2P UDP Hole Punching:** Direct UDP hole punching is coordinated via STUN signaling, allowing clients to punch through their NAT gateways to establish direct, zero-relay virtual WireGuard links.
* **Forward Error Correction (FEC):** Raw UDP packets stream player inputs and coordinates at 20 ticks-per-second, with each packet carrying redundant history of the preceding 3 ticks. This isolates the match from up to 75% random packet loss without triggering TCP-style screen freezing or rubber-banding.

### 2.3 Transparent Title Asset Overrides (Why We Avoid Logo Mixins)
To replace the default "MINECRAFT JAVA EDITION" title screen branding:
* **The Problem:** Modifying or redirecting Minecraft's internal logo rendering methods using Mixins (like injecting into `LogoRenderer`) is highly prone to translation failures at runtime due to obfuscation mapping shifts (i.e., "No refMap loaded" crashes).
* **The Solution:** We utilize Minecraft's built-in resource loading priorities. By placing 1x1 fully transparent PNG images in the mod's resource assets at:
  - `assets/minecraft/textures/gui/title/minecraft.png`
  - `assets/minecraft/textures/gui/title/edition.png`
  The game automatically loads our blank assets instead of the vanilla textures. This completely hides the default logos without touching a single line of obfuscated Java bytecode, guaranteeing zero risk of runtime class transformation crashes.
* **Our Title Screen Injection:** In `TitleScreenMixin.java`, we inject into the `extractRenderState` rendering loop to blit our custom high-resolution `mcr_title.png` (optimized to `512 x 357` for perfect texture mapping) centered on the screen.

### 2.4 Aesthetics and Traction First (Why Security is Deferred)
* **The Philosophy:** During this development phase, **user experience, visual polish, and clean client flow take absolute priority**. A premium, clean dashboard aesthetic matching MCSR Ranks standards builds trust, encourages player registration, and gains the project early traction.
* **The Security Roadmap:** Advanced security measures—such as offline-verifiable digital signatures for stats, deep packet inspection on the peer virtual network, and block command exploit filters—are explicitly deferred to future roadmap items. We will refine and establish a robust, beautiful, and working visual pipeline first before hardening the security context.

### 2.5 User-Space Ghost Proxies & Connection Retries (CRITICAL FOR FUTURE MODELS)
* **The Problem:** When players connect with their host operating system's Tailscale disabled, the Java mod's default HTTP client and Minecraft connection cannot route directly to Tailscale IPs (such as the matchmaking server `100.120.244.95` or the peer's IP), throwing immediate `No route to host` exceptions.
* **The Solution:** We run double-sided **local loopback ghost proxies** inside the Go native daemon using `tsnet`:
  1. **Matchmaking HTTP Tunnel (Port 8000):** The Java mod makes all API queries to `127.0.0.1:8000`. The daemon intercepts these on localhost and tunnels them securely over user-space Tailscale to `100.120.244.95:8000`.
  2. **Guest TCP/UDP Tunnels (Ports 25565 & 24454):** Upon pairing, GUEST mod issues a `SET_PEER <host_ip>` command over loopback IPC. Minecraft then connects to `127.0.0.1:25565` and `127.0.0.1:24454`, which the Guest daemon accepts locally and dials the Host over `tsnet`.
  3. **Host TCP/UDP Tunnels:** The Host's singleplayer integrated server auto-publishes on local `127.0.0.1:25565`. The Host daemon listens on the virtual Tailscale interface and proxies incoming peer TCP/UDP streams directly to the local server.
* **Race Condition Mitigation:** Minecraft singleplayer world load has a 2-3 second latency before the port is active. To handle this, the Host-side Go daemon implements a **30-attempt retry loop** (up to 15 seconds) to await local singleplayer socket initialization before dropping connections. Guest dials are similarly guarded with retry loops, resolving all disconnect crashes.

---

## 3. Directory Layout & Entry Points

```
/
├── fabric-bridge/           # Java Fabric mod targeting version 26.1.2
│   ├── src/main/java/       # Code entry points
│   └── src/main/resources/  # Assets and Mixin registrations
├── core-daemon/             # Go module implementation using Tailscale tsnet
├── matchmaking-server/      # Python HTTPS matchmaking signaling backend
├── protocol-shared/         # Common data structures defining 64-byte offsets
└── mods/                    # Destination for compiled multiplatform JARs
```

### Primary Code Entry Points:
1.  **Title Screen Rendering & Logic:** `fabric-bridge/src/main/java/com/p2ppvp/mod/mixin/TitleScreenMixin.java`
    *   *Inspect this file first to view button positioning (`startY`), dynamic logo scaling, and matchmaking status polling.*
2.  **In-Game Leaderboard UI Screen:** `fabric-bridge/src/main/java/com/p2ppvp/mod/client/LeaderboardScreen.java`
    *   *Handles categorized leaderboard fetching and custom rendering of row cards with alternating striping.*
3.  **Arena World Management & Resets:** `fabric-bridge/src/main/java/com/p2ppvp/mod/ArenaManager.java`
    *   *Unpacks pristine `helios.tar.gz` map templates to saves directory on world load to clear advance overrides.*
4.  **Signaling & ELO Database Server:** `matchmaking-server/matchmaking_server.py`
    *   *Stores persistent player records in `stats.json` and manages queue entries. (Production deployment migrated to `100.120.244.95` under `/opt/p2p_matchmaking` managed by the `p2p-matchmaking.service` systemd daemon on boot).*

---

## 4. Competitive Rank Divisions & ELO Formula Specs

*See `FUTURE_PLAN.md` in the project root for complete formulas, decay rates, and matchmaking response rules.*

---

## 5. Development Operations Guide (How To Do Stuff)

To ensure consistency and zero guessing of command-line tools:

### 5.1 Compilation & Packaging
To build everything (Go daemons + Fabric Java JARs) for all target operating systems:
1. Go to the project root directory.
2. Run the build script:
   ```bash
   ./build_multiplatform.sh
   ```
This script compiles the Go daemons, updates Java mod assets, executes Gradle clean builds, and exports optimized JARs to the `/mods` directory. No manual compiling is required.

---

## 6. Advanced Subsystem Implementations (Technical Breakdown)

This section documents the underlying mechanics and mappings used to implement key gameplay synchronization, animation, and resolution systems.

### 6.1 Server-Side Damage Interception (`LivingEntityMixin.java`)
- **Method Hook:** We inject into `hurtServer(ServerLevel, DamageSource, float)` in `LivingEntity.class` (the standard Mojang mapping for server-side damage calculations).
- **Lethality Evaluation:** Prior to applying any damage, the mixin calculates if the incoming damage will reduce the player's health to 0 or below:
  ```java
  float remainingHealth = player.getHealth() - damageAmount;
  if (remainingHealth <= 0.0f) {
      // Intercept and handle match defeat
  }
  ```
- **Red Screen Bypass:** By calling `ci.cancel()` and returning `false`, the damage is discarded before it reaches the player's actual health field. This completely prevents the client from entering the standard vanilla death sequence, bypassing the red death screen.
- **Spectator Transition:** The mixin immediately updates the player's game mode to spectator and heals them back to full (20.0f).

### 6.2 Bypassing the Disconnect Screen (`MinecraftMixin.java` & `ClientCommonPacketListenerImplMixin.java`)
- **Connection Interception:** Rather than relying on standard connection events which suffer from race conditions, we hook into `ClientCommonPacketListenerImpl.onDisconnect(DisconnectionDetails)`.
- **Parsing Details:** If the disconnection details contain `"MATCH_RESOLVED:"`, we mark the client's static flag `P2PPvpModClient.redirectingToTitle = true` and execute match reporting asynchronously.
- **Screen Hijack:** We hook into `Minecraft.setScreen(Screen)`. If the game attempts to set the active screen to `DisconnectedScreen` while `redirectingToTitle` is active, the mixin cancels the call and immediately redirects to `TitleScreen`.

### 6.3 Decelerating ELO Ticking Animation (`TitleScreenMixin.java`)
- **Non-Linear Steps:** To create an animation that starts extremely rapidly and decelerates smoothly, we map an 18-step index array ($N = 18$) across a 3.0-second timeline.
- **Formula:** The time of the $i$-th tick is calculated as:
  $$T_i = 3000 \times \left(\frac{i}{N}\right)^{2.5}$$
- **Synchronization:** To eliminate network-latency and transition delays, the animation begins tracking `elapsed` time using a dedicated `animationStartTime` field set on the first render tick of the overlay screen, rather than using match reporting response timestamps. Displayed ELO values are mapped directly to the active tick index, keeping numbers synced with click sound effects.
- **Close Button Guard:** The "Close Stats" button is set to invisible and inactive until `elapsed >= 3000` (tick index reaches $N$), ensuring players must see the animation finish before closing the dashboard.

### 6.4 Client/Server Slot Remapping (`MatchCoordinator.java`)
- **Command Syntax:** Minecraft `/item replace` commands utilize different slot naming conventions for player entities compared to generic containers.
- ** Remap Definitions:**
  - Inventory Hotbar (0 to 8) $\implies$ `hotbar.0` to `hotbar.8`
  - Normal Inventory (9 to 35) $\implies$ `inventory.0` to `inventory.26`
  - Helmet (39) $\implies$ `armor.head`
  - Chestplate (38) $\implies$ `armor.chest`
  - Leggings (37) $\implies$ `armor.legs`
  - Boots (36) $\implies$ `armor.feet`
  - Offhand (40) $\implies$ `weapon.offhand`

### 6.5 Offline Personal Profile Caching & Interactive Kit-Filtering (`TitleScreenMixin.java` & `matchmaking_server.py`)
- **Single-Registration Sync**: On game startup, the client pings `/api/player/register` exactly once. The server calculates your global leaderboard ranks and retrieves full kit-specific statistics, returning them to the client. This data is instantly serialized to `p2p_player_cache.json`.
- **Zero-Server-Stress Filtering**: Opening your detailed profile card loads your stats instantly from the local cache file. Clickable tabs allow the user to toggle among `Overall`, `Vanilla`, `UHC`, `Pot`, `SMP`, and `Sword` filters. The UI updates ranks, ELOs, and win-rate records instantly, performing 100% of statistical queries locally with zero server hits.
- **Post-Match Local Writing**: When a match completes, `/api/match/report` returns the updated overall and kit-specific ELOs/records for both players. Upon clicking "Close Stats", the client writes these updated fields directly back to the local `p2p_player_cache.json` file, guaranteeing offline synchronization without repeated network requests.
