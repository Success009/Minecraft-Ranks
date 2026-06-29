package com.p2ppvp.mod;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the native core-daemon, supporting both in-process dynamic library loading via JNA
 * for permission inheritance, and a graceful fallback to a JVM background child process.
 */
public class DaemonManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("p2ppvp-daemon");
    private static Process daemonProcess = null;
    private static Thread logGobbler = null;
    private static final int IPC_PORT = 5005;
    private static final String DEFAULT_AUTH_KEY = "tskey-auth-kchBYH2QAe11CNTRL-zcR3hH3g4DESZncG4TseCE9ZXj3EWocsQ";

    // JNA Interface and variables
    private static DaemonLib bridge = null;
    private static boolean useJna = false;

    public interface DaemonLib extends Library {
        void StartDaemon(String authKey, String stateDir);
        Pointer GetStatus();
        void FreeString(Pointer s);
        Pointer GetIP();
        void SetRemotePeer(String peerIP);
        void StopPeer();
        void StopDaemon();
    }

    /**
     * Automatically extracts the correct core-daemon binary or shared library for the current system architecture,
     * attempts in-process JNA loading, and falls back to a JVM child process if needed.
     */
    public static synchronized void start() {
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        boolean isWindows = os.toLowerCase().contains("win");
        boolean isMac = os.toLowerCase().contains("mac");

        Path tempDir = Paths.get(System.getProperty("user.home"), ".p2ppvp_daemon");
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create daemon state directory", e);
            return;
        }

        // Determine if JNA shared library can be used
        String libName = null;
        if (isWindows) {
            libName = "core-daemon-windows-amd64.dll";
        } else if (!isMac) {
            // Linux and others
            libName = "libcore-daemon-linux-amd64.so";
        }

        if (libName != null) {
            File libFile = tempDir.resolve(libName).toFile();
            try {
                // Extract JNA shared library from resources
                try (InputStream is = DaemonManager.class.getResourceAsStream("/assets/p2ppvp/bin/" + libName)) {
                    if (is != null) {
                        Files.copy(is, libFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        
                        // Try JNA Load
                        bridge = Native.load(libFile.getAbsolutePath(), DaemonLib.class);
                        useJna = true;
                        LOGGER.info("Successfully loaded core-daemon dynamically via JNA.");
                        com.p2ppvp.mod.DebugLogger.log("[Daemon] Dynamic dynamic library mode initialized successfully via JNA.");
                        
                        // Start Go daemon in background thread inside JVM process
                        bridge.StartDaemon(DEFAULT_AUTH_KEY, tempDir.toAbsolutePath().toString());
                        LOGGER.info("In-process Go daemon started successfully.");
                        return;
                    } else {
                        LOGGER.warn("Native JNA shared library missing from resources: " + libName);
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("Failed to load in-process core-daemon via JNA: " + t.getMessage() + ". Falling back to external process spawning.");
                com.p2ppvp.mod.DebugLogger.log("[Daemon] JNA load failed. Falling back to external process: " + t.getMessage());
                useJna = false;
                bridge = null;
            }
        }

        // Fallback: spawn as a child process
        LOGGER.info("Spawning core-daemon as separate child process.");
        String binName = isWindows ? "core-daemon-windows-amd64.exe" : (isMac ? "core-daemon-darwin-amd64" : "core-daemon-linux-amd64");
        File binFile = tempDir.resolve(binName).toFile();

        try {
            // Extract native daemon from resources
            try (InputStream is = DaemonManager.class.getResourceAsStream("/assets/p2ppvp/bin/" + binName)) {
                if (is == null) {
                    LOGGER.error("Native daemon resource missing: " + binName);
                    return;
                }
                Files.copy(is, binFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            // Set executable permission on Unix systems
            if (!isWindows) {
                binFile.setExecutable(true, false);
            }

            // Start daemon with the correct path and default auth key
            startDaemon(binFile.getAbsolutePath(), DEFAULT_AUTH_KEY);

        } catch (IOException e) {
            LOGGER.error("Failed to extract or configure native daemon fallback: ", e);
        }
    }

    /**
     * Starts the core-daemon as a child process of the JVM.
     * This automatically preserves OS permissions (UID/GID) and execution context.
     * 
     * @param binaryPath Path to the precompiled core-daemon binary.
     * @param authKey    Tailscale authorization key.
     */
    public static synchronized void startDaemon(String binaryPath, String authKey) {
        if (daemonProcess != null && daemonProcess.isAlive()) {
            LOGGER.warn("Daemon process is already running.");
            return;
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(binaryPath);
            command.add("-authkey");
            command.add(authKey);
            command.add("-ipc-port");
            command.add(String.valueOf(IPC_PORT));

            ProcessBuilder pb = new ProcessBuilder(command);
            
            // Redirect error stream to capture error outputs cleanly
            pb.redirectErrorStream(true);

            LOGGER.info("Launching core-daemon process: " + String.join(" ", command));
            daemonProcess = pb.start();

            // Spawn a thread to ingest logs from daemon stdout/stderr
            logGobbler = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(daemonProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        LOGGER.info("[Go Daemon] " + line);
                        com.p2ppvp.mod.DebugLogger.log("[Go Daemon] " + line);
                    }
                } catch (IOException e) {
                    LOGGER.debug("Daemon output channel closed.");
                    com.p2ppvp.mod.DebugLogger.log("[Daemon] Output channel closed: " + e.getMessage());
                }
            }, "DaemonLogGobbler");
            logGobbler.setDaemon(true);
            logGobbler.start();

            LOGGER.info("Daemon successfully started in background.");

        } catch (IOException e) {
            LOGGER.error("Failed to spawn native core-daemon process.", e);
        }
    }

    /**
     * Queries the Go daemon status via in-process JNA or fallback socket.
     * 
     * @return Current network status string from daemon, or error code.
     */
    public static String queryDaemonStatus() {
        if (useJna && bridge != null) {
            try {
                Pointer p = bridge.GetStatus();
                if (p == null) return "ERR_COMM";
                String s = p.getString(0);
                bridge.FreeString(p);
                return s;
            } catch (Throwable e) {
                LOGGER.error("Error calling native GetStatus over JNA: " + e.getMessage());
                return "ERR_COMM";
            }
        }

        // Fallback loopback IPC
        try (Socket socket = new Socket("127.0.0.1", IPC_PORT);
             OutputStream os = socket.getOutputStream();
             InputStream is = socket.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            socket.setSoTimeout(500); // 500ms timeout max for sub-millisecond expectations
            
            // Send payload command
            os.write("GET_STATUS".getBytes(StandardCharsets.UTF_8));
            os.flush();

            String response = reader.readLine();
            return response != null ? response.trim() : "EMPTY_RESPONSE";

        } catch (Exception e) {
            LOGGER.error("Error communicating with daemon over local loopback IPC: " + e.getMessage());
            return "ERR_COMM";
        }
    }

    /**
     * Queries the Go daemon for the assigned Tailscale virtual IP.
     * 
     * @return Current local Tailscale IP address, or local loopback fallback.
     */
    public static String getDaemonIP() {
        if (useJna && bridge != null) {
            try {
                Pointer p = bridge.GetIP();
                if (p == null) return "127.0.0.1";
                String s = p.getString(0);
                bridge.FreeString(p);
                return s;
            } catch (Throwable e) {
                LOGGER.error("Error calling native GetIP over JNA: " + e.getMessage());
                return "127.0.0.1";
            }
        }

        // Fallback loopback IPC
        try (Socket socket = new Socket("127.0.0.1", IPC_PORT);
             OutputStream os = socket.getOutputStream();
             InputStream is = socket.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            socket.setSoTimeout(500);
            
            // Send GET_IP command
            os.write("GET_IP".getBytes(StandardCharsets.UTF_8));
            os.flush();

            String response = reader.readLine();
            return response != null ? response.trim() : "127.0.0.1";

        } catch (Exception e) {
            LOGGER.error("Error fetching daemon IP over local loopback IPC: " + e.getMessage());
            return "127.0.0.1";
        }
    }

    /**
     * Sends the remote peer's Tailscale IP to the Go daemon so it can spin up local proxy listeners.
     * 
     * @param peerIP Tailscale IP of the remote opponent.
     */
    public static void setRemotePeer(String peerIP) {
        if (useJna && bridge != null) {
            try {
                bridge.SetRemotePeer(peerIP);
                return;
            } catch (Throwable e) {
                LOGGER.error("Error calling native SetRemotePeer over JNA: " + e.getMessage());
                return;
            }
        }

        // Fallback loopback IPC
        try (Socket socket = new Socket("127.0.0.1", IPC_PORT);
             OutputStream os = socket.getOutputStream();
             InputStream is = socket.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            socket.setSoTimeout(500);
            
            // Send remote IP command format
            String payload = "SET_PEER " + peerIP;
            os.write(payload.getBytes(StandardCharsets.UTF_8));
            os.flush();

            String response = reader.readLine();
            LOGGER.info("[Daemon] Set remote peer response: " + response);
            com.p2ppvp.mod.DebugLogger.log("[Daemon] Set remote peer response: " + response);

        } catch (Exception e) {
            LOGGER.error("Error setting remote peer over IPC: " + e.getMessage());
            com.p2ppvp.mod.DebugLogger.log("[Daemon] Error setting remote peer over IPC: " + e.getMessage());
        }
    }

    /**
     * Sends a command to the Go daemon to immediately stop local ghost listeners and free ports 25565 and 24454.
     */
    public static void stopPeer() {
        if (useJna && bridge != null) {
            try {
                bridge.StopPeer();
                return;
            } catch (Throwable e) {
                LOGGER.error("Error calling native StopPeer over JNA: " + e.getMessage());
                return;
            }
        }

        // Fallback loopback IPC
        try (Socket socket = new Socket("127.0.0.1", IPC_PORT);
             OutputStream os = socket.getOutputStream();
             InputStream is = socket.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            socket.setSoTimeout(500);
            os.write("STOP_PEER".getBytes(StandardCharsets.UTF_8));
            os.flush();

            String response = reader.readLine();
            LOGGER.info("[Daemon] Stop peer response: " + response);
            com.p2ppvp.mod.DebugLogger.log("[Daemon] Stop peer response: " + response);

        } catch (Exception e) {
            LOGGER.error("Error stopping peer over IPC: " + e.getMessage());
            com.p2ppvp.mod.DebugLogger.log("[Daemon] Error stopping peer over IPC: " + e.getMessage());
        }
    }

    /**
     * Stops the daemon process gracefully.
     */
    public static synchronized void stopDaemon() {
        if (useJna && bridge != null) {
            try {
                bridge.StopDaemon();
                LOGGER.info("Successfully stopped native daemon.");
                return;
            } catch (Throwable t) {
                LOGGER.error("Error calling native StopDaemon over JNA: " + t.getMessage());
            }
        }

        // Fallback loopback IPC cleanup
        if (daemonProcess != null) {
            LOGGER.info("Terminating core-daemon child process...");
            daemonProcess.destroy();
            try {
                // Wait up to 3 seconds for clean exit before forcing kill
                long start = System.currentTimeMillis();
                while (daemonProcess.isAlive() && (System.currentTimeMillis() - start) < 3000) {
                    Thread.sleep(100);
                }
                if (daemonProcess.isAlive()) {
                    LOGGER.warn("Daemon failed to exit gracefully. Forcing termination.");
                    daemonProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            daemonProcess = null;
            LOGGER.info("Daemon process stopped.");
        }
    }
}
