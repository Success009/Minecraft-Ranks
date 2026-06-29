# Minecraft Ranks (MCR): Development Status & Competitive Roadmap

> **CRITICAL INSTRUCTION FOR ALL AI DEVELOPMENT MODELS:**
> Before proposing or implementing any changes based on this plan, you **MUST** read and analyze the live source code files directly (e.g. `TitleScreenMixin.java`, `MatchmakingOptionsScreen.java`, `LeaderboardScreen.java`, etc.).
> 
> * **Code as the Source of Truth:** Documentation can be updated less frequently than code; the live source is the only absolute reality. Relying solely on documents leads to severe hallucinations and broken imports/method signatures.
> * **Do NOT Invent Compilation Commands:** Do not try to compile the Fabric mod or native binaries manually. Always run `/build_multiplatform.sh` from the project root to handle automatic compilation, asset updates, and output placement cleanly.

---

## 1. Project VISION & Status Summary

**Minecraft Ranks (MCR)** standardizes competitive Minecraft PvP by introducing a zero-latency, zero-server-compute-cost, decentralized matchmaking and ELO ranking system. 

The visual dashboard and UI overlays are completely polished, providing a seamless transition between the main dashboard, matchmaking overlays, and categorized leaderboards. Gameplay mechanics, kit distribution, and elegant match resolution sequences are fully functional.

---

## 2. Completed UI/UX & Gameplay Milestones

The following features have been successfully built, verified, and packaged into the core modules:

### 2.1 Custom Vector Button System (`McrButton.java`)
- **Visual Design:** Implemented dynamic flat-color vector buttons drawn using pure GL graphics (`context.fill` / coordinate offsets) instead of default vanilla textures. This makes all UI panels completely immune to any custom resource pack overrides.
- **States:**
  - **Idle State:** Translucent deep charcoal background (`0x220B0B0F`) with a thin, muted gold border (`0x22D4AF37`) and metallic silver text.
  - **Hovered State:** High-contrast translucent background (`0xCC15151A`) with a solid gold border (`0xFFD4AF37`) and bright white text.
  - **Selected State:** Glowing gold background (`0x55D4AF37`) with a solid gold border and bright text, immediately indicating active configurations.

### 2.2 Matchmaking Configuration Screen (`MatchmakingOptionsScreen.java`)
- **Seamless Modal Overlay:** Instead of closing the Title Screen and loading an empty background, this screen acts as a translucent overlay (`0x88050507` backdrop) drawn directly over the active main menu.
- **Max Latency Thresholds:** Includes tactile button selectors for `50ms`, `100ms`, `300ms`, and `Unlimited`.
- **Spacious Double-Row Kit Selector:** To eliminate congestion, the kit choices are arranged into two rows of three spacious, high-contrast buttons (`Random`, `Vanilla`, `UHC` on the top row; `Pot`, `SMP`, `Sword` on the bottom row) scaled to 65px wide with 6px spacing.
- **Instructional latency note:** Deliberately placed below the latency threshold selectors: `(Higher threshold will result in faster matchmaking)` in a clean, muted silver format.
- **Mutual Exclusivity Logic:** Selecting "Random" immediately clears all specific kit targets, while selecting any individual format immediately disables "Random". Clearing all individual selections automatically defaults back to "Random".

### 2.3 Categorized Leaderboards (`LeaderboardScreen.java`)
- Swapped all vanilla category buttons for our premium `McrButton` layout.
- Added a row of kit-specific horizontal filter tabs (`Overall`, `Vanilla`, `UHC`, `Pot`, `SMP`, `Sword`) positioned nicely at `Y = 48` above the table rows.
- Dynamic backend integration: Leaderboard queries dynamically append category and kit-specific parameters to fetch and sort stats (e.g. `/api/leaderboard?category=elo&kit=smp`).

### 2.4 Integrated Server Kit Synchronization & Custom Slot Mapping (`MatchCoordinator.java`)
- **JSON Kit Distribution:** Intercepted match starts to parse custom JSON kit configurations (e.g., `netpot.json`) containing specific items, armor pieces, enchantments, and custom tags.
- **Remapped Slot Coordinates:** Solved inventory command issues by mapping standard JSON integer slots to player-authoritative inventory locations: hotbar indices 0-8 map to `hotbar.0`-`hotbar.8`, inventory indices 9-35 map to `inventory.0`-`inventory.26`, armor slots 36-39 map to `armor.head`/`chest`/`legs`/`feet`, and index 40 maps to `weapon.offhand`.

### 2.5 Zero-Lethality Player Defeat Handling & Spectator Transition (`LivingEntityMixin.java`)
- **Hurt Event Interception:** Injected into the server-side `hurtServer` damage processing entry point. If a player receives damage that would reduce their health to 0 or below during an active match, the damage event is canceled.
- **Spectator Transition:** Defeated players are immediately placed in SPECTATOR mode, healed to full (20.0f) health, given appropriate visual titles/defeat cues, and cleanly disconnected, completely bypassing the default red game-over death screen.

### 2.6 Custom Decelerating ELO Animation & Rhythmic Ticks (`TitleScreenMixin.java`)
- **Non-Linear Timing Curve:** Designed an 18-step cubic timing distribution ($t = \text{fraction}^{2.5}$) over a 3.0-second window. The ELO numbers increment extremely rapidly at the start ("ticktickticktick") and decelerate smoothly ("tick ... tick ...") to settle on the exact final values.
- **Audio Synchronization:** Sound effects play precisely on each visual step change. The "Close Stats" button remains fully hidden and disabled until the 18-step progression is finished.

### 2.7 Automated Disconnect Screen Interception & Redirection (`MinecraftMixin.java`, `ClientCommonPacketListenerImplMixin.java`)
- **Packet Reason Parsing:** Intercepted network connection closure inside `ClientCommonPacketListenerImpl.onDisconnect`. It reads the `DisconnectionDetails` reason; if it detects a `"MATCH_RESOLVED:"` prefix, it parses match outcomes and marks `redirectingToTitle = true`.
- **Screen Cancellation:** Hooked into `Minecraft.setScreen`. It blocks the default raw "Connection Lost" screen from rendering on match completion and redirects the user back to the Main Menu Title Screen to view their statistics card.

### 2.8 JVM-Sharing State Reset On Player Join (`MatchCoordinator.java`)
- **State Cleanup:** Ensured clean transitions on singleplayer worlds sharing the same JVM session. Upon player join, all static matchmaking states, active flags, and countdown ticks are reset to default values, guaranteeing consistent performance in every session.


### 2.9 Game Difficulty Enforcement (`P2PPvpMod.java`)
- **Spawn Rules Enforcement:** Automatically sets the singleplayer integrated server's difficulty to Normal (`Difficulty.NORMAL`) upon player join, ensuring mock opponents (such as Husks) can spawn under vanilla combat rules.

### 2.10 In-Process JNA Native Orchestration Layer (`DaemonManager.java`)
- **JVM Native Integration:** Ported the primary native execution flow to run fully in-process inside the Minecraft JVM using JNA (`Native.load()`). This ensures the Go networking stack inherits identical process security contexts and network permissions without requiring separate external OS binaries.
- **File Execution Isolation:** Moves `.dll`/`.so`/`.dylib` extraction to the system's safe temp folder (`java.io.tmpdir`) to resolve Linux `noexec` home directory permission blocks, while preserving stable Tailscale keys in `user.home`.
- **macOS Fallback Execution:** Gracefully falls back to spawning the macOS-compiled binary (`core-daemon-darwin-amd64`) as an external process when the in-process macOS dynamic library is absent.

### 2.11 Version-Agnostic Name-Based Auto-Updater (`AutoUpdater.java`)
- **Direct Filename Comparisons:** Simplified update-checking by comparing the active running JAR filename directly with the platform-specific release assets on GitHub.
- **Silent Hot-Swapping:** Downloads new platforms automatically and schedules the older file for deletion on JVM shutdown, bypassing active lockouts on Windows systems.

### 2.12 Automated Self-Cleaning Multi-Platform Build Pipeline (`build_multiplatform.sh`)
- **Automatic Build Cleanup:** Automatically deletes older MCR builds from the project's output folder and local Minecraft instance paths (`/home/success0/.minecraft/mods/` and `/mnt/data_vault/.minecraft/mods/`) before compilation to avoid version clutter.
- **Dynamic Version Suffix Incrementor:** Programmatically detects and increments trailing revision suffixes in `gradle.properties` (e.g. `26.1.2-beta.1.5` -> `26.1.2-beta.1.6`).
- **Clean GitHub Releases:** Automates the complete push, tag, and publish cycle, packing platform-specific executable and dynamic library assets dynamically.

---

## 3. Future Engineering Milestones (The Roadmap)

With visual assets, kit inventory systems, and match resolution mechanisms implemented, future development phases will focus on networking robustness, anti-cheat, and databases:

### 3.1 Connection Handshake & Network Probing
- **Goal:** Establish a robust 3-second network handshake protocol over the virtual peer-to-peer interfaces before initiating active match gameplay.
- **Implementation:** Send high-frequency dummy UDP packets between the native core-daemons to verify NAT hole punching stability before launching local integrated server connect requests.

### 3.2 Cryptographic Match Signatures & Verification
- **Goal:** Protect against client-side report spoofing by ensuring match outcomes are cryptographically signed.
- **Implementation:** Implement a mutual handshake within the native core-daemon to sign match reports using asymmetric keys before uploading results to the matchmaking backend.

### 3.3 Relational Database Migrations
- **Goal:** Migrate persistent database storage from JSON-based files (`stats.json`) to PostgreSQL or SQLite to allow complex transactional queue queries.
