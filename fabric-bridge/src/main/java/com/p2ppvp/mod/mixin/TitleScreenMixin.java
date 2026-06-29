package com.p2ppvp.mod.mixin;

import com.p2ppvp.mod.ArenaManager;
import com.p2ppvp.mod.DaemonManager;
import com.p2ppvp.mod.DebugLogger;
import com.p2ppvp.mod.client.LatencyManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("p2ppvp-ui");
    private static final Identifier MCR_LOGO = Identifier.fromNamespaceAndPath("p2ppvp", "textures/gui/mcr_title.png");

            private static final String MATCHMAKER_URL = "http://127.0.0.1:8000";
    private String activeMatchmakerUrl = MATCHMAKER_URL;

    // Matchmaking and gameplay states
    private int pingRadiusSliderValue = 100; // in ms
    private int selectedSpearTier = 1;      // Tier 1, 2, or 3
    private boolean isSearchingMatch = false;
    private String statusMessage = "Ready";
    private String playerId = "Player_" + (int)(Math.random() * 9000 + 1000);
    private static boolean hasRegisteredThisSession = false;
    
    // Player statistics cached from backend registration
    private int playerElo = 100;
    private int playerWins = 0;
    private int playerLosses = 0;
    private int playerStreak = 0;
    private int playerMaxStreak = 0;
    private long queueStartTime = 0L;
    private long animationStartTime = 0L;
    private final java.util.Map<String, Integer> kitElos = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> kitWins = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> kitLosses = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> kitRanks = new java.util.HashMap<>();
    private String selectedProfileKit = "overall";

        private Button findMatchButton;
    private Button queueSettingsButton;
    private Button leaderboardButton;
    private Button optionsButton;
    private Button postMatchCloseButton;
    private Thread pollingThread = null;
    private boolean showDetailedProfile = false;
        private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

        protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(at = @At("RETURN"), method = "init")
    private void onInit(CallbackInfo ci) {
        if (this.minecraft != null && this.minecraft.getUser() != null) {
            this.playerId = this.minecraft.getUser().getName();
        }
        DebugLogger.log("[UI] TitleScreen initialized. Player ID: " + this.playerId);

        // Load stats from local cache file immediately so they render correctly on boot!
        loadStatsLocally(this.playerId);

        // Asynchronously update player stats from backend on every title screen initialization
        registerPlayerAsync(this.playerId);
        // Disable vanilla buttons (singleplayer, multiplayer, realms)
        for (Renderable drawable : ((ScreenAccessor) this).getDrawables()) {
            if (drawable instanceof AbstractWidget widget) {
                widget.visible = false;
                widget.active = false;
            }
        }

        int btnWidth = 200;
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int cardHeight = 220;
        int cardY = centerY - cardHeight / 2 - 10;
        int btnX = centerX - btnWidth / 2;
        int btnStartY = cardY + 120;

                                                // 1. "Find Match" Button (Queues with Python backend)
        this.findMatchButton = this.addRenderableWidget(new com.p2ppvp.mod.client.McrButton(
            btnX, btnStartY, btnWidth, 20,
            Component.literal("§6§lFind Match"),
            (b) -> {
                if (!this.isSearchingMatch) {
                    String daemonStatus = com.p2ppvp.mod.DaemonManager.queryDaemonStatus();
                    if ("ERR_DIRECT_CONN_FAILED".equalsIgnoreCase(daemonStatus)) {
                        this.statusMessage = "§cDirect connection failed. Please restart your computer.";
                        return;
                    }
                    long windowHandle = 0;
                    try {
                        Object windowObj = net.minecraft.client.Minecraft.getInstance().getWindow();
                        for (java.lang.reflect.Method m : windowObj.getClass().getDeclaredMethods()) {
                            if (m.getReturnType() == long.class && m.getParameterCount() == 0) {
                                m.setAccessible(true);
                                windowHandle = (long) m.invoke(windowObj);
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                    boolean isShift = false;
                    if (windowHandle != 0) {
                        isShift = org.lwjgl.glfw.GLFW.glfwGetKey(windowHandle, 340) == 1 || 
                                  org.lwjgl.glfw.GLFW.glfwGetKey(windowHandle, 344) == 1;
                    }
                    this.joinQueue(b, isShift);
                } else {
                    this.leaveQueue(b);
                }
            },
            this.font
        ));

        // 2. "Queue Settings" Button
        this.queueSettingsButton = this.addRenderableWidget(new com.p2ppvp.mod.client.McrButton(
            btnX, btnStartY + 24, btnWidth, 20,
            Component.literal("§b§lQueue Settings"), 
            (b) -> {
                this.minecraft.setScreen(new com.p2ppvp.mod.client.MatchmakingOptionsScreen(this));
            },
            this.font
        ));

        // 3. "Leaderboard" Button
        this.leaderboardButton = this.addRenderableWidget(new com.p2ppvp.mod.client.McrButton(
            btnX, btnStartY + 48, btnWidth, 20,
            Component.literal("§e§lLeaderboard"), 
            (b) -> {
                this.minecraft.setScreen(new com.p2ppvp.mod.client.LeaderboardScreen(this));
            },
            this.font
        ));

        // 4. "Options" Button
        this.optionsButton = this.addRenderableWidget(new com.p2ppvp.mod.client.McrButton(
            btnX, btnStartY + 72, btnWidth, 20,
            Component.literal("§7⚙ Options"), 
            (b) -> {
                this.minecraft.setScreen(new net.minecraft.client.gui.screens.options.OptionsScreen(this, this.minecraft.options, false));
            },
            this.font
        ));

        // 5. Post-Match Close Button (only visible/active when overlay is active)
        this.postMatchCloseButton = this.addRenderableWidget(new com.p2ppvp.mod.client.McrButton(
            centerX - 60, centerY + 65, 120, 20,
            Component.literal("§aClose Stats"),
            (b) -> {
                com.p2ppvp.mod.client.P2PPvpModClient.showPostMatchOverlay = false;
                this.animationStartTime = 0L;

                // Sync the post-match results directly into the local in-memory variables
                boolean isWinner = this.playerId.equalsIgnoreCase(com.p2ppvp.mod.client.P2PPvpModClient.lastWinner);
                this.playerElo = isWinner ? com.p2ppvp.mod.client.P2PPvpModClient.winnerElo : com.p2ppvp.mod.client.P2PPvpModClient.loserElo;
                this.playerWins = isWinner ? com.p2ppvp.mod.client.P2PPvpModClient.winnerWins : com.p2ppvp.mod.client.P2PPvpModClient.loserWins;
                this.playerLosses = isWinner ? com.p2ppvp.mod.client.P2PPvpModClient.winnerLosses : com.p2ppvp.mod.client.P2PPvpModClient.loserLosses;
                this.kitRanks.put("overall", isWinner ? com.p2ppvp.mod.client.P2PPvpModClient.winnerRank : com.p2ppvp.mod.client.P2PPvpModClient.loserRank);

                String playedKit = com.p2ppvp.mod.client.P2PPvpModClient.lastKit.toLowerCase();
                if (playedKit != null && !playedKit.isEmpty() && !playedKit.equals("overall")) {
                    int kElo = isWinner ? com.p2ppvp.mod.client.P2PPvpModClient.winnerKitElo : com.p2ppvp.mod.client.P2PPvpModClient.loserKitElo;
                    int kWins = isWinner ? com.p2ppvp.mod.client.P2PPvpModClient.winnerKitWins : com.p2ppvp.mod.client.P2PPvpModClient.loserKitWins;
                    int kLosses = isWinner ? com.p2ppvp.mod.client.P2PPvpModClient.winnerKitLosses : com.p2ppvp.mod.client.P2PPvpModClient.loserKitLosses;
                    int kRank = isWinner ? com.p2ppvp.mod.client.P2PPvpModClient.winnerKitRank : com.p2ppvp.mod.client.P2PPvpModClient.loserKitRank;

                    this.kitElos.put(playedKit, kElo);
                    this.kitWins.put(playedKit, kWins);
                    this.kitLosses.put(playedKit, kLosses);
                    this.kitRanks.put(playedKit, kRank);
                }

                // Instantly save to local file cache
                this.saveStatsLocally(this.playerId, this.playerElo, this.playerWins, this.playerLosses, this.playerStreak, this.playerMaxStreak);

                this.updateButtonsVisibility();
            },
            this.font
        ));

        this.updateButtonsVisibility();
    }

        private void updateButtonsVisibility() {
        boolean overlay = com.p2ppvp.mod.client.P2PPvpModClient.showPostMatchOverlay;
        boolean detailedProfile = this.showDetailedProfile;
        boolean anyOverlay = overlay || detailedProfile;

        long elapsed = System.currentTimeMillis() - com.p2ppvp.mod.client.P2PPvpModClient.overlayOpenTime;
        boolean animCompleted = elapsed >= 3000;

        if (this.findMatchButton != null) {
            this.findMatchButton.visible = !anyOverlay;
            this.findMatchButton.active = !anyOverlay;
        }
        if (this.queueSettingsButton != null) {
            this.queueSettingsButton.visible = !anyOverlay;
            this.queueSettingsButton.active = !anyOverlay;
        }
        if (this.leaderboardButton != null) {
            this.leaderboardButton.visible = !anyOverlay;
            this.leaderboardButton.active = !anyOverlay;
        }
        if (this.optionsButton != null) {
            this.optionsButton.visible = !anyOverlay;
            this.optionsButton.active = !anyOverlay;
        }
        if (this.postMatchCloseButton != null) {
            this.postMatchCloseButton.visible = overlay && animCompleted;
            this.postMatchCloseButton.active = overlay && animCompleted;
        }
    }

    private String getActiveMatchmakerUrl() {
        return "http://127.0.0.1:8000";
    }

    private void joinQueue(Button b, boolean isSoloTest) {
        this.isSearchingMatch = true;
        this.queueStartTime = System.currentTimeMillis();

        if (isSoloTest) {
            this.statusMessage = "§eStarting Solo Mock Match...";
            b.setMessage(Component.literal("§e§lMock Queue... [Cancel]"));
            DebugLogger.log("[JOIN] [SOLO TEST] Player initiated Solo Test queue.");
        } else {
            this.statusMessage = "Registering on matchmaking queue...";
            b.setMessage(Component.literal("§c§lIn Queue... [Cancel]"));
        }

        // Asynchronously post join payload
        Thread joinThread = new Thread(() -> {
            try {
                this.activeMatchmakerUrl = getActiveMatchmakerUrl();
                String actualTailscaleIp = DaemonManager.getDaemonIP();
                DebugLogger.log("[JOIN] Attempting to join queue. Matchmaker URL: " + this.activeMatchmakerUrl + ", Client Tailscale IP: " + actualTailscaleIp);

                // Dynamically evaluate machine hardware score to decide host/guest placement
                long availableProcessors = Runtime.getRuntime().availableProcessors();
                long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);
                double perfScore = (availableProcessors * 1000.0) + maxMemory;

                StringBuilder kitsJson = new StringBuilder("[ ");
                boolean first = true;
                for (String kit : com.p2ppvp.mod.client.MatchmakingOptionsScreen.selectedKits) {
                    if (!first) kitsJson.append(", ");
                    kitsJson.append("\"").append(kit).append("\"");
                    first = false;
                }
                kitsJson.append(" ]");

                String payload = String.format(
                    "{\"player_id\": \"%s\", \"kit_profile\": \"Spear%d\", \"ping_limit\": %d, \"selected_kits\": %s, \"tailscale_ip\": \"%s\", \"perf_score\": %.1f, \"solo_test\": %b}",
                    this.playerId, this.selectedSpearTier, com.p2ppvp.mod.client.MatchmakingOptionsScreen.selectedPingLimit, kitsJson.toString(), actualTailscaleIp, perfScore, isSoloTest
                );

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(this.activeMatchmakerUrl + "/api/queue/join"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                DebugLogger.log("[JOIN] Inbound response status: " + response.statusCode() + " body: " + response.body());
                if (response.statusCode() == 200) {
                    if (!isSoloTest) {
                        this.statusMessage = "Searching for match...";
                    }
                    this.startQueuePolling();
                } else {
                    this.statusMessage = "§cRegistration failed (" + response.statusCode() + ")";
                    DebugLogger.log("[JOIN] Queue registration rejected with status code: " + response.statusCode());
                    this.isSearchingMatch = false;
                    this.queueStartTime = 0L;
                    this.minecraft.execute(() -> b.setMessage(Component.literal("§6§lFind Match")));
                }
            } catch (Exception e) {
                DebugLogger.log("[JOIN] EXCEPTION occurred during queue registration", e);
                LOGGER.error("Error joining queue", e);
                this.statusMessage = "§cNetwork Error: Matchmaker offline";
                this.isSearchingMatch = false;
                this.queueStartTime = 0L;
                this.minecraft.execute(() -> b.setMessage(Component.literal("§6§lFind Match")));
            }
        });
        joinThread.setDaemon(true);
        joinThread.start();
    }

    private void leaveQueue(Button b) {
        this.isSearchingMatch = false;
        this.queueStartTime = 0L;
        this.statusMessage = "Ready";
        b.setMessage(Component.literal("§6§lFind Match"));
        DebugLogger.log("[LEAVE] Player initiated leave queue...");

        // Safely reset active network delays
        LatencyManager.setActiveDelay(0);

        if (this.pollingThread != null) {
            this.pollingThread.interrupt();
            this.pollingThread = null;
        }

        Thread leaveThread = new Thread(() -> {
            try {
                String payload = String.format("{\"player_id\": \"%s\"}", this.playerId);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(this.activeMatchmakerUrl + "/api/queue/leave"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                DebugLogger.log("[LEAVE] Left queue successfully. Response status: " + response.statusCode());
            } catch (Exception e) {
                DebugLogger.log("[LEAVE] EXCEPTION during leave request", e);
            }
        });
        leaveThread.setDaemon(true);
        leaveThread.start();
    }

    private void startQueuePolling() {
        if (this.pollingThread != null) {
            this.pollingThread.interrupt();
        }

        DebugLogger.log("[POLL] Starting background matchmaking status polling loop...");
        this.pollingThread = new Thread(() -> {
            try {
                while (this.isSearchingMatch) {
                    Thread.sleep(1000);

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(this.activeMatchmakerUrl + "/api/queue/status?player_id=" + this.playerId))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    DebugLogger.log("[POLL] Query status: " + response.statusCode() + " body: " + response.body());

                    if (response.statusCode() == 200) {
                        String body = response.body();
                        if (body.contains("\"matched\"")) {
                            // Extract match parameters
                            boolean isHost = body.contains("\"role\": \"host\"");
                            String hostIp = extractJSONValue(body, "host_ip");
                            String opponentId = extractJSONValue(body, "opponent_id");
                            String matchedKit = extractJSONValue(body, "kit");
                            if (matchedKit != null && !matchedKit.isEmpty()) {
                                com.p2ppvp.mod.P2PPvpMod.activeKitName = matchedKit;
                            } else {
                                com.p2ppvp.mod.P2PPvpMod.activeKitName = "Crystal";
                            }
                            DebugLogger.log("[POLL] MATCH FOUND! Role: " + (isHost ? "HOST" : "GUEST") + ", Host IP: " + hostIp + ", Opponent ID: " + opponentId + ", Kit: " + com.p2ppvp.mod.P2PPvpMod.activeKitName);
                            com.p2ppvp.mod.P2PPvpMod.authorizedOpponentName = opponentId;

                            this.statusMessage = "§aMatch Found! Pairing...";
                            this.isSearchingMatch = false;
                            this.queueStartTime = 0L;
                            if (opponentId.contains("Mock") || body.contains("Mock")) {
                                // For solo testing, trigger active 50ms simulated delay (representing 100ms ping)
                                LatencyManager.setActiveDelay(50);
                            } else {
                                // Real matches: Set to zero or pull measured ping from connection handshake
                                LatencyManager.setActiveDelay(0);
                            }

                             this.minecraft.execute(() -> {
                                 if (isHost) {
                                     this.statusMessage = "§aInitializing match as Host...";
                                     com.p2ppvp.mod.DaemonManager.stopPeer();
                                     this.loadLocalWorldAndPublish();
                                 } else {
                                     this.statusMessage = "§aConnecting to Host...";
                                     this.connectToHostAddress(hostIp);
                                 }
                             });
                            break;
                        }
                    }
                }
            } catch (InterruptedException ignored) {
                DebugLogger.log("[POLL] Polling thread interrupted (expected on exit/match).");
            } catch (Exception e) {
                DebugLogger.log("[POLL] EXCEPTION occurred during status polling", e);
                LOGGER.error("Error during queue status polling", e);
                this.statusMessage = "§cPolling lost connection.";
                this.isSearchingMatch = false;
                this.queueStartTime = 0L;
            }
        });
        this.pollingThread.setDaemon(true);
        this.pollingThread.start();
    }

    private void loadLocalWorldAndPublish() {
        DebugLogger.log("[HOST] Launching local world and publishing to LAN...");

        // Run arena initialization in a separate thread to keep the UI smooth
        Thread launchThread = new Thread(() -> {
            if (com.p2ppvp.mod.ArenaManager.isExtracting()) {
                this.statusMessage = "§aWaiting for background map reset...";
                com.p2ppvp.mod.ArenaManager.waitForExtraction();
            }

            File saveFolder = new File("saves/p2p_arena_cache");
            if (!saveFolder.exists()) {
                this.statusMessage = "§aPreparing arena map...";
                boolean success = com.p2ppvp.mod.ArenaManager.initializeArenaCacheSync();
                if (!success) {
                    DebugLogger.log("[HOST] ERROR: Failed to refresh arena map cache!");
                    this.statusMessage = "§cFailed to refresh arena map!";
                    return;
                }
            }

            // Execute world opening flow on the main client thread
            this.minecraft.execute(() -> {
                try {
                    File saveFolderCheck = new File("saves/p2p_arena_cache");
                    if (saveFolderCheck.exists()) {
                        this.minecraft.createWorldOpenFlows().openWorld("p2p_arena_cache", () -> {
                            if (this.minecraft.getSingleplayerServer() != null) {
                                this.minecraft.getSingleplayerServer().publishServer(
                                    GameType.SURVIVAL, 
                                    false, 
                                    25565
                                );
                                DebugLogger.log("[HOST] Published integrated world to LAN on port 25565");
                                LOGGER.info("[P2P Bridge] Host published integrated world to LAN in Survival Mode with Cheats disabled on port 25565");
                            } else {
                                DebugLogger.log("[HOST] ERROR: SingleplayerServer was null after loading world!");
                            }
                            this.minecraft.setScreen(null);
                        });
                    } else {
                        this.statusMessage = "§cError: Local Arena Cache is missing!";
                        DebugLogger.log("[HOST] ERROR: saves/p2p_arena_cache does not exist on disk!");
                    }
                } catch (Exception e) {
                    DebugLogger.log("[HOST] EXCEPTION during local world load and publish", e);
                    this.statusMessage = "§cLaunch failed: " + e.getMessage();
                }
            });
        }, "P2PHostLaunchThread");
        launchThread.setDaemon(true);
        launchThread.start();
    }

        private void connectToHostAddress(String hostIp) {
        DebugLogger.log("[GUEST] Setting up Tailscale guest tunnel and connecting locally. Host IP: " + hostIp);
        try {
            // First, inform the Go daemon of the host's Tailscale IP
            com.p2ppvp.mod.DaemonManager.setRemotePeer(hostIp);

            LOGGER.info("[P2P Bridge] Guest initiating automatic network join to virtual IP: 127.0.0.1");
            ServerAddress serverAddress = new ServerAddress("127.0.0.1", 25565);
            ServerData serverData = new ServerData("P2P Match", "127.0.0.1", ServerData.Type.OTHER);
            ConnectScreen.startConnecting(this, this.minecraft, serverAddress, serverData, false, null);
        } catch (Exception e) {
            DebugLogger.log("[GUEST] EXCEPTION occurred during network connection", e);
            this.statusMessage = "§cJoin failed: " + e.getMessage();
        }
    }

    private void registerPlayerAsync(String pId) {
        Thread thread = new Thread(() -> {
            try {
                String matchmaker = getActiveMatchmakerUrl();
                String payload = String.format("{\"player_id\": \"%s\"}", pId);
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(matchmaker + "/api/player/register"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    String body = response.body();
                    com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                    
                    if (obj.has("elo")) this.playerElo = obj.get("elo").getAsInt();
                    if (obj.has("wins")) this.playerWins = obj.get("wins").getAsInt();
                    if (obj.has("losses")) this.playerLosses = obj.get("losses").getAsInt();
                    if (obj.has("streak")) this.playerStreak = obj.get("streak").getAsInt();
                    if (obj.has("max_streak")) this.playerMaxStreak = obj.get("max_streak").getAsInt();
                    if (obj.has("rank")) this.kitRanks.put("overall", obj.get("rank").getAsInt());
                    if (obj.has("kits")) {
                        com.google.gson.JsonObject kitsObj = obj.getAsJsonObject("kits");
                        for (String k : java.util.Arrays.asList("crystal", "uhc", "pot", "mace", "sword")) {
                            if (kitsObj.has(k)) {
                                com.google.gson.JsonObject kData = kitsObj.getAsJsonObject(k);
                                if (kData.has("elo")) this.kitElos.put(k, kData.get("elo").getAsInt());
                                if (kData.has("wins")) this.kitWins.put(k, kData.get("wins").getAsInt());
                                if (kData.has("losses")) this.kitLosses.put(k, kData.get("losses").getAsInt());
                                if (kData.has("rank")) this.kitRanks.put(k, kData.get("rank").getAsInt());
                            }
                        }
                    }
                    
                    DebugLogger.log("[UI] Loaded player stats from backend: ELO=" + this.playerElo + ", W=" + this.playerWins + ", L=" + this.playerLosses);
                    this.saveStatsLocally(pId, this.playerElo, this.playerWins, this.playerLosses, this.playerStreak, this.playerMaxStreak);
                }
            } catch (Exception e) {
                DebugLogger.log("[UI] Error registering player with backend: " + e.getMessage());
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private String extractJSONNumericValue(String json, String key) {
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

    private String extractJSONValue(String json, String key) {
        try {
            int index = json.indexOf("\"" + key + "\"");
            if (index == -1) return "";
            int start = json.indexOf(":", index) + 1;
            int valStart = json.indexOf("\"", start) + 1;
            int valEnd = json.indexOf("\"", valStart);
            return json.substring(valStart, valEnd);
        } catch (Exception e) {
            return "";
        }
    }

        @Inject(at = @At("TAIL"), method = "extractRenderState")
    private void onRender(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int goldColor = 0xFFD4AF37;

        // 1. Render the Central Matchmaking Deck Card
        int cardWidth = 240;
        int cardHeight = 220;
        int cardX = centerX - cardWidth / 2;
        int cardY = centerY - cardHeight / 2 - 10;

        int left = cardX;
        int right = cardX + cardWidth;
        int top = cardY;
        int bottom = cardY + cardHeight;

        // Central Deck Backing & Shadow (opaque charcoal background)
        context.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF000000);
        context.fill(left + 1, top + 1, right - 1, bottom - 1, 0xCC0B0B0F);

        // Central Deck Gold Border
        context.fill(left, top, left + 1, bottom, goldColor);       // Left Edge
        context.fill(right - 1, top, right, bottom, goldColor);     // Right Edge
        context.fill(left, top, right, top + 1, goldColor);         // Top Edge
        context.fill(left, bottom - 1, right, bottom, goldColor);   // Bottom Edge

        // Render the active buttons on top of the Central Deck Card background
        if (this.findMatchButton != null) this.findMatchButton.extractRenderState(context, mouseX, mouseY, delta);
        if (this.queueSettingsButton != null) this.queueSettingsButton.extractRenderState(context, mouseX, mouseY, delta);
        if (this.leaderboardButton != null) this.leaderboardButton.extractRenderState(context, mouseX, mouseY, delta);
        if (this.optionsButton != null) this.optionsButton.extractRenderState(context, mouseX, mouseY, delta);
        // Render custom MCR PVP logo centered inside the card
        int logoW = 120;
        int logoH = 84;
        int logoX = centerX - logoW / 2;
        int logoY = cardY + 12;
        context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, MCR_LOGO, logoX, logoY, 0.0f, 0.0f, logoW, logoH, logoW, logoH, 0xFFFFFFFF);

        // Live Queue Feedback / Status Message
        int statusY = logoY + logoH + 8;
        if (this.isSearchingMatch) {
            long elapsedMs = System.currentTimeMillis() - this.queueStartTime;
            if (this.queueStartTime == 0) elapsedMs = 0;
            long elapsedSec = (elapsedMs / 1000) % 60;
            long elapsedMin = (elapsedMs / (1000 * 60)) % 60;
            String timerStr = String.format("%02d:%02d", elapsedMin, elapsedSec);

            String[ ] loadingChars = {"-", "\\", "|", "/"};
            int charIndex = (int)((System.currentTimeMillis() / 250) % 4);
            String spinner = loadingChars[charIndex];

            String dispText = "§6● In Queue [" + timerStr + "] " + spinner;
            context.centeredText(this.font, dispText, centerX, statusY, 0xFFFFAA00);
        } else {
            int statusColor = this.statusMessage.startsWith("§c") ? 0xFFFF5555 : 0xFF9E9E9E;
            String dispText = this.statusMessage.startsWith("§c") ? this.statusMessage : "§7Status: " + this.statusMessage;
            context.centeredText(this.font, dispText, centerX, statusY, statusColor);
        }

        // 2. Render the Personal Profile Card (Top-Right Corner)
        int profileWidth = 150;
        int profileHeight = 50;
        int profileX = this.width - profileWidth - 10;
        int profileY = 10;

        int pLeft = profileX;
        int pRight = profileX + profileWidth;
        int pTop = profileY;
        int pBottom = profileY + profileHeight;

        // Profile Card Backing & Shadow (opaque charcoal background)
        context.fill(pLeft - 1, pTop - 1, pRight + 1, pBottom + 1, 0xFF000000);
        context.fill(pLeft + 1, pTop + 1, pRight - 1, pBottom - 1, 0xCC0B0B0F);

        // Profile Card Gold Border
        context.fill(pLeft, pTop, pLeft + 1, pBottom, goldColor);       // Left Edge
        context.fill(pRight - 1, pTop, pRight, pBottom, goldColor);     // Right Edge
        context.fill(pLeft, pTop, pRight, pTop + 1, goldColor);         // Top Edge
        context.fill(pLeft, pBottom - 1, pRight, pBottom, goldColor);   // Bottom Edge

        // Pixel-Perfect 2D Player Face/Skin Avatar (prevent tiling by specifying full sheet size 64x64 with 8x8 region)
        int avatarX = profileX + 8;
        int avatarY = profileY + 9;
        Identifier skinTex = getPlayerSkin();
        if (skinTex != null) {
            // Render the base head region (U=8, V=8, 8x8 pixels) scaled beautifully to 32x32 using 13-parameter blit
            context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, skinTex, avatarX, avatarY, 8.0f, 8.0f, 32, 32, 8, 8, 64, 64, 0xFFFFFFFF);
            // Render the outer accessory/hair layer region (U=40, V=8, 8x8 pixels) scaled beautifully to 32x32 on top using 13-parameter blit
            context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, skinTex, avatarX, avatarY, 40.0f, 8.0f, 32, 32, 8, 8, 64, 64, 0xFFFFFFFF);
        } else {
            // Safe, performant dark-gray placeholder frame
            context.fill(avatarX, avatarY, avatarX + 32, avatarY + 32, 0xFF444444);
        }

        // Render user credentials and stats
        int statsX = profileX + 46;
        context.text(this.font, "§6★ " + this.playerId, statsX, profileY + 8, 0xFFFFFFFF);

        String rankStr = getRankTier(this.playerElo);
        context.text(this.font, "§e" + this.playerElo + " ELO §7| " + rankStr, statsX, profileY + 20, 0xFFFFFFFF);

        context.text(this.font, "§a" + this.playerWins + " Wins §7| §c" + this.playerLosses + " Losses", statsX, profileY + 32, 0xFFFFFFFF);

        // 3. Render Post-Match Statistics Overlay Card if active
        if (com.p2ppvp.mod.client.P2PPvpModClient.showPostMatchOverlay) {
            // Draw a full-screen translucent dark backing
            context.fill(0, 0, this.width, this.height, 0xBB050507);

            // Initialize the overlay start timer exactly when first rendered
            if (this.animationStartTime == 0L) {
                this.animationStartTime = System.currentTimeMillis();
                com.p2ppvp.mod.client.P2PPvpModClient.lastPlayedTickIndex = -1;
            }

            int ovWidth = 300;
            int ovHeight = 220;
            int ovX = centerX - ovWidth / 2;
            int ovY = centerY - ovHeight / 2 - 10;

            int oLeft = ovX;
            int oRight = ovX + ovWidth;
            int oTop = ovY;
            int oBottom = ovY + ovHeight;

            // Backing and gold border
            context.fill(oLeft - 1, oTop - 1, oRight + 1, oBottom + 1, 0xFF000000);
            context.fill(oLeft + 1, oTop + 1, oRight - 1, oBottom - 1, 0xCC0B0B0F);

            context.fill(oLeft, oTop, oLeft + 1, oBottom, goldColor);       // Left Edge
            context.fill(oRight - 1, oTop, oRight, oBottom, goldColor);     // Right Edge
            context.fill(oLeft, oTop, oRight, oTop + 1, goldColor);         // Top Edge
            context.fill(oLeft, oBottom - 1, oRight, oBottom, goldColor);   // Bottom Edge

            // Draw Titles
            context.centeredText(this.font, "§6§l=== MATCH COMPLETED ===", centerX, oTop + 14, 0xFFFFFFFF);

            boolean isWinner = this.playerId.equalsIgnoreCase(com.p2ppvp.mod.client.P2PPvpModClient.lastWinner);
            if (isWinner) {
                context.centeredText(this.font, "§a§lVICTORY!", centerX, oTop + 30, 0xFFFFFFFF);
            } else {
                context.centeredText(this.font, "§c§lDEFEAT", centerX, oTop + 30, 0xFFFFFFFF);
            }

            // Draw Kit Details
            context.centeredText(this.font, "§7Format: §e" + com.p2ppvp.mod.client.P2PPvpModClient.lastKit.toUpperCase(), centerX, oTop + 44, 0xFFBBBBBB);

            // Draw clean horizontal divider
            context.horizontalLine(oLeft + 15, oRight - 15, oTop + 56, 0x55D4AF37);

            long elapsed = System.currentTimeMillis() - this.animationStartTime;

            int N = 18;
            long[ ] tickTimes = new long[N + 1];
            for (int i = 0; i <= N; i++) {
                tickTimes[i] = Math.round(3000.0 * Math.pow((double) i / N, 2.5));
            }

            int currentTickIndex = 0;
            for (int i = 0; i <= N; i++) {
                if (elapsed >= tickTimes[i]) {
                    currentTickIndex = i;
                } else {
                    break;
                }
            }

            float progress = (float) currentTickIndex / N;

            // Play Tick Sound client-side if tick index changed
            if (elapsed < 3000) {
                if (currentTickIndex > com.p2ppvp.mod.client.P2PPvpModClient.lastPlayedTickIndex) {
                    com.p2ppvp.mod.client.P2PPvpModClient.lastPlayedTickIndex = currentTickIndex;
                    this.minecraft.getSoundManager().play(
                        net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.8f
                        )
                    );
                }
            } else {
                if (com.p2ppvp.mod.client.P2PPvpModClient.lastPlayedTickIndex < N) {
                    com.p2ppvp.mod.client.P2PPvpModClient.lastPlayedTickIndex = N;
                    this.minecraft.getSoundManager().play(
                        net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.8f
                        )
                    );
                }
            }

            // Get personal stats of local player only
            int myEloChange = isWinner ? com.p2ppvp.mod.client.P2PPvpModClient.winnerEloChange : com.p2ppvp.mod.client.P2PPvpModClient.loserEloChange;
            int myFinalElo = isWinner ? com.p2ppvp.mod.client.P2PPvpModClient.winnerElo : com.p2ppvp.mod.client.P2PPvpModClient.loserElo;
            int myWins = isWinner ? com.p2ppvp.mod.client.P2PPvpModClient.winnerWins : com.p2ppvp.mod.client.P2PPvpModClient.loserWins;
            int myLosses = isWinner ? com.p2ppvp.mod.client.P2PPvpModClient.winnerLosses : com.p2ppvp.mod.client.P2PPvpModClient.loserLosses;

            // Calculate animated ELO values
            int myStartElo = myFinalElo - myEloChange;
            int myCurrentElo = myStartElo + Math.round(progress * myEloChange);
            int myCurrentChange = Math.round(progress * myEloChange);
            String mySign = myCurrentChange > 0 ? "+" : "";

            int myTotalMatches = myWins + myLosses;
            double myWinRate = myTotalMatches > 0 ? (myWins * 100.0) / myTotalMatches : 0.0;

            // Render Player 2D Face Avatar on the left side
            int pmAvatarX = oLeft + 20;
            int pmAvatarY = oTop + 72;
            Identifier pmSkinTex = getPlayerSkin();
            if (pmSkinTex != null) {
                // Render base head (U=8, V=8, 8x8 pixels) scaled to 48x48
                context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pmSkinTex, pmAvatarX, pmAvatarY, 8.0f, 8.0f, 48, 48, 8, 8, 64, 64, 0xFFFFFFFF);
                // Render accessory layer (U=40, V=8, 8x8 pixels) scaled to 48x48
                context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pmSkinTex, pmAvatarX, pmAvatarY, 40.0f, 8.0f, 48, 48, 8, 8, 64, 64, 0xFFFFFFFF);
            } else {
                context.fill(pmAvatarX, pmAvatarY, pmAvatarX + 48, pmAvatarY + 48, 0xFF444444);
            }

            // Draw a fine gold border around the avatar head
            context.fill(pmAvatarX - 1, pmAvatarY - 1, pmAvatarX + 49, pmAvatarY, goldColor);
            context.fill(pmAvatarX - 1, pmAvatarY + 48, pmAvatarX + 49, pmAvatarY + 49, goldColor);
            context.fill(pmAvatarX - 1, pmAvatarY, pmAvatarX, pmAvatarY + 48, goldColor);
            context.fill(pmAvatarX + 48, pmAvatarY, pmAvatarX + 49, pmAvatarY + 48, goldColor);

            // Render Player ID & Rank under the avatar head
            String myRank = getRankTier(myCurrentElo);
            context.centeredText(this.font, "§6★ " + this.playerId, pmAvatarX + 24, pmAvatarY + 54, 0xFFFFFFFF);
            context.centeredText(this.font, "§e" + myRank, pmAvatarX + 24, pmAvatarY + 65, 0xFFFFFFFF);

            // Render player personal stats nicely aligned on the right side
            int pmStatsX = oLeft + 90;
            int startStatsY = oTop + 72;

            String changeStr = myCurrentChange > 0 ? "§a+" + myCurrentChange : (myCurrentChange < 0 ? "§c" + myCurrentChange : "§70");
            context.text(this.font, "§eELO Rating: §f" + myCurrentElo + " §7(" + changeStr + "§7)", pmStatsX, startStatsY, 0xFFFFFFFF);
            context.text(this.font, "§7Total Matches: §f" + myTotalMatches, pmStatsX, startStatsY + 16, 0xFFFFFFFF);
            context.text(this.font, "§7Total Wins: §a" + myWins + " Wins", pmStatsX, startStatsY + 32, 0xFFFFFFFF);
            context.text(this.font, "§7Total Losses: §c" + myLosses + " Losses", pmStatsX, startStatsY + 48, 0xFFFFFFFF);
            context.text(this.font, "§7Win Rate: §b" + String.format("%.1f%%", myWinRate), pmStatsX, startStatsY + 64, 0xFFFFFFFF);

            // Ensure buttons visibility matches overlay status on rendering ticks
            this.updateButtonsVisibility();

            // Render our custom close button on top
            if (this.postMatchCloseButton != null) {
                this.postMatchCloseButton.extractRenderState(context, mouseX, mouseY, delta);
            }
        } else {
            // Regularly ensure default buttons are active if overlay is inactive
            this.updateButtonsVisibility();
        }

        // 4. Render Personal Detailed Profile Overlay Card if active
        if (this.showDetailedProfile) {
            // Draw full-screen translucent dark backing
            context.fill(0, 0, this.width, this.height, 0xBB050507);

            int ovWidth = 320;
            int ovHeight = 220;
            int ovX = centerX - ovWidth / 2;
            int ovY = centerY - ovHeight / 2 - 10;

            int oLeft = ovX;
            int oRight = ovX + ovWidth;
            int oTop = ovY;
            int oBottom = ovY + ovHeight;

            // Backing and gold border
            context.fill(oLeft - 1, oTop - 1, oRight + 1, oBottom + 1, 0xFF000000);
            context.fill(oLeft + 1, oTop + 1, oRight - 1, oBottom - 1, 0xCC0B0B0F);

            context.fill(oLeft, oTop, oLeft + 1, oBottom, goldColor);       // Left Edge
            context.fill(oRight - 1, oTop, oRight, oBottom, goldColor);     // Right Edge
            context.fill(oLeft, oTop, oRight, oTop + 1, goldColor);         // Top Edge
            context.fill(oLeft, oBottom - 1, oRight, oBottom, goldColor);   // Bottom Edge

            // Title
            context.centeredText(this.font, "§6§l★ PERSONAL PROFILE ★", centerX, oTop + 12, 0xFFFFFFFF);

            // Clean divider
            context.horizontalLine(oLeft + 15, oRight - 15, oTop + 24, 0x55D4AF37);

            // Fetch stats dynamically based on the selected kit tab
            int myElo = 100;
            int myWins = 0;
            int myLosses = 0;
            int myRankPos = 1;

            if (this.selectedProfileKit.equalsIgnoreCase("overall")) {
                myElo = this.playerElo;
                myWins = this.playerWins;
                myLosses = this.playerLosses;
                myRankPos = this.kitRanks.getOrDefault("overall", 1);
            } else {
                String kitKey = this.selectedProfileKit.toLowerCase();
                myElo = this.kitElos.getOrDefault(kitKey, 100);
                myWins = this.kitWins.getOrDefault(kitKey, 0);
                myLosses = this.kitLosses.getOrDefault(kitKey, 0);
                myRankPos = this.kitRanks.getOrDefault(kitKey, 1);
            }

            int myTotalMatches = myWins + myLosses;
            double myWinRate = myTotalMatches > 0 ? (myWins * 100.0) / myTotalMatches : 0.0;

            // Render Player 2D Face Avatar on the left side (larger 48x48)
            int profAvatarX = oLeft + 20;
            int profAvatarY = oTop + 72;
            Identifier profSkinTex = getPlayerSkin();
            if (profSkinTex != null) {
                context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, profSkinTex, profAvatarX, profAvatarY, 8.0f, 8.0f, 48, 48, 8, 8, 64, 64, 0xFFFFFFFF);
                context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, profSkinTex, profAvatarX, profAvatarY, 40.0f, 8.0f, 48, 48, 8, 8, 64, 64, 0xFFFFFFFF);
            } else {
                context.fill(profAvatarX, profAvatarY, profAvatarX + 48, profAvatarY + 48, 0xFF444444);
            }

            // Draw gold frame border around the avatar head
            context.fill(profAvatarX - 1, profAvatarY - 1, profAvatarX + 49, profAvatarY, goldColor);
            context.fill(profAvatarX - 1, profAvatarY + 48, profAvatarX + 49, profAvatarY + 49, goldColor);
            context.fill(profAvatarX - 1, profAvatarY, profAvatarX, profAvatarY + 48, goldColor);
            context.fill(profAvatarX + 48, profAvatarY, profAvatarX + 49, profAvatarY + 48, goldColor);

            // Render Username & Category Title under avatar
            context.centeredText(this.font, "§6★ " + this.playerId, profAvatarX + 24, profAvatarY + 54, 0xFFFFFFFF);
            context.centeredText(this.font, "§7Class: §e" + this.selectedProfileKit.toUpperCase(), profAvatarX + 24, profAvatarY + 65, 0xFFFFFFFF);

            // Interactive Kit Filter Tabs (positioned at oTop + 32)
            String[ ] kitNames = {"Overall", "Crystal", "UHC", "Pot", "Mace", "Sword"};
            int tabW = 42;
            int tabH = 14;
            int tabSpacing = 4;
            int totalTabsW = kitNames.length * tabW + (kitNames.length - 1) * tabSpacing;
            int tabStartX = centerX - totalTabsW / 2;

            for (int i = 0; i < kitNames.length; i++) {
                String kName = kitNames[i];
                int tx = tabStartX + i * (tabW + tabSpacing);
                int ty = oTop + 32;

                boolean isSelected = this.selectedProfileKit.equalsIgnoreCase(kName);

                // Render tab backing and border
                context.fill(tx, ty, tx + tabW, ty + tabH, isSelected ? 0x66D4AF37 : 0xFF222224);
                context.horizontalLine(tx, tx + tabW, ty, isSelected ? goldColor : 0xFF444446);
                context.horizontalLine(tx, tx + tabW, ty + tabH, isSelected ? goldColor : 0xFF444446);
                context.verticalLine(tx, ty, ty + tabH, isSelected ? goldColor : 0xFF444446);
                context.verticalLine(tx + tabW, ty, ty + tabH, isSelected ? goldColor : 0xFF444446);

                // Render centered tab text
                context.centeredText(this.font, "§f" + kName, tx + tabW / 2, ty + 3, 0xFFFFFFFF);
            }

            // Render stats list nicely spaced on the right side
            int profStatsX = oLeft + 90;
            int startStatsY = oTop + 72;
            String myRank = getRankTier(myElo);

            context.text(this.font, "§eLeaderboard Rank: §f#" + myRankPos, profStatsX, startStatsY, 0xFFFFFFFF);
            context.text(this.font, "§eELO Rating: §f" + myElo + " §7(" + myRank + "§7)", profStatsX, startStatsY + 16, 0xFFFFFFFF);
            context.text(this.font, "§7Total Matches: §f" + myTotalMatches, profStatsX, startStatsY + 32, 0xFFFFFFFF);
            context.text(this.font, "§7Total Wins: §a" + myWins + " Wins", profStatsX, startStatsY + 48, 0xFFFFFFFF);
            context.text(this.font, "§7Total Losses: §c" + myLosses + " Losses", profStatsX, startStatsY + 64, 0xFFFFFFFF);
            context.text(this.font, "§7Win Rate: §b" + String.format("%.1f%%", myWinRate), profStatsX, startStatsY + 80, 0xFFFFFFFF);

            // Draw a Close button at the bottom of the card
            int closeBtnX = centerX - 50;
            int closeBtnY = oTop + ovHeight - 25;
            
            // Render the Close button manually using fill and border
            context.fill(closeBtnX, closeBtnY, closeBtnX + 100, closeBtnY + 18, 0xFF222224);
            context.horizontalLine(closeBtnX, closeBtnX + 100, closeBtnY, 0xFF444446);
            context.horizontalLine(closeBtnX, closeBtnX + 100, closeBtnY + 18, 0xFF444446);
            context.verticalLine(closeBtnX, closeBtnY, closeBtnY + 18, 0xFF444446);
            context.verticalLine(closeBtnX + 100, closeBtnY, closeBtnY + 18, 0xFF444446);
            
            context.centeredText(this.font, "§cClose", centerX, closeBtnY + 5, 0xFFFFFFFF);
        }
    }

                private Identifier getPlayerSkin() {
        if (this.minecraft != null && this.minecraft.getUser() != null) {
            try {
                com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(this.minecraft.getUser().getProfileId(), this.minecraft.getUser().getName());
                java.util.function.Supplier<net.minecraft.world.entity.player.PlayerSkin> supplier = this.minecraft.getSkinManager().createLookup(profile, true);
                if (supplier != null) {
                    net.minecraft.world.entity.player.PlayerSkin skin = supplier.get();
                    if (skin != null && skin.body() != null) {
                        return skin.body().texturePath();
                    }
                }
            } catch (Exception e) {
                DebugLogger.log("[UI] Error fetching player skin: " + e.getMessage());
            }
        }
        return null;
    }

    private void saveStatsLocally(String pId, int elo, int wins, int losses, int streak, int maxStreak) {
        try {
            java.io.File file = new java.io.File("p2p_player_cache.json");
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("player_id", pId);
            obj.addProperty("elo", elo);
            obj.addProperty("wins", wins);
            obj.addProperty("losses", losses);
            obj.addProperty("streak", streak);
            obj.addProperty("max_streak", maxStreak);
            obj.addProperty("rank", this.kitRanks.getOrDefault("overall", 1));

            com.google.gson.JsonObject kitsObj = new com.google.gson.JsonObject();
            for (String k : java.util.Arrays.asList("crystal", "uhc", "pot", "mace", "sword")) {
                com.google.gson.JsonObject kData = new com.google.gson.JsonObject();
                kData.addProperty("elo", this.kitElos.getOrDefault(k, 100));
                kData.addProperty("wins", this.kitWins.getOrDefault(k, 0));
                kData.addProperty("losses", this.kitLosses.getOrDefault(k, 0));
                kData.addProperty("rank", this.kitRanks.getOrDefault(k, 1));
                kitsObj.add(k, kData);
            }
            obj.add("kits", kitsObj);

            try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
                writer.write(obj.toString());
            }
        } catch (Exception e) {
            DebugLogger.log("[UI] Error saving local stats cache: " + e.getMessage());
        }
    }

    private void loadStatsLocally(String pId) {
        try {
            java.io.File file = new java.io.File("p2p_player_cache.json");
            if (file.exists()) {
                try (java.io.FileReader reader = new java.io.FileReader(file)) {
                    com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                    String cachedId = obj.has("player_id") ? obj.get("player_id").getAsString() : "";
                    if (cachedId.equalsIgnoreCase(pId)) {
                        if (obj.has("elo")) this.playerElo = obj.get("elo").getAsInt();
                        if (obj.has("wins")) this.playerWins = obj.get("wins").getAsInt();
                        if (obj.has("losses")) this.playerLosses = obj.get("losses").getAsInt();
                        if (obj.has("streak")) this.playerStreak = obj.get("streak").getAsInt();
                        if (obj.has("max_streak")) this.playerMaxStreak = obj.get("max_streak").getAsInt();
                        if (obj.has("rank")) this.kitRanks.put("overall", obj.get("rank").getAsInt());
                        if (obj.has("kits")) {
                            com.google.gson.JsonObject kitsObj = obj.getAsJsonObject("kits");
                            for (String k : java.util.Arrays.asList("crystal", "uhc", "pot", "mace", "sword")) {
                                if (kitsObj.has(k)) {
                                    com.google.gson.JsonObject kData = kitsObj.getAsJsonObject(k);
                                    if (kData.has("elo")) this.kitElos.put(k, kData.get("elo").getAsInt());
                                    if (kData.has("wins")) this.kitWins.put(k, kData.get("wins").getAsInt());
                                    if (kData.has("losses")) this.kitLosses.put(k, kData.get("losses").getAsInt());
                                    if (kData.has("rank")) this.kitRanks.put(k, kData.get("rank").getAsInt());
                                }
                            }
                        }
                        DebugLogger.log("[UI] Successfully loaded stats from local cache file: ELO=" + this.playerElo + ", W=" + this.playerWins + ", L=" + this.playerLosses + ", Streak=" + this.playerStreak + ", MaxStreak=" + this.playerMaxStreak);
                    }
                }
            }
        } catch (Exception e) {
            DebugLogger.log("[UI] Error loading local stats cache: " + e.getMessage());
        }
    }
    @org.spongepowered.asm.mixin.injection.Inject(at = @At("HEAD"), method = "mouseClicked", cancellable = true)
    private void onMouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean someBool, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        if (com.p2ppvp.mod.client.P2PPvpModClient.showPostMatchOverlay) {
            long elapsed = System.currentTimeMillis() - com.p2ppvp.mod.client.P2PPvpModClient.overlayOpenTime;
            boolean animCompleted = elapsed >= 3000;
            if (animCompleted) {
                int centerX = this.width / 2;
                int centerY = this.height / 2;
                int btnX = centerX - 60;
                int btnY = centerY + 55;
                int btnW = 120;
                int btnH = 20;
                if (mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH) {
                    com.p2ppvp.mod.client.P2PPvpModClient.showPostMatchOverlay = false;
                    this.updateButtonsVisibility();
                    this.registerPlayerAsync(this.playerId);
                    this.minecraft.getSoundManager().play(
                        net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f
                        )
                    );
                    cir.setReturnValue(true);
                }
            }
            cir.setReturnValue(true);
            return;
        }

        if (this.showDetailedProfile) {
            int centerX = this.width / 2;
            int centerY = this.height / 2;
            int ovWidth = 320;
            int ovHeight = 220;
            int ovX = centerX - ovWidth / 2;
            int ovY = centerY - ovHeight / 2 - 10;
            
            // Check kit tabs clicks (ty = ovY + 32)
            String[ ] kitNames = {"Overall", "Crystal", "UHC", "Pot", "Mace", "Sword"};
            int tabW = 42;
            int tabH = 14;
            int tabSpacing = 4;
            int totalTabsW = kitNames.length * tabW + (kitNames.length - 1) * tabSpacing;
            int tabStartX = centerX - totalTabsW / 2;
            
            for (int i = 0; i < kitNames.length; i++) {
                int tx = tabStartX + i * (tabW + tabSpacing);
                int ty = ovY + 32;
                if (mouseX >= tx && mouseX <= tx + tabW && mouseY >= ty && mouseY <= ty + tabH) {
                    this.selectedProfileKit = kitNames[i];
                    this.minecraft.getSoundManager().play(
                        net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f
                        )
                    );
                    cir.setReturnValue(true);
                    return;
                }
            }

            // Check if they clicked the Close button on the detailed profile
            int closeBtnX = centerX - 50;
            int closeBtnY = ovY + ovHeight - 25;
            if (mouseX >= closeBtnX && mouseX <= closeBtnX + 100 && mouseY >= closeBtnY && mouseY <= closeBtnY + 18) {
                this.showDetailedProfile = false;
                this.updateButtonsVisibility();
                this.minecraft.getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f
                    )
                );
                cir.setReturnValue(true);
                return;
            }
            
            // Close if they click outside the card boundaries
            if (mouseX < ovX || mouseX > ovX + ovWidth || mouseY < ovY || mouseY > ovY + ovHeight) {
                this.showDetailedProfile = false;
                this.updateButtonsVisibility();
                this.minecraft.getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f
                    )
                );
                cir.setReturnValue(true);
                return;
            }
            cir.setReturnValue(true);
            return;
        }

        // Check if player clicked the Profile Card at the top-right
        int profileWidth = 150;
        int profileHeight = 50;
        int profileX = this.width - profileWidth - 10;
        int profileY = 10;
        if (mouseX >= profileX && mouseX <= profileX + profileWidth && mouseY >= profileY && mouseY <= profileY + profileHeight) {
            this.showDetailedProfile = true;
            this.updateButtonsVisibility();
            this.minecraft.getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f
                )
            );
            cir.setReturnValue(true);
        }
    }

    private String getRankTier(int elo) {
        if (elo < 100) return "§8Unranked";
        if (elo < 500) return "§6Bronze";
        if (elo < 1000) return "§7Silver";
        if (elo < 1500) return "§eGold";
        if (elo < 2000) return "§bDiamond";
        return "§5Netherite";
    }
}
