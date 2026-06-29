package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"log"
	"os"
	"path/filepath"
	"unsafe"
)

//export StartDaemon
func StartDaemon(cAuthKey, cStateDir *C.char) {
	authKey := C.GoString(cAuthKey)
	stateDir := C.GoString(cStateDir)

	mu.Lock()
	if tsServer != nil {
		mu.Unlock()
		log.Println("[Go Bridge] Daemon already started.")
		return
	}
	mu.Unlock()

	log.Println("[Go Bridge] Starting P2P PvP Core Daemon (Native Shared Library Mode)...")

	config := Config{
		AuthKey:      authKey,
		StateDir:     stateDir,
		Hostname:     "p2p-pvp-client",
		LocalIPCPort: 5005,
		RemotePort:   25565,
		LocalPort:    25565,
	}

	if config.StateDir == "" {
		cacheDir, _ := os.UserCacheDir()
		config.StateDir = filepath.Join(cacheDir, "P2P_PvP_Daemon_State")
	}

	initLogging()

	if err := startTsServer(config); err != nil {
		log.Printf("[Go Bridge] Failed to start user-space network stack: %v\n", err)
		updateStatus("START_FAILED")
		return
	}

	go startMatchmakerProxy()
}

//export GetStatus
func GetStatus() *C.char {
	if !isDirectConnectionPossible() {
		return C.CString("ERR_DIRECT_CONN_FAILED")
	}
	mu.Lock()
	s := status
	mu.Unlock()
	return C.CString(s)
}

//export FreeString
func FreeString(s *C.char) {
	C.free(unsafe.Pointer(s))
}

//export GetIP
func GetIP() *C.char {
	mu.Lock()
	ip := localIP
	mu.Unlock()
	return C.CString(ip)
}

//export SetRemotePeer
func SetRemotePeer(cPeerIP *C.char) {
	peerIP := C.GoString(cPeerIP)
	if peerIP != "" {
		peerMu.Lock()
		activePeerIP = peerIP
		peerMu.Unlock()
		log.Printf("[Go Bridge] Setting remote peer to %s. Launching Ghost Listeners...\n", peerIP)
		go startLocalGhostTCPListener(peerIP)
		go startLocalGhostUDPListener(peerIP)
	}
}

//export StopPeer
func StopPeer() {
	log.Println("[Go Bridge] Stopping remote peer listeners...")
	stopGhostListeners()
	peerMu.Lock()
	activePeerIP = ""
	peerMu.Unlock()
}

//export StopDaemon
func StopDaemon() {
	log.Println("[Go Bridge] Stopping Daemon and listeners...")
	stopGhostListeners()
	cleanup()
	mu.Lock()
	tsServer = nil
	ready = false
	status = "INITIALIZING"
	localIP = "127.0.0.1"
	mu.Unlock()
}
