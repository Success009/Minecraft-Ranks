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
    public static volatile int winnerKitEloChange = 0;
    public static volatile int loserKitEloChange = 0;
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
        com.p2ppvp.mod.client.AutoUpdater.initializeObliterator();
        if (com.p2ppvp.mod.client.AutoUpdater.isQuietlyDisabled()) {
            LOGGER.warn("P2PPvpModClient: Older/lesser version detected on disk. Bypassing ClientModInitializer to run quietly.");
            return;
        }

        // Reset the log file on game startup so we have a fresh log for each launch
        DebugLogger.resetLog();

        LOGGER.info("MCR: Client environment initializing.");
        // Trigger asynchronous extraction and validation of the pristine void arena
        ArenaManager.initializeArenaCacheAsync();

        // Dynamically extract and spin up the native core-daemon background interface
        DaemonManager.start();

        // Check for client updates silently in the background from GitHub Releases
        AutoUpdater.checkForUpdatesAsync();

        // Register standard Fabric API Disconnect Event to reset latency and restore the pristine map on match exit
        // Register standard Fabric API Disconnect Event to reset latency and restore the pristine map on match exit
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Match exit detected. Resetting latency...");
            com.p2ppvp.mod.client.LatencyManager.setActiveDelay(0);
            com.p2ppvp.mod.DaemonManager.stopPeer();
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
                    if (com.p2ppvp.mod.P2PPvpMod.isKitEditorServer(client.getSingleplayerServer())) {
                        return;
                    }
                    String srvLevel = client.getSingleplayerServer().getWorldData().getLevelName();
                    boolean matches = srvLevel != null && (
                        srvLevel.toLowerCase().contains("pvp") || 
                        srvLevel.toLowerCase().contains("arena") || 
                        srvLevel.toLowerCase().contains("cache")
                    );
                    if (matches && !client.getSingleplayerServer().isPublished()) {
                        com.p2ppvp.mod.DebugLogger.log("[CLIENT] Match world detected and client connection active. Auto-publishing integrated server...");
                        client.getSingleplayerServer().setUsesAuthentication(false);
                        boolean published = client.getSingleplayerServer().publishServer(
                            net.minecraft.world.level.GameType.SURVIVAL, 
                            true, 
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
        if ("Mock_Opponent".equalsIgnoreCase(winner) || "Mock_Opponent".equalsIgnoreCase(loser)) {
            com.p2ppvp.mod.DebugLogger.log("[CLIENT_REPORT] Solo mock test detected - skipping match report and post-match overlay.");
            return;
        }

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
                        .version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .connectTimeout(java.time.Duration.ofSeconds(5))
                        .build();
                String reporter = net.minecraft.client.Minecraft.getInstance().getUser().getName();
                String payload = String.format("{\"winner\": \"%s\", \"loser\": \"%s\", \"kit\": \"%s\", \"reporter\": \"%s\"}", winner, loser, kit != null ? kit : "Crystal", reporter);

                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(com.p2ppvp.mod.P2PPvpMod.getMatchmakerUrl() + "/api/match/report"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                boolean isSuccess = false;
                try {
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
                        if (obj.has("winner_kit_elo_change")) winnerKitEloChange = obj.get("winner_kit_elo_change").getAsInt();
                        if (obj.has("loser_kit_elo_change")) loserKitEloChange = obj.get("loser_kit_elo_change").getAsInt();
                        if (obj.has("winner_kit_wins")) winnerKitWins = obj.get("winner_kit_wins").getAsInt();
                        if (obj.has("winner_kit_losses")) winnerKitLosses = obj.get("winner_kit_losses").getAsInt();
                        if (obj.has("loser_kit_wins")) loserKitWins = obj.get("loser_kit_wins").getAsInt();
                        if (obj.has("loser_kit_losses")) loserKitLosses = obj.get("loser_kit_losses").getAsInt();
                        
                        if (obj.has("winner_rank")) winnerRank = obj.get("winner_rank").getAsInt();
                        if (obj.has("loser_rank")) loserRank = obj.get("loser_rank").getAsInt();
                        if (obj.has("winner_kit_rank")) winnerKitRank = obj.get("winner_kit_rank").getAsInt();
                        if (obj.has("loser_kit_rank")) loserKitRank = obj.get("loser_kit_rank").getAsInt();

                        isSuccess = true;
                    }
                } catch (Exception e) {
                    com.p2ppvp.mod.DebugLogger.log("[CLIENT_REPORT] Failed to report match result over network: " + e.getMessage());
                }

                if (!isSuccess) {
                    // LOCAL FALLBACK CALCULATION:
                    com.p2ppvp.mod.DebugLogger.log("[CLIENT_REPORT] Performing local fallback ELO calculation due to server failure/desync.");
                    String activePlayer = net.minecraft.client.Minecraft.getInstance().getUser().getName();
                    boolean isWinner = activePlayer.equalsIgnoreCase(winner);

                    // Fetch current local stats for played kit
                    int currentKitElo = 100;
                    int currentKitWins = 0;
                    int currentKitLosses = 0;
                    int currentOverallElo = 100;
                    int currentOverallWins = 0;
                    int currentOverallLosses = 0;

                    try {
                        java.io.File file = new java.io.File("p2p_player_cache.json");
                        if (file.exists()) {
                            try (java.io.FileReader reader = new java.io.FileReader(file)) {
                                com.google.gson.JsonObject cacheObj = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                                if (cacheObj.has("elo")) currentOverallElo = cacheObj.get("elo").getAsInt();
                                if (cacheObj.has("wins")) currentOverallWins = cacheObj.get("wins").getAsInt();
                                if (cacheObj.has("losses")) currentOverallLosses = cacheObj.get("losses").getAsInt();

                                if (cacheObj.has("kits")) {
                                    com.google.gson.JsonObject kitsObj = cacheObj.getAsJsonObject("kits");
                                    String kName = (kit != null ? kit : "Crystal").toLowerCase();
                                    if (kitsObj.has(kName)) {
                                        com.google.gson.JsonObject kData = kitsObj.getAsJsonObject(kName);
                                        if (kData.has("elo")) currentKitElo = kData.get("elo").getAsInt();
                                        if (kData.has("wins")) currentKitWins = kData.get("wins").getAsInt();
                                        if (kData.has("losses")) currentKitLosses = kData.get("losses").getAsInt();
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {}

                    // Apply ELO calculation (asymmetric K-factors)
                    double expectedKit = 1.0 / (1.0 + Math.pow(10.0, (100.0 - currentKitElo) / 400.0));
                    int localKitChange = isWinner ? (int)(32 * (1.0 - expectedKit)) : -(int)(20 * expectedKit);
                    if (isWinner && localKitChange < 4) localKitChange = 4;
                    if (!isWinner && localKitChange > -2) localKitChange = -2;

                    double expectedOverall = 1.0 / (1.0 + Math.pow(10.0, (100.0 - currentOverallElo) / 400.0));
                    int localOverallChange = isWinner ? (int)(32 * (1.0 - expectedOverall)) : -(int)(20 * expectedOverall);
                    if (isWinner && localOverallChange < 4) localOverallChange = 4;
                    if (!isWinner && localOverallChange > -2) localOverallChange = -2;

                    lastWinner = winner;
                    lastLoser = loser;
                    lastKit = kit != null ? kit : "Crystal";

                    if (isWinner) {
                        winnerElo = currentOverallElo + localOverallChange;
                        winnerEloChange = localOverallChange;
                        winnerWins = currentOverallWins + 1;
                        winnerLosses = currentOverallLosses;

                        winnerKitElo = currentKitElo + localKitChange;
                        winnerKitEloChange = localKitChange;
                        winnerKitWins = currentKitWins + 1;
                        winnerKitLosses = currentKitLosses;

                        // Opponent defaults
                        loserElo = 100;
                        loserEloChange = -2;
                        loserWins = 0;
                        loserLosses = 1;
                        loserKitElo = 100;
                        loserKitEloChange = -2;
                        loserKitWins = 0;
                        loserKitLosses = 1;
                    } else {
                        loserElo = currentOverallElo + localOverallChange;
                        loserEloChange = localOverallChange;
                        loserWins = currentOverallWins;
                        loserLosses = currentOverallLosses + 1;

                        loserKitElo = currentKitElo + localKitChange;
                        loserKitEloChange = localKitChange;
                        loserKitWins = currentKitWins;
                        loserKitLosses = currentKitLosses + 1;

                        // Opponent defaults
                        winnerElo = 100;
                        winnerEloChange = 4;
                        winnerWins = 1;
                        winnerLosses = 0;
                        winnerKitElo = 100;
                        winnerKitEloChange = 4;
                        winnerKitWins = 1;
                        winnerKitLosses = 0;
                    }

                    winnerRank = 1;
                    loserRank = 1;
                    winnerKitRank = 1;
                    loserKitRank = 1;
                }

                lastPlayedTickIndex = -1;
                overlayOpenTime = System.currentTimeMillis();
                showPostMatchOverlay = true;

                // IMMEDIATELY update local statistics and save them to the offline cache file
                try {
                    String activePlayer = net.minecraft.client.Minecraft.getInstance().getUser().getName();
                    boolean isWinner = activePlayer.equalsIgnoreCase(winner);
                    saveStatsImmediately(activePlayer, lastKit, isWinner);
                } catch (Exception e) {
                    com.p2ppvp.mod.DebugLogger.log("[CLIENT_REPORT] Error trigger immediate save stats: " + e.getMessage());
                }
            } catch (Exception e) {
                com.p2ppvp.mod.DebugLogger.log("[CLIENT_REPORT] Failed to report match result: " + e.getMessage());
            }
        });
        reportThread.setDaemon(true);
        reportThread.start();
    }

    /**
     * Instantly updates the local p2p_player_cache.json file by loading the existing cache, 
     * modifying only the played kit stats, and re-computing overall dynamic stats (average ELO and sum of wins/losses).
     */
    public static void saveStatsImmediately(String pId, String playedKit, boolean isWinner) {
        try {
            java.io.File file = new java.io.File("p2p_player_cache.json");
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            
            // 1. Read existing cache if it exists to preserve other kits' history
            if (file.exists()) {
                try (java.io.FileReader reader = new java.io.FileReader(file)) {
                    obj = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                } catch (Exception ignored) {}
            }
            
            obj.addProperty("player_id", pId);
            
            com.google.gson.JsonObject kitsObj = obj.has("kits") ? obj.getAsJsonObject("kits") : new com.google.gson.JsonObject();
            
            // 2. Modify only the played kit's data with the new verified ELO, wins, losses, and rank
            String kName = playedKit.toLowerCase();
            com.google.gson.JsonObject kData = kitsObj.has(kName) ? kitsObj.getAsJsonObject(kName) : new com.google.gson.JsonObject();
            
            int kElo = isWinner ? winnerKitElo : loserKitElo;
            int kWins = isWinner ? winnerKitWins : loserKitWins;
            int kLosses = isWinner ? winnerKitLosses : loserKitLosses;
            int kRank = isWinner ? winnerKitRank : loserKitRank;
            
            kData.addProperty("elo", kElo);
            kData.addProperty("wins", kWins);
            kData.addProperty("losses", kLosses);
            kData.addProperty("rank", kRank);
            
            kitsObj.add(kName, kData);
            obj.add("kits", kitsObj);
            
            // 3. Re-compute overall dynamic stats (Average ELO of 5 kits, Sum of Wins/Losses of 5 kits)
            int totalEloSum = 0;
            int totalWinsSum = 0;
            int totalLossesSum = 0;
            for (String k : java.util.Arrays.asList("crystal", "uhc", "pot", "mace", "sword")) {
                com.google.gson.JsonObject currKitData = kitsObj.has(k) ? kitsObj.getAsJsonObject(k) : null;
                if (currKitData != null) {
                    totalEloSum += currKitData.has("elo") ? currKitData.get("elo").getAsInt() : 100;
                    totalWinsSum += currKitData.has("wins") ? currKitData.get("wins").getAsInt() : 0;
                    totalLossesSum += currKitData.has("losses") ? currKitData.get("losses").getAsInt() : 0;
                } else {
                    totalEloSum += 100;
                }
            }
            
                        obj.addProperty("elo", totalEloSum / 5);
            obj.addProperty("wins", totalWinsSum);
            obj.addProperty("losses", totalLossesSum);
            obj.addProperty("rank", isWinner ? winnerRank : loserRank);

            // 3.5 Append a match record locally to "history" array
            com.google.gson.JsonArray histArr = obj.has("history") ? obj.getAsJsonArray("history") : new com.google.gson.JsonArray();
            com.google.gson.JsonObject newMatch = new com.google.gson.JsonObject();
            newMatch.addProperty("match_id", java.util.UUID.randomUUID().toString());
            newMatch.addProperty("timestamp", java.time.Instant.now().toString());
            newMatch.addProperty("winner", lastWinner);
            newMatch.addProperty("loser", lastLoser);
            newMatch.addProperty("kit", lastKit.toLowerCase());
            newMatch.addProperty("winner_elo_change", winnerKitEloChange);
            newMatch.addProperty("loser_elo_change", loserKitEloChange);
            newMatch.addProperty("solo_test", lastWinner.contains("Mock") || lastLoser.contains("Mock"));
            
            // Insert at the front (index 0) of the array to keep it sorted (newest first)
            com.google.gson.JsonArray tempArr = new com.google.gson.JsonArray();
            tempArr.add(newMatch);
            for (com.google.gson.JsonElement el : histArr) {
                tempArr.add(el);
            }
            // Limit to 50 records in cache to prevent file bloat
            if (tempArr.size() > 50) {
                com.google.gson.JsonArray limitedArr = new com.google.gson.JsonArray();
                for (int i = 0; i < 50; i++) {
                    limitedArr.add(tempArr.get(i));
                }
                tempArr = limitedArr;
            }
            obj.add("history", tempArr);
            
            // 4. Serialize back to the cache file
            try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
                writer.write(obj.toString());
            }
            com.p2ppvp.mod.DebugLogger.log("[CLIENT_REPORT] Successfully saved post-match stats IMMEDIATELY to local cache file.");
        } catch (Exception e) {
            com.p2ppvp.mod.DebugLogger.log("[CLIENT_REPORT] Error saving post-match stats immediately: " + e.getMessage());
        }
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