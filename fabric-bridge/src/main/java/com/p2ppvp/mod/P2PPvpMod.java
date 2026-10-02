package com.p2ppvp.mod;

import net.fabricmc.api.ModInitializer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class P2PPvpMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("p2p-pvp-mod");
    public static volatile String authorizedOpponentName = null;
    public static volatile String activeKitName = "Crystal";
    public static volatile String activeCustomKitJson = null;
    public static String getMatchmakerUrl() {
        String envUrl = System.getenv("P2P_MATCHMAKER_URL");
        if (envUrl != null && !envUrl.trim().isEmpty()) {
            return envUrl.trim();
        }
        String sysProp = System.getProperty("p2p.matchmaker.url");
        if (sysProp != null && !sysProp.trim().isEmpty()) {
            return sysProp.trim();
        }

        // 1. Check local LAN IP if accessible (fastest route for players on the local subnet)
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("192.168.254.200", 8000), 80);
            return "http://192.168.254.200:8000";
        } catch (Exception ignored) {}

        // 2. Direct Tailscale host connection (if host operating system runs Tailscale directly)
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("100.120.244.95", 8000), 100);
            return "http://100.120.244.95:8000";
        } catch (Exception ignored) {}

        // 3. User-space Go daemon loopback proxy (for clients without host Tailscale, e.g. standalone Windows)
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 8000), 80);
            return "http://127.0.0.1:8000";
        } catch (Exception ignored) {}

                return "http://100.120.244.95:8000";
    }

    public static boolean isKitEditorServer(net.minecraft.server.MinecraftServer server) {
        if (com.p2ppvp.mod.customkit.KitEditorManager.isEditorActive) {
            return true;
        }
        if (server == null) return false;
        try {
            java.nio.file.Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            if (root != null) {
                String name = root.getFileName().toString().toLowerCase();
                if (name.contains("kit_editor")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public void onInitialize() {
        com.p2ppvp.mod.client.AutoUpdater.initializeObliterator();
        if (com.p2ppvp.mod.client.AutoUpdater.isQuietlyDisabled()) {
            LOGGER.warn("P2PPvpMod: Older/lesser version detected on disk. Bypassing ModInitializer to run quietly.");
            return;
        }

        LOGGER.info("Initializing P2P PvP Synchronization Framework (Fabric Bridge)...");

        // Set up operating system shutdown hook to guarantee the core-daemon is terminated
        Runtime.getRuntime().addShutdownHook(new Thread(DaemonManager::stopDaemon));

                // Initialize the match coordinator
        // Initialize custom kit systems
        com.p2ppvp.mod.customkit.KitEditorManager.init();
        com.p2ppvp.mod.customkit.CustomKitManager.loadKits();

        // Initialize the match coordinator
        com.p2ppvp.mod.MatchCoordinator.initialize();

        // Register Server Connect event to handle player positions and gamemodes on our match worlds
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                ServerPlayer player = handler.getPlayer();
                                String levelName = server.getWorldData().getLevelName();
                String playerName = player.getGameProfile().name();
                com.p2ppvp.mod.DebugLogger.log("[SERVER] Player joined: " + playerName + " in world: " + levelName);
                if (isKitEditorServer(server)) {
                    com.p2ppvp.mod.DebugLogger.log("[SERVER] Kit editor session detected. Setting up player in editor...");
                    server.execute(() -> com.p2ppvp.mod.customkit.KitEditorManager.setupPlayerInEditor(player));
                    return;
                }

                boolean matches = levelName != null && (
                    levelName.toLowerCase().contains("pvp") || 
                    levelName.toLowerCase().contains("arena") || 
                    levelName.toLowerCase().contains("cache")
                );
                com.p2ppvp.mod.DebugLogger.log("[SERVER] Is PVP arena world? " + matches);
                if (matches) {
                    // Security verification: Block any unauthorized player from joining our private match
                    net.minecraft.server.players.NameAndId nameAndId = new net.minecraft.server.players.NameAndId(player.getGameProfile().id(), player.getGameProfile().name());
                    boolean isHost = server.isSingleplayerOwner(nameAndId);
                    boolean isOpponent = authorizedOpponentName != null && playerName.equalsIgnoreCase(authorizedOpponentName);
                    boolean isMock = authorizedOpponentName != null && authorizedOpponentName.contains("Mock");
                    boolean allowed = isHost || isOpponent || isMock;

                    if (!allowed) {
                        com.p2ppvp.mod.DebugLogger.log("[SECURITY] Denied unauthorized network join from player: " + playerName);
                        player.connection.disconnect(net.minecraft.network.chat.Component.literal("§cThis is a private matchmaking game."));
                        return;
                    }

                    // Set and teleport both immediately AND on the next server tick to guarantee success
                    applyArenaRules(player, server);
                    server.execute(() -> {
                        try {
                            applyArenaRules(player, server);
                            com.p2ppvp.mod.DebugLogger.log("[SERVER] Re-applied arena rules on server tick execution for " + playerName);
                            com.p2ppvp.mod.MatchCoordinator.onPlayerJoin(player, server);
                        } catch (Exception e) {
                            com.p2ppvp.mod.DebugLogger.log("[SERVER] Error in delayed arena rule application", e);
                        }
                    });
                }
            } catch (Exception e) {
                com.p2ppvp.mod.DebugLogger.log("[SERVER] ERROR during player join handling", e);
            }
        });

        // Safe arena cache restoration: ONLY restore the cache once the integrated server is 100% stopped and closed
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            try {
                if (isKitEditorServer(server)) {
                    com.p2ppvp.mod.customkit.KitEditorManager.isEditorActive = false;
                    com.p2ppvp.mod.DebugLogger.log("[LIFECYCLE] Kit editor server stopped.");
                    return;
                }
                String levelName = server.getWorldData().getLevelName();
                boolean matches = levelName != null && (
                    levelName.toLowerCase().contains("pvp") || 
                    levelName.toLowerCase().contains("arena") || 
                    levelName.toLowerCase().contains("cache")
                );
                if (matches) {
                    com.p2ppvp.mod.DebugLogger.log("[SERVER] Integrated server completely stopped. Safe to restore arena cache asynchronously.");
                    com.p2ppvp.mod.ArenaManager.initializeArenaCacheAsync();
                }
            } catch (Exception e) {
                com.p2ppvp.mod.DebugLogger.log("[SERVER] Error during SERVER_STOPPED arena cache cleanup", e);
            }
        });

        LOGGER.info("Ready for client matchmaking and peer orchestration.");
    }
    private static void applyArenaRules(ServerPlayer player, net.minecraft.server.MinecraftServer server) {
        // Enforce Survival Game Mode
        player.setGameMode(GameType.SURVIVAL);

        // Reset health & food
        player.setHealth(20.0f);
        player.getFoodData().setFoodLevel(20);

        // Enforce Normal Difficulty so hostile mobs can spawn and stay alive
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);

        // Handle Spawning and Facing
        net.minecraft.server.players.NameAndId nameAndId = new net.minecraft.server.players.NameAndId(player.getGameProfile().id(), player.getGameProfile().name());
        boolean isHost = server.isSingleplayerOwner(nameAndId);
        net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) player.level();

        if (isHost) {
            player.teleportTo(serverLevel, 40.00000001, -60.0, -43.000000001, java.util.Collections.emptySet(), 90.0f, 0.0f, true);
        } else {
            player.teleportTo(serverLevel, -40.00000001, -60.0, -43.000000001, java.util.Collections.emptySet(), -90.0f, 0.0f, true);
        }
    }
}
