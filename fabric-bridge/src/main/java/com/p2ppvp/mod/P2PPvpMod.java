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

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing P2P PvP Synchronization Framework (Fabric Bridge)...");

        // Set up operating system shutdown hook to guarantee the core-daemon is terminated
        Runtime.getRuntime().addShutdownHook(new Thread(DaemonManager::stopDaemon));

        // Initialize the match coordinator
        com.p2ppvp.mod.MatchCoordinator.initialize();

        // Register Server Connect event to handle player positions and gamemodes on our match worlds
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                ServerPlayer player = handler.getPlayer();
                String levelName = server.getWorldData().getLevelName();
                String playerName = player.getGameProfile().name();
                com.p2ppvp.mod.DebugLogger.log("[SERVER] Player joined: " + playerName + " in world: " + levelName);

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

        // Deop player
        server.getPlayerList().deop(nameAndId);
    }
}
