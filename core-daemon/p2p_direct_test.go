package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	"tailscale.com/tsnet"
)

func TestP2PDirectConnection(t *testing.T) {
	authKey := "tskey-auth-kF5BpAzZmA11CNTRL-HYTHfMZwSWh553tYCvFjWhW3tDN3kvyN"
	tmpDir := os.TempDir()

	hostDir := filepath.Join(tmpDir, "ts_test_host_dir")
	guestDir := filepath.Join(tmpDir, "ts_test_guest_dir")
	_ = os.RemoveAll(hostDir)
	_ = os.RemoveAll(guestDir)
	_ = os.MkdirAll(hostDir, 0755)
	_ = os.MkdirAll(guestDir, 0755)
	defer os.RemoveAll(hostDir)
	defer os.RemoveAll(guestDir)

	os.Setenv("TSNET_FORCE_LOGIN", "1")

	// 1. Start Host Node
	hostServer := &tsnet.Server{
		AuthKey:   authKey,
		Dir:       hostDir,
		Hostname:  "test-p2p-host",
		Ephemeral: true,
	}
	defer hostServer.Close()

	if err := hostServer.Start(); err != nil {
		t.Fatalf("Host tsnet Start failed: %v", err)
	}

	ctxUp, cancelUp := context.WithTimeout(context.Background(), 20*time.Second)
	hostStatus, err := hostServer.Up(ctxUp)
	cancelUp()
	if err != nil || hostStatus == nil || len(hostStatus.TailscaleIPs) == 0 {
		t.Fatalf("Host failed to obtain Tailscale IP: %v", err)
	}
	hostIP := hostStatus.TailscaleIPs[0].String()
	t.Logf("=== HOST ONLINE === (IP: %s)", hostIP)

	// Host listens on TCP 25565
	hostListener, err := hostServer.Listen("tcp", ":25565")
	if err != nil {
		t.Fatalf("Host failed to listen on :25565: %v", err)
	}
	defer hostListener.Close()

	// Server goroutine: accept 1 connection and echo data
	hostDone := make(chan bool)
	go func() {
		conn, err := hostListener.Accept()
		if err != nil {
			t.Errorf("Host accept error: %v", err)
			hostDone <- false
			return
		}
		defer conn.Close()

		buf := make([]byte, 128)
		n, err := conn.Read(buf)
		if err != nil {
			t.Errorf("Host read error: %v", err)
			hostDone <- false
			return
		}
		msg := string(buf[:n])
		t.Logf("Host received payload from guest: %s", msg)

		// Echo reply
		_, _ = conn.Write([]byte("P2P_HANDSHAKE_ACK: " + msg))
		hostDone <- true
	}()

	// 2. Start Guest Node
	guestServer := &tsnet.Server{
		AuthKey:   authKey,
		Dir:       guestDir,
		Hostname:  "test-p2p-guest",
		Ephemeral: true,
	}
	defer guestServer.Close()

	if err := guestServer.Start(); err != nil {
		t.Fatalf("Guest tsnet Start failed: %v", err)
	}

	ctxUp2, cancelUp2 := context.WithTimeout(context.Background(), 20*time.Second)
	guestStatus, err := guestServer.Up(ctxUp2)
	cancelUp2()
	if err != nil || guestStatus == nil || len(guestStatus.TailscaleIPs) == 0 {
		t.Fatalf("Guest failed to obtain Tailscale IP: %v", err)
	}
	guestIP := guestStatus.TailscaleIPs[0].String()
	t.Logf("=== GUEST ONLINE === (IP: %s)", guestIP)

	// Guest dials Host IP on 25565
	var guestConn net.Conn
	for attempt := 1; attempt <= 10; attempt++ {
		dialCtx, dialCancel := context.WithTimeout(context.Background(), 5*time.Second)
		guestConn, err = guestServer.Dial(dialCtx, "tcp", net.JoinHostPort(hostIP, "25565"))
		dialCancel()
		if err == nil {
			break
		}
		t.Logf("Dial attempt %d failed: %v. Retrying...", attempt, err)
		time.Sleep(1 * time.Second)
	}
	if err != nil {
		t.Fatalf("Guest failed to dial host %s:25565: %v", hostIP, err)
	}
	defer guestConn.Close()

	// Send handshake payload
	testPayload := fmt.Sprintf("HELLO_FROM_GUEST_%s", guestIP)
	_, err = guestConn.Write([]byte(testPayload))
	if err != nil {
		t.Fatalf("Guest write failed: %v", err)
	}

	replyBuf := make([]byte, 256)
	_ = guestConn.SetReadDeadline(time.Now().Add(10 * time.Second))
	n, err := guestConn.Read(replyBuf)
	if err != nil && err != io.EOF {
		t.Fatalf("Guest read response failed: %v", err)
	}

	reply := string(replyBuf[:n])
	t.Logf("Guest received reply from host: %s", reply)

	expected := "P2P_HANDSHAKE_ACK: " + testPayload
	if reply != expected {
		t.Fatalf("Unexpected reply. Expected '%s', got '%s'", expected, reply)
	}

	<-hostDone
	t.Log("=== DIRECT P2P CONNECTION VERIFICATION PASSED SUCCESSFULLY! ===")
}
