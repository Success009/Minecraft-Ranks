package com.p2ppvp.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.alchemy.PotionContents;
import java.util.Optional;
import net.minecraft.core.Holder;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MatchCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger("p2ppvp-match");

    public static volatile boolean matchRunning = false;
    public static volatile boolean countdownActive = false;
    public static volatile int countdownTicks = -1;
    public static volatile int endTicks = -1;
    public static volatile String currentWinner = null;
    public static volatile String currentLoser = null;
    public static volatile int joinDelayTicks = -1;

            public static void initialize() {
        // Register server-side tick handler
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(MatchCoordinator::onServerTick);
    }

        public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (P2PPvpMod.isKitEditorServer(server)) {
            return;
        }
        String levelName = server.getWorldData().getLevelName();
        boolean isPvPWorld = levelName != null && (
            levelName.toLowerCase().contains("pvp") || 
            levelName.toLowerCase().contains("arena") || 
            levelName.toLowerCase().contains("cache")
        );

        com.p2ppvp.mod.DebugLogger.log("[SERVER MatchCoordinator] onPlayerJoin event registered. levelName=" + levelName + ", isPvPWorld=" + isPvPWorld);
        if (isPvPWorld) {
            matchRunning = false;
            countdownActive = false;
            countdownTicks = -1;
            endTicks = -1;
            currentWinner = null;
            currentLoser = null;
            joinDelayTicks = -1; // Reset to -1 so threshold can trigger the terrain safety buffer
        }
    }

    private static void startCountdown(MinecraftServer server) {
        LOGGER.info("[MatchCoordinator] Target player count met. Starting 5-second countdown...");
        countdownTicks = 100; // 5 seconds * 20 ticks
        countdownActive = true;
        matchRunning = false;
        endTicks = -1;
        currentWinner = null;
        currentLoser = null;
        joinDelayTicks = -1; // Reset safety delay

                                // In solo mock matches, ensure activeKitName respects the user's selected kit from options
        if (P2PPvpMod.authorizedOpponentName != null && P2PPvpMod.authorizedOpponentName.toLowerCase().contains("mock")) {
            java.util.List<String> validKits = new java.util.ArrayList<>();
            if (com.p2ppvp.mod.client.MatchmakingOptionsScreen.selectedKits != null) {
                for (String k : com.p2ppvp.mod.client.MatchmakingOptionsScreen.selectedKits) {
                    if (!"Random".equalsIgnoreCase(k)) {
                        validKits.add(k);
                    }
                }
            }
            if (!validKits.isEmpty()) {
                P2PPvpMod.activeKitName = validKits.get(new java.util.Random().nextInt(validKits.size()));
            } else if (P2PPvpMod.activeKitName == null || P2PPvpMod.activeKitName.equalsIgnoreCase("random")) {
                String[] allKits = {"Crystal", "UHC", "Pot", "Mace", "Sword"};
                P2PPvpMod.activeKitName = allKits[new java.util.Random().nextInt(allKits.length)];
            }

            if ("Random Custom".equalsIgnoreCase(P2PPvpMod.activeKitName)) {
                java.util.List<com.p2ppvp.mod.customkit.CustomKit> ckList = com.p2ppvp.mod.customkit.CustomKitManager.getCustomKits();
                if (!ckList.isEmpty()) {
                    com.p2ppvp.mod.customkit.CustomKit picked = ckList.get(new java.util.Random().nextInt(ckList.size()));
                    P2PPvpMod.activeKitName = "Custom:" + picked.getName();
                    P2PPvpMod.activeCustomKitJson = picked.toJson().toString();
                } else {
                    P2PPvpMod.activeKitName = "Crystal";
                }
            } else if (P2PPvpMod.activeKitName != null && P2PPvpMod.activeKitName.startsWith("Custom:")) {
                String cName = P2PPvpMod.activeKitName.substring(7);
                com.p2ppvp.mod.customkit.CustomKit ck = com.p2ppvp.mod.customkit.CustomKitManager.getCustomKit(cName);
                if (ck != null) {
                    P2PPvpMod.activeCustomKitJson = ck.toJson().toString();
                }
            }
        }
        // Clean up any lingering mock opponent husks and stray items/projectiles from previous test sessions
        runCommand(server, "kill @e[tag=mock_opponent]");
        runCommand(server, "kill @e[type=item]");
        runCommand(server, "kill @e[type=experience_orb]");
        runCommand(server, "kill @e[type=arrow]");
        runCommand(server, "kill @e[type=spectral_arrow]");
        runCommand(server, "kill @e[type=end_crystal]");

        // Configure naturalRegeneration based on whether the active kit is UHC
        if (P2PPvpMod.activeKitName != null && P2PPvpMod.activeKitName.equalsIgnoreCase("uhc")) {
            runCommand(server, "gamerule naturalRegeneration false");
        } else {
            runCommand(server, "gamerule naturalRegeneration true");
        }

        // Apply kit, reset game mode to Survival, and lock players in place
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            String name = player.getGameProfile().name();

            // Explicitly restore Survival game mode and disable spectator flying
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.getAbilities().flying = false;
            player.onUpdateAbilities();

            // Clear fire, status effects, and arrows stuck in player
            player.clearFire();
            runCommand(server, "effect clear " + name);
            player.setArrowCount(0);

            // Teleport to spawn location immediately
            net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) player.level();
            net.minecraft.server.players.NameAndId nameAndId = new net.minecraft.server.players.NameAndId(player.getGameProfile().id(), player.getGameProfile().name());
            boolean isHost = server.isSingleplayerOwner(nameAndId);

            if (isHost) {
                player.teleportTo(level, 40.0, -60.0, -43.0, java.util.Collections.emptySet(), 90.0f, 0.0f, true);
            } else {
                player.teleportTo(level, -40.0, -60.0, -43.0, java.util.Collections.emptySet(), -90.0f, 0.0f, true);
            }

            // Clear current inventory completely
            player.getInventory().clearContent();
            player.containerMenu.broadcastChanges();
            player.inventoryMenu.broadcastFullState();
            runCommand(server, "clear " + name);

            // Apply selected kit
            applyKit(server, player, P2PPvpMod.activeKitName);

            // Turn on blindness for countdown duration
            runCommand(server, "effect give " + name + " minecraft:blindness 6 255 true");

            // Reset health and food
            player.setHealth(20.0f);
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(20.0f);
        }
    }

    
        private static void applyKit(MinecraftServer server, ServerPlayer player, String kitName) {
        String name = player.getGameProfile().name();

        // 1. Check if activeCustomKitJson is set
        if (P2PPvpMod.activeCustomKitJson != null && !P2PPvpMod.activeCustomKitJson.trim().isEmpty()) {
            try {
                JsonObject kitObj = JsonParser.parseString(P2PPvpMod.activeCustomKitJson).getAsJsonObject();
                com.p2ppvp.mod.customkit.CustomKit customKit = com.p2ppvp.mod.customkit.CustomKit.fromJson(kitObj);
                com.p2ppvp.mod.customkit.CustomKitManager.applyKitToPlayer(server, player, customKit);
                LOGGER.info("[MatchCoordinator] Applied active custom kit '" + customKit.getName() + "' to player: " + name);
                return;
            } catch (Exception e) {
                LOGGER.error("[MatchCoordinator] Failed to deserialize activeCustomKitJson: ", e);
            }
        }

        // 2. Check if kit name references a locally stored custom kit
        if (kitName != null && (kitName.toLowerCase().startsWith("custom") || kitName.toLowerCase().startsWith("custom:"))) {
            String customName = kitName.contains(":") ? kitName.substring(kitName.indexOf(':') + 1).trim() : kitName;
            com.p2ppvp.mod.customkit.CustomKit localKit = com.p2ppvp.mod.customkit.CustomKitManager.getCustomKit(customName);
            if (localKit != null) {
                com.p2ppvp.mod.customkit.CustomKitManager.applyKitToPlayer(server, player, localKit);
                LOGGER.info("[MatchCoordinator] Applied local custom kit '" + localKit.getName() + "' to player: " + name);
                return;
            }
        }

        String fileKit = kitName == null ? "pot" : kitName.toLowerCase();
        if (fileKit.equals("netpot")) {
            fileKit = "pot";
        }

        LOGGER.info("[MatchCoordinator] Loading kit: " + fileKit + " for player: " + name);

        try (InputStream is = MatchCoordinator.class.getResourceAsStream("/assets/p2ppvp/kits/" + fileKit + ".json")) {
            InputStream resourceStream = is;
            if (resourceStream == null) {
                LOGGER.warn("[MatchCoordinator] Kit resource not found: " + fileKit + ".json, falling back to pot.json");
                resourceStream = MatchCoordinator.class.getResourceAsStream("/assets/p2ppvp/kits/pot.json");
            }
            if (resourceStream == null) {
                LOGGER.error("[MatchCoordinator] Absolute failure: Pot kit fallback resource missing!");
                return;
            }

            JsonObject obj = JsonParser.parseReader(new InputStreamReader(resourceStream, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray items = obj.getAsJsonArray("items");

            var registryManager = server.registryAccess();
            var enchantmentRegistry = registryManager.lookup(Registries.ENCHANTMENT).orElseThrow();

            for (JsonElement itemElem : items) {
                JsonObject itemObj = itemElem.getAsJsonObject();
                int slot = itemObj.get("slot").getAsInt();
                String id = itemObj.get("id").getAsString();
                int count = itemObj.has("count") ? itemObj.get("count").getAsInt() : 1;

                Item item = BuiltInRegistries.ITEM.get(Identifier.tryParse(id)).map(Holder::value).orElse(null);
                if (item == null) {
                    LOGGER.warn("[MatchCoordinator] Item not found: " + id);
                    continue;
                }

                ItemStack stack = new ItemStack(item, count);

                // Handle potion contents
                if (itemObj.has("potion")) {
                    String potionId = itemObj.get("potion").getAsString();
                    var potionKey = ResourceKey.create(Registries.POTION, Identifier.tryParse(potionId));
                    var potionHolder = BuiltInRegistries.POTION.get(potionKey).orElse(null);
                    if (potionHolder != null) {
                        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potionHolder));
                    }
                }

                // Handle enchantments
                if (itemObj.has("enchantments")) {
                    ItemEnchantments.Mutable builder = new ItemEnchantments.Mutable(stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
                    JsonArray enchs = itemObj.getAsJsonArray("enchantments");
                    for (int i = 0; i < enchs.size(); i++) {
                        JsonObject enchObj = enchs.get(i).getAsJsonObject();
                        String  enchId = enchObj.get("id").getAsString();
                        int lvl =  enchObj.get("lvl").getAsInt();

                        var enchKey = ResourceKey.create(Registries.ENCHANTMENT, Identifier.tryParse(enchId));
                        var enchantmentHolder = enchantmentRegistry.get(enchKey).orElse(null);
                        if (enchantmentHolder != null) {
                            builder.set(enchantmentHolder, lvl);
                        }
                    }
                    stack.set(DataComponents.ENCHANTMENTS, builder.toImmutable());
                }

                // Inject into inventory safely using direct memory writes
                Inventory inventory = player.getInventory();
                if (slot >= 0 && slot < inventory.getContainerSize()) {
                    inventory.setItem(slot, stack);
                }
            }

            // Sync and send client updates to completely prevent desyncs/invisible items
            player.containerMenu.broadcastChanges();
            player.inventoryMenu.broadcastFullState();

        } catch (Exception e) {
            LOGGER.error("[MatchCoordinator] Failed to load and apply kit: " + fileKit, e);
        }
    }


        private static void onServerTick(MinecraftServer server) {
        if (P2PPvpMod.isKitEditorServer(server)) return;
        String levelName = server.getWorldData().getLevelName();
        boolean isPvPWorld = levelName != null && (
            levelName.toLowerCase().contains("pvp") || 
            levelName.toLowerCase().contains("arena") || 
            levelName.toLowerCase().contains("cache")
        );

        if (!isPvPWorld) return;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        // 0. Auto-start match if not running and player threshold met
        if (!matchRunning && !countdownActive && endTicks < 0) {
            boolean isSolo = P2PPvpMod.authorizedOpponentName != null && P2PPvpMod.authorizedOpponentName.contains("Mock");
            int playersNeeded = isSolo ? 1 : 2;
            if (players.size() >= playersNeeded) {
                // Ensure all players have fully connected and ticked for at least 80 ticks (4 seconds)
                boolean allReady = true;
                for (ServerPlayer p : players) {
                    if (p.tickCount < 80) {
                        allReady = false;
                        break;
                    }
                }
                if (allReady) {
                    if (joinDelayTicks < 0) {
                        joinDelayTicks = 40; // Exact 2-second terrain-load safety delay
                        com.p2ppvp.mod.DebugLogger.log("[SERVER MatchCoordinator] Player threshold met and all players loaded. Starting 2-second safety buffer...");
                    }
                } else {
                    joinDelayTicks = -1;
                }
            } else {
                joinDelayTicks = -1;
            }
        }

        // Process active terrain safety buffer
        if (!matchRunning && !countdownActive && endTicks < 0 && joinDelayTicks >= 0) {
            if (joinDelayTicks > 0) {
                joinDelayTicks--;
                return;
            }
            startCountdown(server);
        }

        // 1. Process active countdown locking and titles
        if (countdownActive) {
            // Force freeze position on every tick to lock players in spawn boxes
            for (ServerPlayer player : players) {
                net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) player.level();
                net.minecraft.server.players.NameAndId nameAndId = new net.minecraft.server.players.NameAndId(player.getGameProfile().id(), player.getGameProfile().name());
                boolean isHost = server.isSingleplayerOwner(nameAndId);

                if (isHost) {
                    player.teleportTo(level, 40.0, -60.0, -43.0, java.util.Collections.emptySet(), 90.0f, 0.0f, true);
                } else {
                    player.teleportTo(level, -40.0, -60.0, -43.0, java.util.Collections.emptySet(), -90.0f, 0.0f, true);
                }
            }

            if (countdownTicks % 20 == 0) {
                int secs = countdownTicks / 20;
                if (secs > 0) {
                    runCommand(server, "title @a title {\"text\":\"" + secs + "\",\"color\":\"gold\",\"bold\":true}");
                    runCommand(server, "execute as @a at @s run playsound minecraft:block.note_block.bell master @s ~ ~ ~ 1 1");
                }
            }

            countdownTicks--;

                        if (countdownTicks <= 0) {
                countdownActive = false;
                matchRunning = true;
                for (ServerPlayer player : players) {
                    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                    player.getAbilities().flying = false;
                    player.onUpdateAbilities();
                }
                runCommand(server, "title @a title {\"text\":\"FIGHT!\",\"color\":\"red\",\"bold\":true}");
                runCommand(server, "title @a subtitle {\"text\":\"May the best player win!\",\"color\":\"gray\"}");
                runCommand(server, "execute as @a at @s run playsound minecraft:event.raid.horn master @s ~ ~ ~ 1.5 1");
                runCommand(server, "execute as @a at @s run playsound minecraft:entity.wither.spawn master @s ~ ~ ~ 1.0 1");
                runCommand(server, "effect clear @a minecraft:blindness");
                LOGGER.info("[MatchCoordinator] Match officially started!");
            }
        }

                // 2. Process active match victory/defeat evaluation
        if (matchRunning) {
            for (ServerPlayer player : players) {
                if (player.getHealth() < 1.0f) {
                    // If holding a Totem of Undying, do NOT resolve match
                    if (player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND).is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING) ||
                        player.getItemInHand(net.minecraft.world.InteractionHand.OFF_HAND).is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) {
                        continue;
                    }

                    // Match Over! This player has lost!
                    String loserName = player.getGameProfile().name();
                    String winnerName = null;

                    for (ServerPlayer p : players) {
                        if (!p.getGameProfile().name().equalsIgnoreCase(loserName)) {
                            winnerName = p.getGameProfile().name();
                            break;
                        }
                    }

                    // Fallback winner if solo mock test
                    if (winnerName == null) {
                        winnerName = "Mock_Opponent";
                    }

                    resolveMatch(server, winnerName, loserName);
                    break;
                }
            }
        }

        // 3. Process end match delay and safe disconnection
        if (endTicks > 0) {
            endTicks--;
            if (endTicks <= 0) {
                LOGGER.info("[MatchCoordinator] Terminating world and issuing safe match resolution disconnections.");

                String reasonString = "MATCH_RESOLVED:Winner=" + currentWinner + ":Loser=" + currentLoser + ":Kit=" + P2PPvpMod.activeKitName;
                Component disconnectReason = Component.literal(reasonString);

                List<ServerPlayer> playersCopy = new java.util.ArrayList<>(server.getPlayerList().getPlayers());
                for (ServerPlayer player : playersCopy) {
                    try {
                        player.connection.disconnect(disconnectReason);
                    } catch (Exception e) {
                        LOGGER.error("[MatchCoordinator] Error disconnecting player " + player.getGameProfile().name(), e);
                    }
                }
                // Clear state
                endTicks = -1;
                matchRunning = false;
                countdownActive = false;
            }
        }
    }

    public static void resolveMatch(MinecraftServer server, String winner, String loser) {
        boolean isMock = "Mock_Opponent".equalsIgnoreCase(winner) || "Mock_Opponent".equalsIgnoreCase(loser);
        if (isMock) {
            LOGGER.info("[MatchCoordinator] Solo mock test detected - suppressing disconnect and stats reporting; resetting round.");
            matchRunning = false;
            countdownActive = false;
            endTicks = -1;
            joinDelayTicks = 40; // 2 seconds delay before restarting countdown
            for (ServerPlayer player : new java.util.ArrayList<>(server.getPlayerList().getPlayers())) {
                try {
                    player.getInventory().clearContent();
                    player.containerMenu.broadcastChanges();
                    player.inventoryMenu.broadcastFullState();
                } catch (Exception e) {
                    LOGGER.error("[MatchCoordinator] Error clearing inventory for player", e);
                }
            }

            ServerPlayer loserPlayer = server.getPlayerList().getPlayerByName(loser);
            if (loserPlayer != null) {
                loserPlayer.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                loserPlayer.setHealth(20.0f);
                runCommand(server, "title " + loser + " title {\"text\":\"RESPAWNING...\",\"color\":\"gold\",\"bold\":true}");
                runCommand(server, "title " + loser + " subtitle {\"text\":\"Starting next round\",\"color\":\"gray\"}");
            }

            ServerPlayer winnerPlayer = server.getPlayerList().getPlayerByName(winner);
            if (winnerPlayer != null) {
                runCommand(server, "title " + winner + " title {\"text\":\"ROUND WON!\",\"color\":\"green\",\"bold\":true}");
                runCommand(server, "title " + winner + " subtitle {\"text\":\"Starting next round\",\"color\":\"gray\"}");
                runCommand(server, "execute at " + winner + " run playsound minecraft:ui.toast.challenge_complete master @s ~ ~ ~ 1 1");
            }
            return;
        }

        matchRunning = false;
        countdownActive = false;
        endTicks = 100; // 5-second celebration delay
        currentWinner = winner;
        currentLoser = loser;

        LOGGER.info("[MatchCoordinator] Match resolved. Winner: " + winner + ", Loser: " + loser);

        for (ServerPlayer player : new java.util.ArrayList<>(server.getPlayerList().getPlayers())) {
            try {
                player.getInventory().clearContent();
                player.containerMenu.broadcastChanges();
                player.inventoryMenu.broadcastFullState();
            } catch (Exception e) {
                LOGGER.error("[MatchCoordinator] Error clearing inventory for player", e);
            }
        }
        // Turn loser into spectator and full heal (if they are an actual player)
        ServerPlayer loserPlayer = server.getPlayerList().getPlayerByName(loser);
        if (loserPlayer != null) {
            loserPlayer.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            loserPlayer.setHealth(20.0f);
            runCommand(server, "execute at " + loser + " run summon minecraft:lightning_bolt ~ ~ ~");
            runCommand(server, "title " + loser + " title {\"text\":\"GAME OVER\",\"color\":\"red\",\"bold\":true}");
            runCommand(server, "title " + loser + " subtitle {\"text\":\"You lost the match\",\"color\":\"gray\"}");
        }

        // Clean up any surviving mock opponents
        runCommand(server, "kill @e[tag=mock_opponent]");

        // Apply temporary level 255 Resistance and Fire Resistance effects to the winning player to protect them from lightning
        runCommand(server, "effect give " + winner + " minecraft:resistance 3 255 true");
        runCommand(server, "effect give " + winner + " minecraft:fire_resistance 3 255 true");

        // Set titles to winner
        runCommand(server, "title " + winner + " title {\"text\":\"VICTORY!\",\"color\":\"green\",\"bold\":true}");
        runCommand(server, "title " + winner + " subtitle {\"text\":\"You won the match\",\"color\":\"gray\"}");
        runCommand(server, "execute at " + winner + " run playsound minecraft:ui.toast.challenge_complete master @s ~ ~ ~ 1 1");
    }
    public static void runCommand(MinecraftServer server, String cmd) {
        try {
            server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(),
                cmd
            );
        } catch (Exception e) {
            LOGGER.error("[MatchCoordinator] Failed to execute server command: " + cmd, e);
        }
    }
}
