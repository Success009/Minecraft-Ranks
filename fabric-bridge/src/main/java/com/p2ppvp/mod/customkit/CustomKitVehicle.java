package com.p2ppvp.mod.customkit;

import com.google.gson.JsonObject;

public class CustomKitVehicle {
    public String entityType;
    public float health;
    public float maxHealth;
    public boolean saddled;
    public boolean tamed;
    public String armorItem;
    public String nbtBase64;

    public CustomKitVehicle() {}

    public CustomKitVehicle(String entityType) {
        this.entityType = entityType;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("entityType", entityType != null ? entityType : "minecraft:horse");
        if (health > 0) obj.addProperty("health", health);
        if (maxHealth > 0) obj.addProperty("maxHealth", maxHealth);
        if (saddled) obj.addProperty("saddled", saddled);
        if (tamed) obj.addProperty("tamed", tamed);
        if (armorItem != null && !armorItem.isEmpty()) obj.addProperty("armorItem", armorItem);
        if (nbtBase64 != null && !nbtBase64.isEmpty()) obj.addProperty("nbtBase64", nbtBase64);
        return obj;
    }

    public static CustomKitVehicle fromJson(JsonObject obj) {
        if (obj == null) return null;
        CustomKitVehicle vehicle = new CustomKitVehicle();
        if (obj.has("entityType")) vehicle.entityType = obj.get("entityType").getAsString();
        if (obj.has("health")) vehicle.health = obj.get("health").getAsFloat();
        if (obj.has("maxHealth")) vehicle.maxHealth = obj.get("maxHealth").getAsFloat();
        if (obj.has("saddled")) vehicle.saddled = obj.get("saddled").getAsBoolean();
        if (obj.has("tamed")) vehicle.tamed = obj.get("tamed").getAsBoolean();
        if (obj.has("armorItem")) vehicle.armorItem = obj.get("armorItem").getAsString();
        if (obj.has("nbtBase64")) vehicle.nbtBase64 = obj.get("nbtBase64").getAsString();
        return vehicle;
    }
}
