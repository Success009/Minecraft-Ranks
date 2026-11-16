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
    /** When non-null the editor is arranging this ranked kit (lowercase) instead of building a custom kit. */
    public static volatile String layoutKit = null;
    private static final Set<UUID> exitPendingConfirmation = new HashSet<>();
    private static final Set<UUID> savedInSession = new HashSet<>();
    private static final Set<UUID> welcomedPlayers = new HashSet<>();

    public static void init() {
        registerLayoutLocks();
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
                    if (layoutKit != null) return handleLayoutSave(player);
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

                        dispatcher.register(Commands.literal("reset")
                .executes(context -> handleLayoutReset(context.getSource().getPlayerOrException())));

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

    private static java.io.File layoutFile() { return new java.io.File("nml_inventory_layout.json"); }

    private static com.google.gson.JsonObject readLayouts() {
        try {
            java.io.File f = layoutFile();
            if (!f.exists()) return new com.google.gson.JsonObject();
            return com.p2ppvp.mod.client.ui.KitLayout.parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { return new com.google.gson.JsonObject(); }
    }

    private static void writeLayouts(com.google.gson.JsonObject root) throws java.io.IOException {
        java.nio.file.Files.write(layoutFile().toPath(), root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static volatile String[] baselineKeys = null;
    private static volatile net.minecraft.world.item.ItemStack[] lastValid = null;

    private static String[] snapshotKeys(ServerPlayer player) {
        var inv = player.getInventory();
        String[] out = new String[com.p2ppvp.mod.client.ui.LayoutGuard.SIZE];
        for (int i = 0; i < out.length && i < inv.getContainerSize(); i++) {
            var st = inv.getItem(i);
            out[i] = st.isEmpty() ? null : itemKey(st) + "x" + st.getCount();
        }
        return out;
    }

    private static net.minecraft.world.item.ItemStack[] snapshotStacks(ServerPlayer player) {
        var inv = player.getInventory();
        var out = new net.minecraft.world.item.ItemStack[com.p2ppvp.mod.client.ui.LayoutGuard.SIZE];
        for (int i = 0; i < out.length; i++) {
            out[i] = i < inv.getContainerSize() ? inv.getItem(i).copy() : net.minecraft.world.item.ItemStack.EMPTY;
        }
        return out;
    }

    private static void captureLayoutBaseline(ServerPlayer player) {
        baselineKeys = snapshotKeys(player);
        lastValid = snapshotStacks(player);
    }

    /** Layout mode: pin the player, silently undo illegal inventory changes, remove dropped items. */
    private static void tickLayoutGuard(net.minecraft.server.MinecraftServer server) {
        if (layoutKit == null || baselineKeys == null || lastValid == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player.level() instanceof net.minecraft.server.level.ServerLevel sl)) continue;
// Stationary: no gravity and a single barrier block under the feet, so there is nothing to jitter against.
            if (!player.isNoGravity()) player.setNoGravity(true);
            net.minecraft.core.BlockPos floor = new net.minecraft.core.BlockPos(0, -61, -43);
            if (!sl.getBlockState(floor).is(net.minecraft.world.level.block.Blocks.BARRIER)) {
                sl.setBlock(floor, net.minecraft.world.level.block.Blocks.BARRIER.defaultBlockState(), 2);
            }
            // Only correct real drift (more than one block), never every tick.
            if (player.distanceToSqr(0.0, -60.0, -43.0) > 1.0) {
                player.teleportTo(sl, 0.0, -60.0, -43.0, Collections.emptySet(), player.getYRot(), player.getXRot(), true);
                player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            }

            var carried = player.containerMenu.getCarried();
            String cursorKey = carried.isEmpty() ? null : itemKey(carried) + "x" + carried.getCount();
            boolean ok = com.p2ppvp.mod.client.ui.LayoutGuard.isValid(baselineKeys, snapshotKeys(player), cursorKey);
            if (ok) {
                if (carried.isEmpty()) lastValid = snapshotStacks(player);
            } else {
                var inv = player.getInventory();
                for (int i = 0; i < lastValid.length && i < inv.getContainerSize(); i++) {
                    inv.setItem(i, lastValid[i].copy());
                }
                player.containerMenu.setCarried(net.minecraft.world.item.ItemStack.EMPTY);
                player.containerMenu.broadcastChanges();
                player.inventoryMenu.broadcastFullState();
            }
            for (var ie : sl.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, player.getBoundingBox().inflate(8.0))) {
                ie.discard();
            }
        }
    }

    private static void registerLayoutLocks() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(KitEditorManager::tickLayoutGuard);
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((pl, lv, hand) ->
            layoutKit != null ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((pl, lv, hand, hit) ->
            layoutKit != null ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((pl, lv, hand, pos, dir) ->
            layoutKit != null ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((pl, lv, hand, en, hit) ->
            layoutKit != null ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((pl, lv, hand, en, hit) ->
            layoutKit != null ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
    }

    private static String itemKey(net.minecraft.world.item.ItemStack st) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
        var pc = st.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        if (pc != null) {
            var h = pc.potion().orElse(null);
            if (h != null) id += "|" + h.unwrapKey().map(k -> k.identifier().toString()).orElse("");
        }
        return id;
    }

    private static int handleLayoutSave(ServerPlayer player) {
        String kit = layoutKit;
        if (kit == null) {
            player.sendSystemMessage(Component.literal("§cLayout editing is not active. Open it from Queue Settings."));
            return 0;
        }
        try (java.io.InputStream is = KitEditorManager.class.getResourceAsStream("/assets/p2ppvp/kits/" + kit + ".json")) {
            if (is == null) { player.sendSystemMessage(Component.literal("§cKit data not found.")); return 0; }
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("items");
            java.util.List<com.p2ppvp.mod.client.ui.KitLayoutMapper.Item> defaults = new java.util.ArrayList<>();
            for (var e : arr) {
                var o = e.getAsJsonObject();
                String key = o.get("id").getAsString() + (o.has("potion") ? "|" + o.get("potion").getAsString() : "");
                defaults.add(new com.p2ppvp.mod.client.ui.KitLayoutMapper.Item(o.get("slot").getAsInt(), key));
            }
            java.util.List<com.p2ppvp.mod.client.ui.KitLayoutMapper.Item> current = new java.util.ArrayList<>();
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var st = inv.getItem(i);
                if (!st.isEmpty()) current.add(new com.p2ppvp.mod.client.ui.KitLayoutMapper.Item(i, itemKey(st)));
            }
            java.util.List<String> missingKeys = com.p2ppvp.mod.client.ui.KitLayoutMapper.missing(defaults, current);
            if (!missingKeys.isEmpty()) {
                java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
                for (String k : missingKeys) counts.merge(k.contains("|") ? k.substring(0, k.indexOf('|')) : k, 1, Integer::sum);
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (var en : counts.entrySet()) {
                    if (shown++ >= 6) { sb.append(", ..."); break; }
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(en.getKey().replace("minecraft:", "")).append(" x").append(en.getValue());
                }
                player.sendSystemMessage(Component.literal("§cMissing kit items, not saved: §e" + sb + "§c. Put them back in an inventory slot (not armor/offhand) or press Reset."));
                return 0;
            }
            com.google.gson.JsonObject mapping = com.p2ppvp.mod.client.ui.KitLayoutMapper.build(defaults, current);
            if (mapping == null) { player.sendSystemMessage(Component.literal("§cInvalid layout (two items on one slot). Not saved.")); return 0; }
            writeLayouts(com.p2ppvp.mod.client.ui.KitLayout.withKit(readLayouts(), kit, mapping.size() == 0 ? null : mapping));
            player.sendSystemMessage(Component.literal("§aLayout saved for §e" + kit + "§a."));
            return finishLayout(player);
        } catch (Exception e) {
            LOGGER.error("Layout save failed", e);
            player.sendSystemMessage(Component.literal("§cCould not save layout."));
            return 0;
        }
    }

    /** Reset button: put every item back in its default slot in the open inventory. Saved layout is untouched, world stays open. */
    private static int handleLayoutReset(ServerPlayer player) {
        String kit = layoutKit;
        if (kit == null) return 0;
        try {
            player.containerMenu.setCarried(net.minecraft.world.item.ItemStack.EMPTY);
            player.getInventory().clearContent();
            if (player.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                com.p2ppvp.mod.MatchCoordinator.applyKitDefault(sl.getServer(), player, kit);
            }
            captureLayoutBaseline(player);
            player.containerMenu.broadcastChanges();
            player.inventoryMenu.broadcastFullState();
            return 1;
        } catch (Exception e) {
            LOGGER.error("Layout reset failed", e);
            return 0;
        }
    }

    private static int finishLayout(ServerPlayer player) {
        layoutKit = null;
        savedInSession.add(player.getUUID());
        return handleExit(player);
    }

    public static void launchLayoutEditor(Minecraft mc, String kit) {
        layoutKit = kit.toLowerCase(java.util.Locale.ROOT);
        launchEditor(mc);
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
        player.sendSystemMessage(Component.literal("§7Use the Exit button beside your inventory to return to the menu."));
        return 1;
    }

    private static int handleExit(ServerPlayer player) {
        UUID uuid = player.getUUID();

        exitPendingConfirmation.remove(uuid);
        savedInSession.remove(uuid);
        welcomedPlayers.remove(uuid);
        isEditorActive = false;
        layoutKit = null;

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

        boolean layoutMode = layoutKit != null;
        player.setGameMode(layoutMode ? GameType.SURVIVAL : GameType.CREATIVE);
        player.getAbilities().mayfly = !layoutMode;
        player.getAbilities().flying = !layoutMode;
        player.getAbilities().instabuild = !layoutMode;
        player.getAbilities().invulnerable = true;
        player.onUpdateAbilities();

        if (player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            // Teleport to the center of the arena (0.0, -60.0, -43.0)
            player.teleportTo(serverLevel, 0.0, -60.0, -43.0, Collections.emptySet(), 0.0f, 0.0f, true);
        }

        // Clear default starting inventory so player can pick fresh items from Creative
        player.getInventory().clearContent();
        if (layoutKit != null && player.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            com.p2ppvp.mod.MatchCoordinator.applyKitSaved(sl.getServer(), player, layoutKit);
            captureLayoutBaseline(player);
        }
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastFullState();

        if (welcomedPlayers.add(uuid)) {
            if (layoutKit != null) {
            } else {
            player.sendSystemMessage(Component.literal("§6§l[KIT CREATOR]"));
            player.sendSystemMessage(Component.literal("§fBuild your kit using the Creative inventory."));
            player.sendSystemMessage(Component.literal("§eOpen your inventory (§aE§e) and use the buttons beside it to save or exit."));
            }
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
