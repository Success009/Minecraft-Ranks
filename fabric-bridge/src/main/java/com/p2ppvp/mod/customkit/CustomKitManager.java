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
}
