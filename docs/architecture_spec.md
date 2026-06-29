# P2P PvP Synchronization Framework: Architecture & System Design Specification
This document establishes the end-to-end system design, mechanics, and communication contracts of the decentralized, pure peer-to-peer (P2P) player-vs-player (PvP) synchronization framework. 

This engine is optimized for high-performance kinematic synchronization in competitive environment environments (e.g., Minecraft version 26.1.2 with Fabric Loader) by shifting 100% of game ticks, physical kinematics, and input simulation to local client machines. It bypasses intermediate server compute overhead, hosting costs, and routing latency bottlenecks entirely.

---

## 1. THE NATIVE ORCHESTRATION LAYER

To bypass the memory boundaries and garbage collection (GC) latency spikes associated with traditional Java Native Interface (JNI) or Java Native Access (JNA) bindings, this framework uses an isolated process orchestration design.

```
+-----------------------------------+
|      Host JVM (Minecraft)         |
|                                   |
|   +---------------------------+   |
|   |    P2P Fabric Bridge      |   |
|   |                           |   |
|   |    [Daemon Manager]       |   |
|   +-------------+-------------+   |
+-----------------|-----------------+
                  | (Spawns Child Process)
                  v
+-----------------------------------+
|      OS Process Boundary          |
|                                   |
|   +---------------------------+   |
|   |       core-daemon         |   |
|   |      (Go Executable)      |   |
|   +---------------------------+   |
+-----------------------------------+
```

### 1.1 Process Lifecycle & Spawning
- **Lifecycle Mirroring:** The Fabric Mod (Java) acts as the parent supervisor. On game launch (within the client initialization flow), `DaemonManager` locates and starts the compiled native Go binary (`core-daemon`) as a background child process using `java.lang.ProcessBuilder`.
- **Automatic Cleanup:** To prevent "ghost" daemon processes on abrupt game crashes, the Java parent registers a JVM shutdown hook (`Runtime.getRuntime().addShutdownHook(...)`) that explicitly calls `Process.destroy()` on the daemon. The Go process also monitors standard input (stdin) or processes a heartbeat check; if the parent process drops, the daemon terminates instantly.

### 1.2 Permission-Inheritance Model
- **Security Context Integration:** Because the `core-daemon` is spawned directly as a child process of the Minecraft JVM, it inherits the exact User ID (UID), Group ID (GID), and security context granted to Minecraft by the host operating system.
- **Port Binding and Resources:** No administrative privilege escalation (e.g., `sudo`) is required. The daemon binds to user-space ports (above 1024) and writes transient node state to the parent-defined JVM temporary directory or user-space cache paths (e.g., `~/.cache/P2P_PvP_Daemon_State`), aligning with standard sandbox models.

### 1.3 Local IPC Layer (Sub-Millisecond Loopback)
To pipe kinematic data arrays and player status between the Java mod and the Go network stack without JNI memory-overhead or JVM thread-blocking risks:
- **TCP/UDS Loopback Channel:** The local communication pipeline uses a local TCP loopback (`127.0.0.1:5005`) or Unix Domain Sockets (UDS) for Unix-based machines.
- **Command Communication Protocol:** Java uses standard ASCII socket streams over port 5005 to control the Go daemon. For example, triggering a matchmaking peer connection sends a `SET_PEER <ip>` command, which initializes loopback proxy listeners dynamically in Go without restarting.
- **Zero-Copy JSON Bypass:** Data packets exchanged over local IPC are formatted as plain byte offsets or strict delimited strings rather than complex, heavy JSON envelopes, preserving CPU clock cycles on the rendering thread.

---

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

## 6. STRATEGIC ROADMAP: MATCHMAKING SIGNALLING BACKEND

The matchmaking coordinator is designed as a zero-state, low-memory (4GB RAM safe) signaling microservice written in Go or FastAPI. Its primary responsibility is the short-term pairing of nodes and coordination of UDP hole-punching sequences.

```
       [ Matchmaking Signaling Server ]
        /                            \
       /                              \
(1. Queue Registration)        (1. Queue Registration)
     /                                  \
    v                                    v
[ Client A ] <--- (2. STUN / Punch) ---> [ Client B ]
```

### 6.1 Geographic Player Bucketing
- **Latency-Based Isolation:** The matchmaker partitions the active player queue into regional pools using localized Geodns routing or client-reported latency estimates.
- **Dynamic Ping Buckets:** Players are grouped into dynamic concentric ping circles (e.g., `<20ms`, `20-50ms`, `50-100ms`). The search radius expands progressively every 2 seconds if no opponent is located within the tighter threshold.

### 6.2 Matchmaking Lifecycle and 3-Second STUN Test
1. **Queue Entrance:** Clients post an encrypted registration request to the matchmaker containing their current public STUN-reflected endpoint and their ephemeral WireGuard public key.
2. **Pairing Decision:** Once a valid pairing is identified, the backend locks the queue entries and creates a temporary session container with a unique connection token.
3. **STUN & Hole-Punch Verification:** Instead of assuming routing immediately, the backend instructs both clients to perform a **3-second UDP hole-punching verification test**. Both clients send high-frequency dummy packets directly to each other's NAT holes.
4. **Token & Key Exchange:** Once the 3-second test verifies a stable direct link, the matchmaker registers the ephemeral WireGuard peer mapping on the virtual interface, issues connection tokens, and removes session traces from memory to preserve its stateless, zero-disk-I/O footprint.