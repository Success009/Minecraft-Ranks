package com.p2ppvp.mod.customkit;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class KitEditorManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("p2ppvp-kiteditor");
    public static volatile boolean isEditorActive = false;
    private static final Set<UUID> exitPendingConfirmation = new HashSet<>();
    private static final Set<UUID> savedInSession = new HashSet<>();
    private static final Set<UUID> welcomedPlayers = new HashSet<>();

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("save")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String name = StringArgumentType.getString(context, "name").trim();
                        return handleSave(player, name);
                    })
                )
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    player.sendSystemMessage(Component.literal("§eTo save your kit, type: §6/save <kit_name> §eor §6/name <kit_name>"));
                    return 1;
                })
            );

            dispatcher.register(Commands.literal("name")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String name = StringArgumentType.getString(context, "name").trim();
                        return handleSave(player, name);
                    })
                )
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    player.sendSystemMessage(Component.literal("§eUsage: §6/name <kit_name>"));
                    return 1;
                })
            );

                        dispatcher.register(Commands.literal("exit")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    return handleExit(player);
                })
            );

            // Register /gamemode and /gm commands - strictly permitted inside Kit Creator
            for (String literal : new String[]{"gamemode", "gm"}) {
                dispatcher.register(Commands.literal(literal)
                    .then(Commands.argument("mode", StringArgumentType.word())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            if (!isEditorActive && !com.p2ppvp.mod.P2PPvpMod.isKitEditorServer(player.level().getServer())) {
                                player.sendSystemMessage(Component.literal("§cGamemode change is only permitted in the Kit Creator sandbox!"));
                                return 0;
                            }
                            String modeStr = StringArgumentType.getString(context, "mode").toLowerCase();
                            GameType target;
                            switch (modeStr) {
                                case "survival":
                                case "s":
                                case "0":
                                    target = GameType.SURVIVAL;
                                    break;
                                case "creative":
                                case "c":
                                case "1":
                                    target = GameType.CREATIVE;
                                    break;
                                case "adventure":
                                case "a":
                                case "2":
                                    target = GameType.ADVENTURE;
                                    break;
                                case "spectator":
                                case "sp":
                                case "3":
                                    target = GameType.SPECTATOR;
                                    break;
                                default:
                                    player.sendSystemMessage(Component.literal("§cUnknown gamemode: " + modeStr + ". Options: survival, creative, adventure, spectator"));
                                    return 0;
                            }
                            player.setGameMode(target);
                            player.sendSystemMessage(Component.literal("§aGame mode updated to §e" + target.getName()));
                            return 1;
                        })
                    )
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        player.sendSystemMessage(Component.literal("§eUsage: §6/" + literal + " <survival|creative|adventure|spectator>"));
                        return 1;
                    })
                );
            }
        });
    }

    private static int handleSave(ServerPlayer player, String name) {
        if (name == null || name.isEmpty()) {
            player.sendSystemMessage(Component.literal("§cKit name cannot be empty!"));
            return 0;
        }

        CustomKit kit = CustomKitManager.captureFromPlayer(player, name);
        if (kit.getItems().isEmpty()) {
            player.sendSystemMessage(Component.literal("§cYour inventory is empty! Add items to your kit before saving."));
            return 0;
        }

        CustomKitManager.saveCustomKit(kit);
        savedInSession.add(player.getUUID());
        exitPendingConfirmation.remove(player.getUUID());

        player.sendSystemMessage(Component.literal("§aKit '§e" + name + "§a' saved with " + kit.getItems().size() + " items!"));
        player.sendSystemMessage(Component.literal("§7Type §c/exit §7to return to menu."));
        return 1;
    }

    private static int handleExit(ServerPlayer player) {
        UUID uuid = player.getUUID();
        boolean hasItems = !player.getInventory().isEmpty();
        boolean wasSaved = savedInSession.contains(uuid);

        if (hasItems && !wasSaved && !exitPendingConfirmation.contains(uuid)) {
            exitPendingConfirmation.add(uuid);
            player.sendSystemMessage(Component.literal("§cYou have unsaved items. Type §4/exit §cagain to discard, or §6/save <name> §cto save."));
            return 1;
        }

        exitPendingConfirmation.remove(uuid);
        savedInSession.remove(uuid);
        welcomedPlayers.remove(uuid);
        isEditorActive = false;

                player.sendSystemMessage(Component.literal("§6Exiting kit creator..."));
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                try {
                    mc.disconnect(new net.minecraft.client.gui.screens.TitleScreen(), false);
                } catch (Exception e) {
                    LOGGER.error("Error disconnecting from kit editor: ", e);
                } finally {
                    Thread cleanup = new Thread(() -> {
                        try {
                            Thread.sleep(1200);
                            com.p2ppvp.mod.ArenaManager.deleteKitEditorWorld();
                        } catch (Exception ignored) {}
                    }, "P2PKitEditorCleanup");
                    cleanup.setDaemon(true);
                    cleanup.start();
                }
            });
        }
        return 1;
    }

    public static void setupPlayerInEditor(ServerPlayer player) {
        isEditorActive = true;
        if (player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            serverLevel.noSave = true;
        }
        applyEditorState(player);
        if (player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            net.minecraft.server.MinecraftServer server = serverLevel.getServer();
            if (server != null) {
                server.execute(() -> {
                    if (player.isAlive() && !player.hasDisconnected()) {
                        applyEditorState(player);
                    }
                });
            }
        }
    }

    private static void applyEditorState(ServerPlayer player) {
        UUID uuid = player.getUUID();
        exitPendingConfirmation.remove(uuid);
        savedInSession.remove(uuid);

        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.getAbilities().instabuild = true;
        player.getAbilities().invulnerable = true;
        player.onUpdateAbilities();

        if (player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            // Teleport to the center of the arena (0.0, -60.0, -43.0)
            player.teleportTo(serverLevel, 0.0, -60.0, -43.0, Collections.emptySet(), 0.0f, 0.0f, true);
        }

        // Clear default starting inventory so player can pick fresh items from Creative
        player.getInventory().clearContent();
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastFullState();

        if (welcomedPlayers.add(uuid)) {
            player.sendSystemMessage(Component.literal("§6§l[KIT CREATOR]"));
            player.sendSystemMessage(Component.literal("§fBuild your kit using the Creative inventory."));
            player.sendSystemMessage(Component.literal("§eType §a/save <name> §eto save your kit."));
            player.sendSystemMessage(Component.literal("§eType §c/exit §eto return to menu."));
        }
    }

    public static void launchEditor(Minecraft mc) {
        isEditorActive = true;
        Thread thread = new Thread(() -> {
            com.p2ppvp.mod.ArenaManager.prepareKitEditorWorld();
            mc.execute(() -> {
                try {
                    mc.createWorldOpenFlows().openWorld("p2p_kit_editor", () -> {
                        mc.setScreen(null);
                    });
                } catch (Exception e) {
                    LOGGER.error("Failed to open kit editor world: ", e);
                    isEditorActive = false;
                }
            });
        }, "P2PKitEditorLauncher");
        thread.setDaemon(true);
        thread.start();
    }
}
