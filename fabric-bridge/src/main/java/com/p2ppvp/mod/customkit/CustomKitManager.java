package com.p2ppvp.mod.customkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CustomKitManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("p2ppvp-customkit");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<CustomKit> customKits = new ArrayList<>();
    private static boolean initialized = false;

    private static Path getConfigFile() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("p2ppvp");
        try {
            Files.createDirectories(configDir);
        } catch (Exception ignored) {}
        return configDir.resolve("custom_kits.json");
    }

    public static synchronized void loadKits() {
        customKits.clear();
        Path path = getConfigFile();
        if (!Files.exists(path)) {
            initialized = true;
            return;
        }

        try (FileReader reader = new FileReader(path.toFile(), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray()) {
                    if (el.isJsonObject()) {
                        customKits.add(CustomKit.fromJson(el.getAsJsonObject()));
                    }
                }
            }
            LOGGER.info("[CustomKitManager] Loaded " + customKits.size() + " custom kits from disk.");
        } catch (Exception e) {
            LOGGER.error("[CustomKitManager] Failed to load custom kits: ", e);
        }
        initialized = true;
    }

    public static synchronized void saveKits() {
        Path path = getConfigFile();
        try {
            JsonArray arr = new JsonArray();
            for (CustomKit kit : customKits) {
                arr.add(kit.toJson());
            }
            try (FileWriter writer = new FileWriter(path.toFile(), StandardCharsets.UTF_8)) {
                GSON.toJson(arr, writer);
            }
            LOGGER.info("[CustomKitManager] Saved " + customKits.size() + " custom kits to disk.");
        } catch (Exception e) {
            LOGGER.error("[CustomKitManager] Failed to save custom kits: ", e);
        }
    }

    public static synchronized List<CustomKit> getCustomKits() {
        if (!initialized) {
            loadKits();
        }
        return new ArrayList<>(customKits);
    }

    public static synchronized CustomKit getCustomKit(String name) {
        if (!initialized) {
            loadKits();
        }
        for (CustomKit kit : customKits) {
            if (kit.getName().equalsIgnoreCase(name)) {
                return kit;
            }
        }
        return null;
    }

    public static synchronized void saveCustomKit(CustomKit newKit) {
        if (!initialized) {
            loadKits();
        }
        customKits.removeIf(k -> k.getName().equalsIgnoreCase(newKit.getName()));
        customKits.add(newKit);
        saveKits();
    }

    public static synchronized boolean deleteCustomKit(String name) {
        if (!initialized) {
            loadKits();
        }
        boolean removed = customKits.removeIf(k -> k.getName().equalsIgnoreCase(name));
        if (removed) {
            saveKits();
        }
        return removed;
    }

    public static CustomKit captureFromPlayer(ServerPlayer player, String kitName) {
        CustomKit kit = new CustomKit(kitName);
        Inventory inv = player.getInventory();

        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;

            Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId == null) continue;

            CustomKitItem kitItem = new CustomKitItem(slot, itemId.toString(), stack.getCount());

            // Capture durability / damage
            if (stack.isDamageableItem()) {
                kitItem.damage = stack.getDamageValue();
            }

            // Capture potion contents if present
            PotionContents potionContents = stack.get(DataComponents.POTION_CONTENTS);
            if (potionContents != null && potionContents.potion().isPresent()) {
                var holder = potionContents.potion().get();
                holder.unwrapKey().ifPresent(k -> kitItem.potion = k.identifier().toString());
            }
            // Capture enchantments if present
            ItemEnchantments enchs = stack.get(DataComponents.ENCHANTMENTS);
            if (enchs != null && !enchs.isEmpty()) {
                for (var entry : enchs.entrySet()) {
                    var holder = entry.getKey();
                    int lvl = entry.getIntValue();
                    holder.unwrapKey().ifPresent(k -> kitItem.enchantments.add(new CustomKitEnchantment(k.identifier().toString(), lvl)));
                }
            }
            kit.getItems().add(kitItem);
        }

        // Capture riding entity / vehicle if present
        net.minecraft.world.entity.Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            CustomKitVehicle vDef = new CustomKitVehicle();
            Identifier vId = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType());
            vDef.entityType = vId != null ? vId.toString() : "minecraft:horse";
            if (vehicle instanceof net.minecraft.world.entity.LivingEntity living) {
                vDef.health = living.getHealth();
                vDef.maxHealth = living.getMaxHealth();
            }
            try {
                var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                    net.minecraft.util.ProblemReporter.DISCARDING,
                    player.level().registryAccess()
                );
                vehicle.save(output);
                net.minecraft.nbt.CompoundTag tag = output.buildResult();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
                net.minecraft.nbt.NbtIo.write(tag, dos);
                vDef.nbtBase64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
                LOGGER.info("[CustomKitManager] Captured riding vehicle: " + vDef.entityType + " with full NBT");
            } catch (Exception e) {
                LOGGER.warn("[CustomKitManager] Failed to serialize vehicle NBT: ", e);
            }
            kit.setVehicle(vDef);
        }

        return kit;
    }

    public static void applyKitToPlayer(MinecraftServer server, ServerPlayer player, CustomKit kit) {
        if (server == null || player == null || kit == null) return;

        player.getInventory().clearContent();
        player.setHealth(20.0f);
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(20.0f);

        var registryManager = server.registryAccess();
        var enchantmentRegistry = registryManager.lookup(Registries.ENCHANTMENT).orElse(null);

        Inventory inventory = player.getInventory();

        for (CustomKitItem itemDef : kit.getItems()) {
            Identifier itemIdent = Identifier.tryParse(itemDef.id);
            if (itemIdent == null) continue;

            Item item = BuiltInRegistries.ITEM.get(itemIdent).map(Holder::value).orElse(null);
            if (item == null) continue;

            ItemStack stack = new ItemStack(item, Math.max(1, itemDef.count));

            // Apply durability / damage
            if (itemDef.damage > 0 && stack.isDamageableItem()) {
                stack.setDamageValue(itemDef.damage);
            }

            // Apply potion
            if (itemDef.potion != null && !itemDef.potion.isEmpty()) {
                Identifier pIdent = Identifier.tryParse(itemDef.potion);
                if (pIdent != null) {
                    var potionKey = ResourceKey.create(Registries.POTION, pIdent);
                    var potionHolder = BuiltInRegistries.POTION.get(potionKey).orElse(null);
                    if (potionHolder != null) {
                        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potionHolder));
                    }
                }
            }

            // Apply enchantments
            if (enchantmentRegistry != null && itemDef.enchantments != null && !itemDef.enchantments.isEmpty()) {
                ItemEnchantments.Mutable builder = new ItemEnchantments.Mutable(stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
                for (CustomKitEnchantment ench : itemDef.enchantments) {
                    Identifier eIdent = Identifier.tryParse(ench.id());
                    if (eIdent != null) {
                        var enchKey = ResourceKey.create(Registries.ENCHANTMENT, eIdent);
                        var enchantmentHolder = enchantmentRegistry.get(enchKey).orElse(null);
                        if (enchantmentHolder != null) {
                            builder.set(enchantmentHolder, ench.lvl());
                        }
                    }
                }
                stack.set(DataComponents.ENCHANTMENTS, builder.toImmutable());
            }

            if (itemDef.slot >= 0 && itemDef.slot < inventory.getContainerSize()) {
                inventory.setItem(itemDef.slot, stack);
            }
        }

        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastFullState();
        LOGGER.info("[CustomKitManager] Applied custom kit '" + kit.getName() + "' to player " + player.getGameProfile().name());
    }

    public static net.minecraft.world.entity.Entity spawnVehicleForPlayer(ServerPlayer player, CustomKitVehicle vehicleDef, double x, double y, double z, float yaw) {
        if (player == null || vehicleDef == null) return null;
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) return null;

        net.minecraft.world.entity.Entity vehicle = null;
        try {
            if (vehicleDef.nbtBase64 != null && !vehicleDef.nbtBase64.isEmpty()) {
                byte[] bytes = java.util.Base64.getDecoder().decode(vehicleDef.nbtBase64);
                java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
                net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.NbtIo.read(dis, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                tag.remove("UUID");
                tag.remove("UUIDMost");
                tag.remove("UUIDLeast");

                var input = net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING,
                    level.registryAccess(),
                    tag
                );
                var opt = net.minecraft.world.entity.EntityType.create(input, level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                if (opt.isPresent()) {
                    vehicle = opt.get();
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[CustomKitManager] Could not deserialize vehicle from NBT, falling back to entityType: ", e);
        }

        if (vehicle == null && vehicleDef.entityType != null) {
            Identifier ident = Identifier.tryParse(vehicleDef.entityType);
            if (ident != null) {
                var entityTypeHolder = BuiltInRegistries.ENTITY_TYPE.get(ident).orElse(null);
                if (entityTypeHolder != null) {
                    vehicle = entityTypeHolder.value().create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                }
            }
        }

        if (vehicle != null) {
            vehicle.setPos(x, y, z);
            vehicle.setYRot(yaw);
            vehicle.setXRot(0.0f);
            if (vehicle instanceof net.minecraft.world.entity.LivingEntity living) {
                living.setYHeadRot(yaw);
                living.setYBodyRot(yaw);
                if (vehicleDef.health > 0) {
                    living.setHealth(vehicleDef.health);
                }
            }
            level.addFreshEntity(vehicle);
            player.startRiding(vehicle, true, false);
                        LOGGER.info("[CustomKitManager] Spawned vehicle " + vehicle.getType() + " for player " + player.getGameProfile().name() + " facing towards center (yaw=" + yaw + ")");
            return vehicle;
        }
        return null;
    }
}
