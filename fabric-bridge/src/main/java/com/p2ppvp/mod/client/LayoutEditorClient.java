package com.p2ppvp.mod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

/** While a kit layout is being edited, keep the vanilla inventory open on the client. */
public final class LayoutEditorClient {
    private LayoutEditorClient() {}

    public static void tick(Minecraft mc) {
        if (com.p2ppvp.mod.customkit.KitEditorManager.layoutKit == null) return;
        if (mc.player == null || mc.level == null) return;
        if (mc.screen == null) mc.setScreen(new InventoryScreen(mc.player));
    }
}
