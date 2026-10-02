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
- **Spacious Double-Row Kit Selector:** To eliminate congestion, the kit choices are arranged into two rows of three spacious, high-contrast buttons (`Random`, `Crystal`, `UHC` on the top row; `Pot`, `Mace`, `Sword` on the bottom row) scaled to 65px wide with 6px spacing.
- **Instructional latency note:** Deliberately placed below the latency threshold selectors: `(Higher threshold will result in faster matchmaking)` in a clean, muted silver format.
- **Mutual Exclusivity Logic:** Selecting "Random" immediately clears all specific kit targets, while selecting any individual format immediately disables "Random". Clearing all individual selections automatically defaults back to "Random".

### 2.3 Categorized Leaderboards (`LeaderboardScreen.java`)
- Swapped all vanilla category buttons for our premium `McrButton` layout.
- Added a row of kit-specific horizontal filter tabs (`Overall`, `Crystal`, `UHC`, `Pot`, `Mace`, `Sword`) positioned nicely at `Y = 48` above the table rows.
- Dynamic backend integration: Leaderboard queries dynamically append category and kit-specific parameters to fetch and sort stats (e.g. `/api/leaderboard?category=elo&kit=mace`).


### 2.4 Integrated Server Kit Synchronization & Custom Slot Mapping (`MatchCoordinator.java`)
- **JSON Kit Distribution:** Intercepted match starts to parse custom JSON kit configurations containing specific items, armor pieces, enchantments, and custom tags.
- **All Competitive Kits Implemented:** Successfully implemented and verified all competitive kit formats (**Crystal, UHC, Pot, Mace, and Sword**), including dynamic kit distribution and balanced attribute scaling.
- **Remapped Slot Coordinates:** Solved inventory command issues by mapping standard JSON integer slots to player-authoritative inventory locations: hotbar indices 0-8 map to `hotbar.0`-`hotbar.8`, inventory indices 9-35 map to `inventory.0`-`inventory.26`, armor slots 36-39 map to `armor.head`/`chest`/`legs`/`feet`, and index 40 maps to `weapon.offhand`.
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

### 2.13 Dynamic Client-Side Overall Stats Computation
- **Local Consolidation:** The Fabric mod ignores precomputed server overall statistics and computes overall ELO and records dynamically on the client side.
- **Formulas:** Overall ELO is calculated as the average of ELOs across all 5 active kits (`crystal`, `uhc`, `pot`, `mace`, `sword`), and overall wins/losses are calculated as the sum of wins/losses across all 5 kits.
- **Continuous Synchronization:** This dynamic computation occurs in real-time when stats are retrieved from the backend (`registerPlayerAsync`), loaded from the local cache (`loadStatsLocally`), or updated post-match (`postMatchCloseButton`).

### 2.14 Persistent Local Caching & Server Reset
- **Instantaneous Load Times:** The computed client-side stats are serialized directly back into `p2p_player_cache.json` after every update, enabling instant offline UI rendering upon menu loads.
- **Wiped Server Database:** Cleared buggy legacy statistics on the remote matchmaking signaling server (`/opt/p2p_matchmaking/stats.json`), establishing a clean default starting ELO of 100 on all kits for every player.

### 2.15 Kit-Specific Skill Matchmaking
### 2.15 Kit-Specific Skill Matchmaking
- **Closer Competitive Matches:** Re-engineered the matchmaking logic on the signaling server (`matchmaking_server.py`) to analyze all overlapping candidate kit selections (including "Random" options).
- **Skill Optimization:** Rather than pairing players on a random kit, the server evaluates players' kit-specific ELOs for each candidate kit and pairs them on the kit that yields the absolute closest skill-level match.

### 2.16 Multiplatform P2P Direct Connection Verification
- **Automated CI/CD Validation:** Integrated a two-node direct `tsnet` peer handshake test (`p2p_direct_test.go`) executed synchronously on GitHub Actions across **Linux (x86_64)**, **macOS (arm64)**, and **Windows (x64)** virtual runners.
- **Zero-Failure Verification:** Asserts bidirectional payload delivery and acknowledgment over raw WireGuard tunnels before any multiplatform binaries are packaged into release JARs.

### 2.17 Host Post-Match Crash & Resource Exhaustion Elimination
- **Linux Unlink Race Condition Fixed:** Resolved the fatal post-match client crash where `ArenaManager.initializeArenaCacheAsync()` deleted `saves/p2p_arena_cache` while `IntegratedServer` was still saving chunks and holding `session.lock`. World cache restoration was moved exclusively to the `ServerLifecycleEvents.SERVER_STOPPED` lifecycle hook.
- **Clean Disconnect Synchronization:** Removed premature `client.setScreen(new TitleScreen())` from `ClientPlayConnectionEvents.DISCONNECT`, allowing vanilla's `disconnect()` and `clearLevel()` to complete unhindered before `MinecraftMixin` redirects cleanly to TitleScreen.
- **Skin Lookup Caching:** Cached player skins in `TitleScreenMixin` with a 3-second throttle, stopping hundreds of redundant AuthLib HTTP/401 lookups and GPU texture allocations per second.
- **Defensive Player Iteration:** Iterated over defensive copies (`new ArrayList<>(server.getPlayerList().getPlayers())`) during disconnects and inventory clears to prevent `ConcurrentModificationException`.
- **Diagnostic Log Rotation:** Modified `DebugLogger.resetLog()` to rotate `mods/p2ppvp_debug.log` to `.bak` on launch, preventing loss of post-crash stack traces.

---

## 3. Active Roadmap: Custom Kits, Ranked Overhaul & Social Features

### 3.1 Custom Kit Creation & Management Engine
- **In-Game Sandbox World:** Players access a dedicated singleplayer creation world loaded directly into the center of the arena (`(0, -60, 0)`), equipped with Creative mode and infinite items.
- **Clean Chat Directives (Non-AI, Minimalist):**
  - Prompt: `"§eUse §6/save §eto store your kit or §c/exit §eto leave."`
  - On `/save`: Prompts for a kit identifier via `/name <kit_name>` (or allows `/save <name>`).
  - On `/exit`: If inventory is empty, exits immediately. If items are present, prompts: `"§cYou have unsaved changes. Type §4/exit §cagain to discard, or §6/save §cto keep."`
- **Data Component & NBT Precision:** Captures exact item components (custom enchantments, potion effects, durability, stack counts, armor slots 36-39, offhand slot 40, and hotbar 0-8).
- **Cloud & Account Synchronization:** Serializes the kit schema to JSON and syncs it to the player's profile on the central Matchmaking Server (`/api/custom_kits/save`), enabling access across multiple client installations and solo mock practice matches.

### 3.2 Custom Kit Matchmaking Architecture (Inspiration & Design)
- **Mode A - Mirrored Custom Duel (Recommended for Fair Play):**
  - When Player A and Player B match, one player's custom kit is chosen by mutual voting or coin-flip, and both players are equipped with that identical kit. This preserves pure competitive balance while enabling infinite custom kit diversity.
- **Mode B - Asymmetric Custom Duel (Open Arena):**
  - Both players bring their own custom gear setups into the arena. Great for theorycrafting and class-based counter matchups.
- **Mode C - Global Community Presets:**
  - Highly upvoted custom kits created by the community can be featured globally on the matchmaker as weekly rotating presets.

### 3.3 Competitive Ranking & Leaderboard System Overhaul
- **Tier & Division Restructuring:**
  - `Bronze` (0 - 499)
  - `Iron` (500 - 999)
  - `Gold` (1000 - 1499)
  - `Diamond` (1500 - 1999)
  - `Master` (2000 - 2499)
  - `Grandmaster / Champion` (2500+)
- **Refined ELO / Rating Progression:**
  - Introduce placement matches for new accounts, win streak bonuses, and dynamic K-factor scaling based on match frequency.
- **Leaderboard UI Enhancements:**
  - Paginated high-contrast tables with search-by-player-name.
  - Dedicated "Custom Kit" casual win tracking vs ranked standard kit ladders.

### 3.4 In-Game Friend & Direct Duel System (Future Expansion)
- **Friend Requests:** Send `/friend add <name>`, `/friend accept <name>`, `/friend remove <name>`.
- **Status Presence:** Central matchmaking signaling tracks online/in-queue/in-match states for friends.
- **Direct P2P Duel Challenge:** Bypass the public queue by typing `/duel <friend_name> [kit]`, establishing direct peer-to-peer matchmaking without MMR constraints.

### 3.5 Central Web Portal & Account Dashboard (Future Expansion)
- **Web Dashboard:** Account creation, web-based leaderboard browsing, and player match history timelines.
- **Web-Based Kit Builder:** Allows players to inspect, configure, and share custom kits via web links that import directly into the Minecraft client.
- **Replay & Spectator Viewer:** Future capability to stream tick input history for competitive analysis.
