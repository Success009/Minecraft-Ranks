package com.p2ppvp.mod.customkit;

import java.util.ArrayList;
import java.util.List;

public class CustomKitItem {
    public int slot;
    public String id;
    public int count;
    public String potion;
    public int damage;
    public List<CustomKitEnchantment> enchantments = new ArrayList<>();

    public CustomKitItem(int slot, String id, int count) {
        this.slot = slot;
        this.id = id;
        this.count = count;
    }
}
