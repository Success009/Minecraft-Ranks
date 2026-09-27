# P2P PvP Synchronization Framework: Architecture & System Design Specification
This document establishes the end-to-end system design, mechanics, and communication contracts of the decentralized, pure peer-to-peer (P2P) player-vs-player (PvP) synchronization framework. 

This engine is optimized for high-performance kinematic synchronization in competitive environment environments (e.g., Minecraft version 26.1.2 with Fabric Loader) by shifting 100% of game ticks, physical kinematics, and input simulation to local client machines. It bypasses intermediate server compute overhead, hosting costs, and routing latency bottlenecks entirely.

---


## 1. THE NATIVE ORCHESTRATION LAYER

To ensure absolute security context alignment, minimize memory overhead, and guarantee zero-latency execution, this framework utilizes a **dual-mode native orchestration layer**:

```
[ Primary Mode: In-Process JNA (Windows/Linux) ]
+---------------------------------------------------+
|               Host JVM (Minecraft)                |
|                                                   |
|   +-------------------------------------------+   |
|   |             P2P Fabric Bridge             |   |
|   |             [DaemonManager]               |   |
|   +---------------------+---------------------+   |
|                         | (Loads DLL/SO via JNA)  |
|                         v                         |
|   +-------------------------------------------+   |
|   |            core-daemon (Go JNA)           |   |
|   |      (Runs inside JVM as Go-routines)      |   |
|   +-------------------------------------------+   |
+---------------------------------------------------+

[ Fallback Mode: External Subprocess (macOS) ]
+-----------------------------------+
|               Host JVM            |
|       +-------------------+       |
|       | [Daemon Manager]  |       |
|       +---------+---------+       |
+-----------------|-----------------+
                  | (Spawns Child Process)
                  v
+-----------------------------------+
|        OS Process Boundary        |
|       +-------------------+       |
|       |    core-daemon    |       |
|       |  (Go Executable)  |       |
|       +-------------------+       |
+-----------------------------------+
```

### 1.1 Dual-Mode Execution (In-Process JNA & Subprocess Fallback)
- **Primary Mode (In-Process JNA):** For Windows and Linux clients, the compiled Go network stack (`core-daemon-windows-amd64.dll` and `libcore-daemon-linux-amd64.so`) is dynamically loaded into the Minecraft JVM process using Java Native Access (JNA). Calling `StartDaemon` starts the user-space Tailscale proxy stack as background Go-routines, running entirely in-process. This inherits all parent permissions natively.
- **Fallback Mode (Process Builder Spawning):** On macOS or in environments where JVM dynamic library loading is restricted, `DaemonManager` gracefully falls back to extracting and starting the macOS-compiled binary (`core-daemon-darwin-amd64`) as an isolated background child process using `java.lang.ProcessBuilder`.

### 1.2 Resource Extraction & Security Sandbox Isolation
To comply with modern OS security policies and containerized environments:
- **Execution Sandboxing (`java.io.tmpdir`):** Dynamically loaded shared libraries (`.so`/`.dll`/`.dylib`) are extracted to the system's temporary directory (`java.io.tmpdir`). This bypasses execution denials (e.g. `noexec` mounts) on the user's home directories.
- **State Persistence (`user.home`):** Cryptographic Tailscale node certificates, persistent machine keys, and cache files are stored in the user's home directory (`~/.p2ppvp_daemon`). This ensures that client node identities remain stable and persistent across game restarts without polluting the `/tmp` folder.

### 1.3 Communication & Coordination Layer
To control the daemon and fetch network statistics:
- **JNA Memory-Mapped Bindings:** In-process JNA execution queries status, updates peer destinations, and controls lifecycle operations directly via sub-millisecond memory-pointer sharing and dynamic C-function exports (e.g. `GetStatus`, `SetRemotePeer`), avoiding local TCP socket and port-binding overhead.
- **Sub-Millisecond Loopback IPC Fallback:** When running in subprocess mode, the Java mod communicates with the Go background executable using standard ASCII socket streams over a local loopback TCP channel on port `5005`. Commands like `SET_PEER <ip>` are piped over this loopback to dynamically set up local proxy listeners.

## 2. P2P NETWORK STREAMING & RUNTIME FLOW

The decentralized network architecture utilizes a central orchestration server strictly for initial matchmaking and connection coordination (STUN/ICE), leaving actual gameplay synchronization to direct client-to-client pipelines.

```
+-----------------------------------------------------------------------+
|                            USER MACHINE                               |
|                                                                       |
|  +--------------------+  HTTP  +--------------------+  tsnet  +----+  |
|  |     Java Mod       |------->| 127.0.0.1:8000     |-------->| S  |  |
|  | (Minecraft Client) |        | (Matchmaker Proxy) |         | I  |  |
|  +---------+----------+        +--------------------+         | G  |  |
|            |                                                  | N  |  |
|            | TCP                                              | A  |  |
|            v                                                  | L  |  |
|  +--------------------+  tsnet +--------------------+         | I  |  |
|  |   127.0.0.1:25565  |------->| 100.91.x.x:25565   |-------->| N  |  |
|  | (Local Ghost TCP)  |        | (Go Core Daemon)   |         | G  |  |
|  +--------------------+        +--------------------+         +----+  |
+-----------------------------------------------------------------------+
```

### 2.1 User-Space Ghost Proxy Tunneling (No System-Wide Tailscale Required)
To allow competitive players to pair cleanly even when their host operating system's system-wide Tailscale installation is fully disabled, this architecture leverages an isolated, in-memory **user-space TCP/UDP ghost proxy network stack**:
1. **The Matchmaking Proxy (Port 8000):** On startup, the Go daemon binds to local loopback port `127.0.0.1:8000`. When the Java mod makes HTTP matchmaking API calls (registrations, queues, polling, or leaderboards) to local port 8000, the Go daemon accepts the connection and dials the dedicated signaling server (`100.120.244.95:8000`) over the in-memory `tsnet` interface. This eliminates `No route to host` exceptions on the host JVM.
2. **The Guest Proxy Model (Loopback Forwarding):** Once a match is made, the Guest mod sends a `SET_PEER <host_ip>` command to its local Go daemon. The Guest mod then connects to `127.0.0.1:25565` (local loopback). The Guest's Go daemon intercepts the connection on `127.0.0.1:25565` and proxies all TCP streams directly to the Host's virtual Tailscale IP on port `25565` using `tsnet.Dial()`. UDP kinematic packets on port `24454` are mirrored in the same manner.
3. **The Host Proxy Model (Virtual Listening):** On the Host, the Minecraft server binds locally to `127.0.0.1:25565`. The Host's Go daemon starts an in-memory Tailscale listener on port `25565` (`tsnet.Listen()`). When the Guest's proxy connection is received over the Tailscale interface, the Host's Go daemon accepts the connection and forwards it to the local Minecraft integrated server on `127.0.0.1:25565`. Since both processes bind to different interfaces, there is zero port conflict.

### 2.2 Forward Error Correction (FEC) over UDP
Because standard TCP handles packet loss by halting the stream to await retransmission (inducing packet freezing or "rubber-banding" in competitive play), this framework uses an optimized Forward Error Correction (FEC) protocol over raw UDP:
- **Redundant Tick State Arrays:** Each tick packet transmitted at the 20-tick-per-second rate does not merely contain the current state. Instead, it embeds slightly redundant kinematic data from the *last 3 ticks* (Ticks $N-1$, $N-2$, $N-3$) in a packed array behind the current state.
- **Immediate Loss Recovery:** If a UDP packet carrying Tick $N$ is dropped in transit, the receiving client does not wait. The arrival of Tick $N+1$ contains the packed delta or raw coordinates for Tick $N$. The receiver extracts the dropped state immediately, isolating the match from up to 75% random packet loss without triggering TCP-style freezing or lagging.

---

## 3. KINEMATIC & GAMEPLAY PROTOCOL SCHEMAS

Player updates are streamed at a full 20-tick-per-second rate. The network protocol uses a strict, unaligned, dense binary layout of exactly 64 bytes to minimize MTU sizes and optimize serialization speeds.

### 3.1 Strict 64-Byte Primitive Offset Layout

| Offset (Bytes) | Field Name   | Data Type | Description |
|----------------|--------------|-----------|-------------|
| **0 – 7**      | TickIndex    | uint64    | Monotonically increasing tick index from source client |
| **8 – 15**     | PosX         | float64   | Player world coordinate X (Double precision) |
| **16 – 23**    | PosY         | float64   | Player world coordinate Y (Double precision) |
| **24 – 31**    | PosZ         | float64   | Player world coordinate Z (Double precision) |
| **32 – 35**    | VelX         | float32   | Velocity vector X component (Single precision) |
| **36 – 39**    | VelY         | float32   | Velocity vector Y component (Single precision) |
| **40 – 43**    | VelZ         | float32   | Velocity vector Z component (Single precision) |
| **44 – 47**    | Pitch        | float32   | Camera head pitch angle [-90.0, 90.0] |
| **48 – 51**    | Yaw          | float32   | Body rotation yaw angle [0.0, 360.0] |
| **52 – 53**    | MoveVecX     | int16     | Raw keyboard movement X input vector (scaled [-32767, 32767]) |
| **54 – 55**    | MoveVecZ     | int16     | Raw keyboard movement Z input vector (scaled [-32767, 32767]) |
| **56**         | Flags        | uint8     | Discrete input bitmask (Jump, Sprint, Crouch, Lunge) |
| **57**         | ActionState  | uint8     | Enum mapping current combat state and tiered weapon stages |
| **58 – 61**    | Checksum     | uint32    | CRC32 checksum computed over bytes 0 through 57 |
| **62 – 63**    | Padding      | uint16    | Fixed zero-padding to align payload to exact 64-byte boundary |

#### Flags Bitmask (Byte 56):
- **Bit 0 (0x01):** Jump State (Active/Inactive)
- **Bit 1 (0x02):** Sprinting State
- **Bit 2 (0x04):** Crouching State
- **Bit 3 (0x08):** Weapon Lunging state

#### ActionState Enum (Byte 57) - Targeting Version 26.1.2 Tiered Mechanics:
- **0x00 (0):** Idle / No Combat Action
- **0x01 (1):** Tiered Spear Action - Level 1 (Light charge)
- **0x02 (2):** Tiered Spear Action - Level 2 (Medium charge)
- **0x03 (3):** Tiered Spear Action - Level 3 (Fully charged armor-piercing thrust)

---

## 4. RUNTIME JITTER BUFFER & TICK-BOUNDARY ALIGNMENT

To guarantee smooth kinematic interpolation and prevent packet delivery jitter from causing micro-stutter on the receiving client, incoming packets are not executed immediately upon socket reception.

```
[ Incoming Network Packets ]
         |
         v
+------------------------+
|  Jitter Queue (Buffer)  |  <--- Reorders and buffers packets based on TickIndex
+------------------------+
         |
         | (Pulls exact index matching game tick)
         v
[ Client Tick Boundary (20 TPS) ] -> Apply position, velocities, and spear tiers
```

### 4.1 Tick Index Enqueuing
- **The Jitter Queue:** Upon receiving a binary kinematic packet, the client extracts the `TickIndex`. Because packets might arrive out of order, the client inserts the packet into a sorted bounded-size Jitter Queue, indexed by `TickIndex`.
- **Target Tick Alignment:** The receiving client runs a rendering and update ticker offset by a target duration (typically 2-3 ticks, i.e., 100-150ms).
- **Exact Execution:** When the client engine executes a physics update tick, it queries the Jitter Queue for the frame whose `TickIndex` matches the current target tick.
  - If the packet is present: The client applies the player's position, velocity vectors, and action state.
  - If a packet is missing (dropped): The client falls back to the Forward Error Correction (FEC) redundant array, or performs a local dead-reckoning extrapolation based on the last known velocity vector (`VelX`, `VelY`, `VelZ`).
- This design aligns with version 26.1.2 mechanics, guaranteeing that tiered spear animations and physical knockback kinematic states sync perfectly on tick boundaries.

---

## 5. STRATEGIC ROADMAP: TAILSCALE NETWORK TOPOLOGY & ACLs

To prevent unauthorized peer discovery, packet sniffing, or local host compromise, the virtual network overlay uses strict Access Control Lists (ACLs). This structure confines matched players to isolated, game-port-only micro-meshes.

### 5.1 Strict P2P Tailscale ACL Specifications
Below is the structural blueprint for our production Tailscale ACL configuration. It enforces zero-trust routing across the virtual network interface, allowing only UDP-based game traffic on designated ports, and preventing arbitrary ICMP ping scans or SSH access between peers.

```json
{
  // Groups of administrators and service orchestrators
  "groups": {
    "group:matchmakers": [ "matchmaker-prod@p2ppvp.internal" ],
    "group:admins":      [ "admin@p2ppvp.internal" ]
  },

  // Tag definitions for client nodes and backend controllers
  "tags": {
    "tag:game-client": [ ],
    "tag:controller":  [ ]
  },

  // Access Control Rules enforcing isolated micro-meshes
  "acls": [
    // 1. Allow clients to talk to matchmaking controller over secure HTTPS control plane
    {
      "action": "accept",
      "src":    [ "tag:game-client" ],
      "dst":    [ "tag:controller:443" ]
    },

    // 2. RESTRICT PEER-TO-PEER TRAFFIC: Game clients can ONLY send raw UDP kinematics on port 25565 and 24454
    // This prevents unauthorized port scanning, SSH connections, or protocol exploitation.
    {
      "action": "accept",
      "src":    [ "tag:game-client" ],
      "dst":    [ "tag:game-client:25565", "tag:game-client:24454" ],
      "proto":  "udp"
    },

    // 3. Admin access override for troubleshooting and logging
    {
      "action": "accept",
      "src":    [ "group:admins" ],
      "dst":    [ "*:*" ]
    }
  ],

  // Deny all other traffic by default (Implicit Deny-All rule)
  "hosts": {
    "matchmaker": "100.100.1.1"
  }
}
```

---

## 6. MATCHMAKING SIGNALLING BACKEND

The matchmaking signaling backend is a lightweight, high-performance HTTP service implemented in Python. Deployed as a systemd daemon (`p2p-matchmaking.service`) on the remote server (`100.120.244.95:8000`), its primary responsibility is coordinating the pairing of nodes, managing persistent player statistics, and resolving match outcomes.

```
       [ Matchmaking Signaling Server (100.120.244.95) ]
        /                                            \
       /                                              \
(1. Queue Registration)                        (1. Queue Registration)
     /                                                  \
    v                                                    v
[ Client A ] <--------- (2. direct P2P connection) ----> [ Client B ]
```

### 6.1 Persistent Databases and Clean Slate
- **JSON File-Based Persistence:** Persistent player statistics (ELO records, wins, losses, ranks) are stored in `/opt/p2p_matchmaking/stats.json`. Completed match histories are logged chronologically in `/opt/p2p_matchmaking/match_history.json`.
- **Database Reset:** Buggy legacy statistics have been wiped clean, resetting the database to a fresh state where all new connecting players start with a default ELO of 100 across all 5 competitive kits.

### 6.2 Kit-Specific Skill Matchmaking
- **Queue Logic:** When players enter the matchmaking queue, the server dynamically analyzes overlapping kit selections (including specific individual kits and "Random" entries).
- **Skill Balancing:** Instead of matching players on a random format, the signaling server evaluates the players' kit-specific ELOs for each overlapping candidate kit and matches them on the format that yields the closest, most competitive skill-level matchup.

### 6.3 Security Policy during Active Testing Phase
- **Deferred Security:** Because the framework is in an active development and testing phase, advanced security measures (including cryptographic signatures for stats reporting, packet filtering, and transaction boundaries) are explicitly deferred.
- **Philosophy:** Prioritizes visual polish, gameplay mechanics, and core matchmaking reliability first. Security implementations will be introduced prior to production deployment once the core framework is established and verified.