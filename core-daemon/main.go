package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"tailscale.com/tsnet"
)

// Config holds runtime configuration passed via flags or env variables.
type Config struct {
	AuthKey      string
	Hostname     string
	StateDir     string
	LocalIPCPort int
	RemotePeerIP string
	RemotePort   int
	LocalPort    int
}

var (
	tsServer       *tsnet.Server
	ready          bool
	status         string = "INITIALIZING"
	localIP        string = "127.0.0.1"
	mu             sync.Mutex
	activePeerIP   string
	peerMu         sync.Mutex
	localTCPCancel func()
	localUDPCancel func()
)

func main() {
	config := parseFlags()

	// Initialize log output
	initLogging()
	log.Println("[Daemon] Starting P2P PvP Core Daemon...")

	// Crash-proof process monitoring:
	// Listen on standard input. When Minecraft exits or crashes, the OS tears down the process tree,
	// closing our stdin pipe. os.Stdin.Read unblocks with EOF, allowing us to exit immediately.
	go func() {
		buf := make([ ]byte, 1)
		for {
			_, err := os.Stdin.Read(buf)
			if err != nil {
				log.Println("[Daemon] Parent process stdin closed (Minecraft closed). Shutting down instantly...")
				cleanup()
				os.Exit(0)
			}
		}
	}()
	// Create and start tailscale tsnet server
	if err := startTsServer(config); err != nil {
		log.Fatalf("[Daemon] Failed to start user-space network stack: %v", err)
	}

	// Start matchmaking loopback proxy on port 8000
	go startMatchmakerProxy()

	// Set up shutdown hooks
	shutdownCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Spin up IPC listener (Local loopback communication with Fabric Mod)
	go startLocalIPC(config, shutdownCtx)

	// Block until signal is received
	<-shutdownCtx.Done()
	log.Println("[Daemon] Shutting down Core Daemon cleanly...")
	cleanup()
}

func parseFlags() Config {
	var c Config
	flag.StringVar(&c.AuthKey, "authkey", "tskey-auth-kchBYH2QAe11CNTRL-zcR3hH3g4DESZncG4TseCE9ZXj3EWocsQ", "Unified Tailscale Auth Key for P2P PvP matching")
	flag.StringVar(&c.Hostname, "hostname", "p2p-pvp-client", "Virtual node hostname")
	flag.StringVar(&c.StateDir, "statedir", "", "Path to store Tailscale state")
	flag.IntVar(&c.LocalIPCPort, "ipc-port", 5005, "Local port for Java-Go loopback IPC")
	flag.StringVar(&c.RemotePeerIP, "remote-ip", "", "Tailscale IP of remote PvP opponent")
	flag.IntVar(&c.RemotePort, "remote-port", 25565, "Remote target port on opponent")
	flag.IntVar(&c.LocalPort, "local-port", 25565, "Local proxy port for client loopback binding")
	flag.Parse()

	// If TS_AUTHKEY is set in environment, allow override, otherwise enforce primary key
	if os.Getenv("TS_AUTHKEY") != "" {
		c.AuthKey = os.Getenv("TS_AUTHKEY")
	}
	if c.StateDir == "" {
		cacheDir, _ := os.UserCacheDir()
		c.StateDir = filepath.Join(cacheDir, "P2P_PvP_Daemon_State")
	}
	return c
}

func initLogging() {
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds | log.Lshortfile)
	log.SetOutput(os.Stderr)
}

func startTsServer(c Config) error {
	if err := os.MkdirAll(c.StateDir, 0755); err != nil {
		return fmt.Errorf("failed to create state directory: %w", err)
	}

	// Ensure force login is set to prevent tsnet hanging on unauthenticated nodes
	os.Setenv("TSNET_FORCE_LOGIN", "1")

	tsServer = &tsnet.Server{
		AuthKey:   c.AuthKey,
		Dir:       c.StateDir,
		Hostname:  c.Hostname,
		Logf:      log.Printf,
		Ephemeral: true, // Ephemeral node, clean state on exit
	}

	log.Printf("[Daemon] Initializing tsnet stack. Hostname: %s, State: %s\n", c.Hostname, c.StateDir)

	go func() {
		if err := tsServer.Start(); err != nil {
			updateStatus("START_FAILED")
			log.Printf("[Daemon] tsnet Start failed: %v\n", err)
			return
		}

		// Wait for Tailscale to bring up the interface and obtain an IP
		for {
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			st, err := tsServer.Up(ctx)
			cancel()
			if err == nil && st != nil {
				mu.Lock()
				ready = true
				status = "STABLE"
				if len(st.TailscaleIPs) > 0 {
					localIP = st.TailscaleIPs[0].String()
				}
				mu.Unlock()
				log.Printf("[Daemon] >>> USER-SPACE P2P INTERFACE ONLINE (IP: %s) <<<\n", localIP)

				// Start the incoming Tailscale listeners (For Host role)
				go startTailscaleTCPListener()
				go startTailscaleUDPListener()
				break
			}
			log.Println("[Daemon] Awaiting P2P node coordination and IP address...")
			time.Sleep(3 * time.Second)
		}
	}()

	return nil
}

func updateStatus(s string) {
	mu.Lock()
	defer mu.Unlock()
	status = s
}

// startLocalIPC initializes a sub-millisecond local loopback socket for Fabric-bridge coordination.
func startLocalIPC(c Config, ctx context.Context) {
	addr := fmt.Sprintf("127.0.0.1:%d", c.LocalIPCPort)
	l, err := net.Listen("tcp", addr)
	if err != nil {
		log.Printf("[Daemon] IPC listener failed to bind to %s: %v\n", addr, err)
		return
	}
	defer l.Close()
	log.Printf("[Daemon] Local IPC Layer active on %s (TCP Loopback)\n", addr)

	// Keep-alive or request handling loop
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			default:
				conn, err := l.Accept()
				if err != nil {
					continue
				}
				go handleIPCClient(conn, c, ctx)
			}
		}
	}()

	<-ctx.Done()
}

func handleIPCClient(conn net.Conn, c Config, ctx context.Context) {
	defer conn.Close()
	buf := make([ ]byte, 1024)

	for {
		select {
		case <-ctx.Done():
			return
		default:
			conn.SetReadDeadline(time.Now().Add(5 * time.Second))
			n, err := conn.Read(buf)
			if err != nil {
				return
			}

			payload := string(buf[:n])
			response := processIPCPayload(payload, c, ctx)

			conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
			if _, err := conn.Write([ ]byte(response + "\n")); err != nil {
				return
			}
		}
	}
}

func isDirectConnectionPossible() bool {
	if tsServer == nil {
		return false
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	st, err := tsServer.Up(ctx)
	if err != nil || st == nil {
		return false
	}
	for _, h := range st.Health {
		hLower := strings.ToLower(h)
		if (strings.Contains(hLower, "udp") && strings.Contains(hLower, "blocked")) || strings.Contains(hLower, "derp") {
			return false
		}
	}
	return true
}

func stopGhostListeners() {
	tcpListenerMu.Lock()
	if activeTCPListener != nil {
		activeTCPListener.Close()
		activeTCPListener = nil
		tcpListenerStarted = false
		log.Println("[Daemon] Ghost TCP Listener stopped, port 25565 freed.")
	}
	tcpListenerMu.Unlock()

	udpListenerMu.Lock()
	if activeUDPConn != nil {
		activeUDPConn.Close()
		activeUDPConn = nil
		udpListenerStarted = false
		log.Println("[Daemon] Ghost UDP Listener stopped, port 24454 freed.")
	}
	udpListenerMu.Unlock()
}

func processIPCPayload(payload string, c Config, ctx context.Context) string {
	payload = strings.TrimSpace(payload)
	if strings.HasPrefix(payload, "SET_PEER ") {
		peerIP := strings.TrimSpace(strings.TrimPrefix(payload, "SET_PEER "))
		if peerIP != "" {
			peerMu.Lock()
			activePeerIP = peerIP
			peerMu.Unlock()
			log.Printf("[Daemon] Setting remote peer to %s. Launching Ghost Listeners...\n", peerIP)
			go startLocalGhostTCPListener(peerIP)
			go startLocalGhostUDPListener(peerIP)
			return "OK"
		}
		return "ERR_INVALID_IP"
	}

	switch payload {
	case "STOP_PEER":
		stopGhostListeners()
		peerMu.Lock()
		activePeerIP = ""
		peerMu.Unlock()
		return "OK"
	case "GET_STATUS":
		if !isDirectConnectionPossible() {
			return "ERR_DIRECT_CONN_FAILED"
		}
		mu.Lock()
		s := status
		mu.Unlock()
		return s
	case "IS_READY":
		if !isDirectConnectionPossible() {
			return "ERR_DIRECT_CONN_FAILED"
		}
		mu.Lock()
		r := ready
		mu.Unlock()
		if r {
			return "1"
		}
		return "0"
		mu.Lock()
		ip := localIP
		mu.Unlock()
		return ip
	default:
		return "OK"
	}
}

// startTailscaleTCPListener acts on the Host, listening on the virtual Tailscale IP and proxying to local server.
func startTailscaleTCPListener() {
	for {
		mu.Lock()
		r := ready
		mu.Unlock()
		if r {
			break
		}
		time.Sleep(1 * time.Second)
	}

	l, err := tsServer.Listen("tcp", ":25565")
	if err != nil {
		log.Printf("[Daemon] Failed to listen on Tailscale port 25565: %v\n", err)
		return
	}
	defer l.Close()
	log.Println("[Daemon] Tailscale TCP Listener active on port 25565")

	for {
		conn, err := l.Accept()
		if err != nil {
			continue
		}
		go func(tsConn net.Conn) {
			defer tsConn.Close()

			var localConn net.Conn

			// Robust retry loop to handle slow singleplayer integrated server world loads
			for i := 0; i < 30; i++ {
				localConn, err = net.Dial("tcp", "127.0.0.1:25565")
				if err == nil {
					break
				}
				log.Printf("[Daemon] Local Minecraft server not ready yet. Retrying dial... (Attempt %d/30)\n", i+1)
				time.Sleep(500 * time.Millisecond)
			}

			if err != nil {
				log.Printf("[Daemon] Failed to dial local Minecraft server after retries: %v\n", err)
				return
			}
			defer localConn.Close()

			done := make(chan struct{}, 2)
			go func() { _, _ = io.Copy(localConn, tsConn); done <- struct{}{} }()
			go func() { _, _ = io.Copy(tsConn, localConn); done <- struct{}{} }()
			<-done
		}(conn)
	}
}

// startTailscaleUDPListener acts on the Host, listening on the virtual Tailscale IP and proxying to local server.
func startTailscaleUDPListener() {
	for {
		mu.Lock()
		r := ready
		mu.Unlock()
		if r {
			break
		}
		time.Sleep(1 * time.Second)
	}

	mu.Lock()
	ip := localIP
	mu.Unlock()

	lConn, err := tsServer.ListenPacket("udp", fmt.Sprintf("%s:%d", ip, 24454))
	if err != nil {
		log.Printf("[Daemon] Failed to listen on Tailscale UDP port 24454: %v\n", err)
		return
	}
	defer lConn.Close()
	log.Printf("[Daemon] Tailscale UDP Listener active on %s:24454\n", ip)

	localAddr, _ := net.ResolveUDPAddr("udp", "127.0.0.1:24454")
	localUDPConn, err := net.DialUDP("udp", nil, localAddr)
	if err != nil {
		log.Printf("[Daemon] Failed to dial local UDP port 24454: %v\n", err)
		return
	}
	defer localUDPConn.Close()

	buf := make([ ]byte, 4096)
	for {
		n, _, err := lConn.ReadFrom(buf)
		if err != nil {
			continue
		}
		_, _ = localUDPConn.Write(buf[:n])
	}
}

// startMatchmakerProxy creates a local TCP proxy on 127.0.0.1:8000 to tunnel matchmaking HTTP queries over Tailscale.
func startMatchmakerProxy() {
	addr := "127.0.0.1:8000"
	l, err := net.Listen("tcp", addr)
	if err != nil {
		log.Printf("[Daemon] Failed to bind local matchmaking proxy on %s: %v\n", addr, err)
		return
	}
	defer l.Close()
	log.Printf("[Daemon] Matchmaking Proxy active on %s -> Proxying to 100.120.244.95:8000 over Tailscale\n", addr)

	for {
		conn, err := l.Accept()
		if err != nil {
			continue
		}
		go func(localConn net.Conn) {
			defer localConn.Close()
			for i := 0; i < 30; i++ {
				mu.Lock()
				r := ready
				mu.Unlock()
				if r {
					break
				}
				time.Sleep(500 * time.Millisecond)
			}

			dialCtx, dialCancel := context.WithTimeout(context.Background(), 10*time.Second)
			tsConn, err := tsServer.Dial(dialCtx, "tcp", "100.120.244.95:8000")
			dialCancel()
			if err != nil {
				log.Printf("[Daemon] Proxy failed to dial remote matchmaker: %v\n", err)
				return
			}
			defer tsConn.Close()

			done := make(chan struct{}, 2)
			go func() { _, _ = io.Copy(tsConn, localConn); done <- struct{}{} }()
			go func() { _, _ = io.Copy(localConn, tsConn); done <- struct{}{} }()
			<-done
		}(conn)
	}
}

var (
	tcpListenerStarted bool
	tcpListenerMu      sync.Mutex
	activeTCPListener  net.Listener

	udpListenerStarted bool
	udpListenerMu      sync.Mutex
	activeUDPConn      *net.UDPConn
)

// startLocalGhostTCPListener acts on the Guest, listening locally on 127.0.0.1:25565 and proxying to Host.
func startLocalGhostTCPListener(peerIP string) {
	tcpListenerMu.Lock()
	if tcpListenerStarted {
		log.Printf("[Daemon] Ghost TCP Listener is already active on port 25565, pointing to current peer: %s\n", peerIP)
		tcpListenerMu.Unlock()
		return
	}
	tcpListenerStarted = true
	tcpListenerMu.Unlock()

	addr := "127.0.0.1:25565"
	l, err := net.Listen("tcp", addr)
	if err != nil {
		log.Printf("[Daemon] Failed to bind local ghost TCP listener on %s: %v\n", addr, err)
		tcpListenerMu.Lock()
		tcpListenerStarted = false
		tcpListenerMu.Unlock()
		return
	}
	activeTCPListener = l
	defer func() {
		tcpListenerMu.Lock()
		activeTCPListener = nil
		tcpListenerStarted = false
		tcpListenerMu.Unlock()
		l.Close()
	}()
	log.Printf("[Daemon] Local Ghost TCP Listener permanently active on %s\n", addr)

	for {
		conn, err := l.Accept()
		if err != nil {
			tcpListenerMu.Lock()
			r := tcpListenerStarted
			tcpListenerMu.Unlock()
			if !r {
				// Listener was closed intentionally, exit loop cleanly
				return
			}
			continue
		}
		go func(localConn net.Conn) {
			defer localConn.Close() // Safe proxy connection
			for i := 0; i < 30; i++ {
				mu.Lock()
				r := ready
				mu.Unlock()
				if r {
					break
				}
				time.Sleep(500 * time.Millisecond)
			}

			// Read latest active peer IP dynamically
			peerMu.Lock()
			currentPeer := activePeerIP
			peerMu.Unlock()

			if currentPeer == "" {
				log.Println("[Daemon] Ghost proxy received TCP connection but current activePeerIP is empty!")
				return
			}

			var remoteConn net.Conn

			// Retry loop to handle Host-side startup lag and virtual interface initialization
			for i := 0; i < 30; i++ {
				ctxDial, cancelDial := context.WithTimeout(context.Background(), 3*time.Second)
				remoteConn, err = tsServer.Dial(ctxDial, "tcp", fmt.Sprintf("%s:%d", currentPeer, 25565))
				cancelDial()
				if err == nil {
					break
				}
				log.Printf("[Daemon] Host virtual interface not ready yet. Retrying dial... (Attempt %d/30)\n", i+1)
				time.Sleep(500 * time.Millisecond)
			}

			if err != nil {
				log.Printf("[Daemon] Failed to dial peer over Tailscale after retries: %v\n", err)
				return
			}
			defer remoteConn.Close()

			done := make(chan struct{}, 2)
			go func() { _, _ = io.Copy(remoteConn, localConn); done <- struct{}{} }()
			go func() { _, _ = io.Copy(localConn, remoteConn); done <- struct{}{} }()
			<-done
		}(conn)
	}
}

// startLocalGhostUDPListener acts on the Guest, listening locally on 127.0.0.1:24454 and proxying to Host.
func startLocalGhostUDPListener(peerIP string) {
	udpListenerMu.Lock()
	if udpListenerStarted {
		log.Printf("[Daemon] Ghost UDP Listener is already active on port 24454, pointing to current peer: %s\n", peerIP)
		udpListenerMu.Unlock()
		return
	}
	udpListenerStarted = true
	udpListenerMu.Unlock()

	addr, _ := net.ResolveUDPAddr("udp", "127.0.0.1:24454")
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		log.Printf("[Daemon] Failed to bind local ghost UDP listener: %v\n", err)
		udpListenerMu.Lock()
		udpListenerStarted = false
		udpListenerMu.Unlock()
		return
	}
	activeUDPConn = conn
	defer func() {
		udpListenerMu.Lock()
		activeUDPConn = nil
		udpListenerStarted = false
		udpListenerMu.Unlock()
		conn.Close()
	}()
	log.Printf("[Daemon] Local Ghost UDP Listener permanently active on 127.0.0.1:24454\n")

	var remoteConn net.Conn
	var rmu sync.Mutex
	buf := make([ ]byte, 4096)

	for {
		conn.SetReadDeadline(time.Now().Add(1 * time.Second))
		n, _, err := conn.ReadFromUDP(buf)
		if err != nil {
			udpListenerMu.Lock()
			r := udpListenerStarted
			udpListenerMu.Unlock()
			if !r {
				// Listener was closed intentionally, exit loop cleanly
				return
			}
			continue
		}

		// Read latest active peer IP dynamically
		peerMu.Lock()
		currentPeer := activePeerIP
		peerMu.Unlock()

		if currentPeer == "" {
			continue
		}

		rmu.Lock()
		if remoteConn == nil || remoteConn.RemoteAddr().String() != currentPeer+":24454" {
			if remoteConn != nil {
				remoteConn.Close()
			}
			dialCtx, dialCancel := context.WithTimeout(context.Background(), 10*time.Second)
			remoteConn, err = tsServer.Dial(dialCtx, "udp", currentPeer+":24454")
			dialCancel()
			if err != nil {
				remoteConn = nil
				rmu.Unlock()
				continue
			}
			go func(rc net.Conn) {
				defer rc.Close()
				rBuf := make([ ]byte, 4096)
				for {
					rn, err := rc.Read(rBuf)
					if err != nil {
						rmu.Lock()
						if remoteConn == rc {
							remoteConn = nil
						}
						rmu.Unlock()
						return
					}
					_, _ = conn.Write(rBuf[:rn])
				}
			}(remoteConn)
		}
		_, _ = remoteConn.Write(buf[:n])
		rmu.Unlock()
	}
}

func cleanup() {
	if tsServer != nil {
		tsServer.Close()
	}
}
