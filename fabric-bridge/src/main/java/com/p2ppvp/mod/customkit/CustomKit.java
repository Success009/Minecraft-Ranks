package com.p2ppvp.mod.customkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class CustomKit {
    private String name;
    private final List<CustomKitItem> items = new ArrayList<>();
    private long createdAt;

    public CustomKit(String name) {
        this.name = name;
        this.createdAt = System.currentTimeMillis();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<CustomKitItem> getItems() {
        return items;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        obj.addProperty("category", "custom");
        obj.addProperty("createdAt", createdAt);

        JsonArray itemArray = new JsonArray();
        for (CustomKitItem item : items) {
            JsonObject itemObj = new JsonObject();
            itemObj.addProperty("slot", item.slot);
            itemObj.addProperty("id", item.id);
            itemObj.addProperty("count", item.count);
            if (item.potion != null && !item.potion.isEmpty()) {
                itemObj.addProperty("potion", item.potion);
            }
            if (item.enchantments != null && !item.enchantments.isEmpty()) {
                JsonArray enchArray = new JsonArray();
                for (CustomKitEnchantment ench : item.enchantments) {
                    JsonObject enchObj = new JsonObject();
                    enchObj.addProperty("id", ench.id());
                    enchObj.addProperty("lvl", ench.lvl());
                    enchArray.add(enchObj);
                }
                itemObj.add("enchantments", enchArray);
            }
            itemArray.add(itemObj);
        }
        obj.add("items", itemArray);
        return obj;
    }

    public static CustomKit fromJson(JsonObject obj) {
        String name = obj.has("name") ? obj.get("name").getAsString() : "CustomKit";
        CustomKit kit = new CustomKit(name);
        if (obj.has("createdAt")) {
            kit.setCreatedAt(obj.get("createdAt").getAsLong());
        }
        if (obj.has("items") && obj.get("items").isJsonArray()) {
            JsonArray itemArray = obj.getAsJsonArray("items");
            for (JsonElement el : itemArray) {
                if (!el.isJsonObject()) continue;
                JsonObject itemObj = el.getAsJsonObject();
                int slot = itemObj.get("slot").getAsInt();
                String id = itemObj.get("id").getAsString();
                int count = itemObj.has("count") ? itemObj.get("count").getAsInt() : 1;

                CustomKitItem kitItem = new CustomKitItem(slot, id, count);
                if (itemObj.has("potion")) {
                    kitItem.potion = itemObj.get("potion").getAsString();
                }
                if (itemObj.has("enchantments") && itemObj.get("enchantments").isJsonArray()) {
                    for (JsonElement enchEl : itemObj.getAsJsonArray("enchantments")) {
                        if (!enchEl.isJsonObject()) continue;
                        JsonObject enchObj = enchEl.getAsJsonObject();
                        String enchId = enchObj.get("id").getAsString();
                        int lvl = enchObj.get("lvl").getAsInt();
                        kitItem.enchantments.add(new CustomKitEnchantment(enchId, lvl));
                    }
                }
                kit.getItems().add(kitItem);
            }
        }
        return kit;
    }
}
