package com.p2ppvp.mod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.p2ppvp.mod.ArenaManager;
import com.p2ppvp.mod.DaemonManager;
import com.p2ppvp.mod.DebugLogger;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

public class P2PPvpModClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("p2p-pvp-client");

    // Post-Match Stat Overlay States
    public static volatile boolean showPostMatchOverlay = false;
    public static volatile boolean redirectingToTitle = false;
    public static volatile long overlayOpenTime = 0;
    public static volatile int lastPlayedTickIndex = -1;
    public static volatile String lastWinner = "";
    public static volatile String lastLoser = "";
    public static volatile String lastKit = "";
    public static volatile int winnerElo = 100;
    public static volatile int loserElo = 100;
    public static volatile int winnerEloChange = 0;
    public static volatile int loserEloChange = 0;
    public static volatile int winnerWins = 0;
    public static volatile int winnerLosses = 0;
    public static volatile int loserWins = 0;
    public static volatile int loserLosses = 0;
    public static volatile int winnerKitElo = 100;
    public static volatile int loserKitElo = 100;
    public static volatile int winnerKitWins = 0;
    public static volatile int winnerKitLosses = 0;
    public static volatile int loserKitWins = 0;
    public static volatile int loserKitLosses = 0;
    public static volatile int winnerRank = 1;
    public static volatile int loserRank = 1;
    public static volatile int winnerKitRank = 1;
    public static volatile int loserKitRank = 1;
    public static volatile long lastReportTime = 0;

    @Override
    public void onInitializeClient() {
        // Reset the log file on game startup so we have a fresh log for each launch
        DebugLogger.resetLog();

        LOGGER.info("MCR: Client environment initializing.");
        // Trigger asynchronous extraction and validation of the pristine void arena
        ArenaManager.initializeArenaCacheAsync();

        // Dynamically extract and spin up the native core-daemon background interface
        DaemonManager.start();

        // Register standard Fabric API Disconnect Event to reset latency and restore the pristine map on match exit
        // Register standard Fabric API Disconnect Event to reset latency and restore the pristine map on match exit
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Match exit detected. Resetting latency and restoring pristine map cache...");
            com.p2ppvp.mod.client.LatencyManager.setActiveDelay(0);
            com.p2ppvp.mod.ArenaManager.initializeArenaCacheAsync();
            com.p2ppvp.mod.DaemonManager.stopPeer();
            // Parse match resolution if present in disconnect reason
            try {
                Connection conn = handler.getConnection();
                if (conn != null) {
                    Component reason = null;
                    try {
                        // 1. Scan methods on Connection
                        for (java.lang.reflect.Method m : conn.getClass().getDeclaredMethods()) {
                            if (m.getParameterCount() == 0 && Component.class.isAssignableFrom(m.getReturnType())) {
                                String mName = m.getName();
                                if (mName.toLowerCase().contains("reason") || mName.toLowerCase().contains("disconnect") || mName.toLowerCase().contains("info") || mName.toLowerCase().contains("desc")) {
                                    m.setAccessible(true);
                                    Component res = (Component) m.invoke(conn);
                                    if (res != null) {
                                        reason = res;
                                        LOGGER.info("[REFLECTION] Found disconnect reason via method: " + mName + " -> " + res.getString());
                                        break;
                                    }
                                }
                            }
                        }
                        // 2. Scan fields on Connection if not found
                        if (reason == null) {
                            for (java.lang.reflect.Field f : conn.getClass().getDeclaredFields()) {
                                if (Component.class.isAssignableFrom(f.getType())) {
                                    f.setAccessible(true);
                                    Component res = (Component) f.get(conn);
                                    if (res != null) {
                                        reason = res;
                                        LOGGER.info("[REFLECTION] Found disconnect reason via field: " + f.getName() + " -> " + res.getString());
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        LOGGER.error("[REFLECTION ERROR] Failed to dynamically find disconnect reason", ex);
                    }
                    if (reason != null) {
                        String text = reason.getString();
                        if (text.startsWith("MATCH_RESOLVED:")) {
                            String[] parts = text.split(":");
                            String winner = null;
                            String loser = null;
                            String kit = null;
                            for (String part : parts) {
                                if (part.startsWith("Winner=")) winner = part.substring(7);
                                if (part.startsWith("Loser=")) loser = part.substring(6);
                                if (part.startsWith("Kit=")) kit = part.substring(4);
                            }
                            if (winner != null && loser != null) {
                                redirectingToTitle = true;
                                reportMatchResult(winner, loser, kit);
                                // Instantly redirect back to TitleScreen to bypass the raw disconnect screen
                                client.execute(() -> {
                                    client.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
                                });
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error parsing match resolution reason on disconnect", e);
            }
        });

        // Register Client Tick Event to handle integrated server auto-publishing safely when client connection is active
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (client.level != null && client.getSingleplayerServer() != null && client.getConnection() != null) {
                    String srvLevel = client.getSingleplayerServer().getWorldData().getLevelName();
                    boolean matches = srvLevel != null && (
                        srvLevel.toLowerCase().contains("pvp") || 
                        srvLevel.toLowerCase().contains("arena") || 
                        srvLevel.toLowerCase().contains("cache")
                    );
                    if (matches && !client.getSingleplayerServer().isPublished()) {
                        com.p2ppvp.mod.DebugLogger.log("[CLIENT] Match world detected and client connection active. Auto-publishing integrated server...");
                        boolean published = client.getSingleplayerServer().publishServer(
                            net.minecraft.world.level.GameType.SURVIVAL, 
                            false, 
                            25565
                        );
                        if (published) {
                            com.p2ppvp.mod.DebugLogger.log("[CLIENT] Successfully auto-published server on port 25565!");
                        } else {
                            com.p2ppvp.mod.DebugLogger.log("[CLIENT] Failed to auto-publish server on port 25565 (maybe port already bound).");
                        }
                    }
                }
            } catch (Exception e) {
                com.p2ppvp.mod.DebugLogger.log("[CLIENT] Error inside ClientTickEvents publish handler", e);
            }
        });
    }

    public static void reportMatchResult(String winner, String loser, String kit) {
        // Deduplication guard: block reports for the same outcome within 10 seconds to resolve backend double-counting
        if (System.currentTimeMillis() - lastReportTime < 10000 && winner.equals(lastWinner) && loser.equals(lastLoser)) {
            com.p2ppvp.mod.DebugLogger.log("[CLIENT_REPORT] Skipping duplicate match report submission.");
            return;
        }
        lastWinner = winner;
        lastLoser = loser;
        lastReportTime = System.currentTimeMillis();

        Thread reportThread = new Thread(() -> {
            try {
                com.p2ppvp.mod.DebugLogger.log("[CLIENT_REPORT] Match completed! Sending independent validation report: Winner=" + winner + ", Loser=" + loser + ", Kit=" + kit);

                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(5))
                        .build();

                String reporter = net.minecraft.client.Minecraft.getInstance().getUser().getName();
                String payload = String.format("{\"winner\": \"%s\", \"loser\": \"%s\", \"kit\": \"%s\", \"reporter\": \"%s\"}", winner, loser, kit != null ? kit : "Crystal", reporter);

                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create("http://127.0.0.1:8000/api/match/report"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                String body = response.body();
                com.p2ppvp.mod.DebugLogger.log("[CLIENT_REPORT] Match report submitted. Status: " + response.statusCode() + " body: " + body);

                if (response.statusCode() == 200) {
                    com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();

                    lastWinner = winner;
                    lastLoser = loser;
                    lastKit = kit != null ? kit : "Crystal";
                    
                    if (obj.has("winner_elo")) winnerElo = obj.get("winner_elo").getAsInt();
                    if (obj.has("loser_elo")) loserElo = obj.get("loser_elo").getAsInt();
                    if (obj.has("winner_elo_change")) winnerEloChange = obj.get("winner_elo_change").getAsInt();
                    if (obj.has("loser_elo_change")) loserEloChange = obj.get("loser_elo_change").getAsInt();
                    if (obj.has("winner_wins")) winnerWins = obj.get("winner_wins").getAsInt();
                    if (obj.has("winner_losses")) winnerLosses = obj.get("winner_losses").getAsInt();
                    if (obj.has("loser_wins")) loserWins = obj.get("loser_wins").getAsInt();
                    if (obj.has("loser_losses")) loserLosses = obj.get("loser_losses").getAsInt();

                    if (obj.has("winner_kit_elo")) winnerKitElo = obj.get("winner_kit_elo").getAsInt();
                    if (obj.has("loser_kit_elo")) loserKitElo = obj.get("loser_kit_elo").getAsInt();
                    if (obj.has("winner_kit_wins")) winnerKitWins = obj.get("winner_kit_wins").getAsInt();
                    if (obj.has("winner_kit_losses")) winnerKitLosses = obj.get("winner_kit_losses").getAsInt();
                    if (obj.has("loser_kit_wins")) loserKitWins = obj.get("loser_kit_wins").getAsInt();
                    if (obj.has("loser_kit_losses")) loserKitLosses = obj.get("loser_kit_losses").getAsInt();
                    
                    if (obj.has("winner_rank")) winnerRank = obj.get("winner_rank").getAsInt();
                    if (obj.has("loser_rank")) loserRank = obj.get("loser_rank").getAsInt();
                    if (obj.has("winner_kit_rank")) winnerKitRank = obj.get("winner_kit_rank").getAsInt();
                    if (obj.has("loser_kit_rank")) loserKitRank = obj.get("loser_kit_rank").getAsInt();
                    
                    lastPlayedTickIndex = -1;
                    overlayOpenTime = System.currentTimeMillis();
                    showPostMatchOverlay = true;
                }
            } catch (Exception e) {
                com.p2ppvp.mod.DebugLogger.log("[CLIENT_REPORT] Failed to report match result: " + e.getMessage());
            }
        });
        reportThread.setDaemon(true);
        reportThread.start();
    }

    private static String extractJSONNumericValue(String json, String key) {
        try {
            int index = json.indexOf("\"" + key + "\"");
            if (index == -1) return "";
            int start = json.indexOf(":", index) + 1;
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            return json.substring(start, end).trim().replace("\"", "");
        } catch (Exception e) {
            return "";
        }
    }
}