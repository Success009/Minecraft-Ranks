package com.p2ppvp.mod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/** Asks for a kit name, then sends /save <name>. Used by the kit creator's Save button. */
public class KitNameScreen extends Screen {
    private EditBox box;

    public KitNameScreen() {
        super(Component.literal("Name your kit"));
    }

    @Override
    protected void init() {
        box = new EditBox(this.font, this.width / 2 - 100, this.height / 2 - 20, 200, 20, Component.literal("Kit name"));
        box.setMaxLength(32);
        box.setHint(Component.literal("Kit name"));
        this.addRenderableWidget(box);
        this.setInitialFocus(box);
        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> submit())
            .bounds(this.width / 2 - 100, this.height / 2 + 10, 98, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> back())
            .bounds(this.width / 2 + 2, this.height / 2 + 10, 98, 20).build());
    }

    private void submit() {
        String name = box.getValue().trim();
        Minecraft mc = Minecraft.getInstance();
        if (name.isEmpty() || mc.player == null) return;
        mc.player.connection.sendCommand("save " + name);
        back();
    }

    private void back() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.setScreen(new InventoryScreen(mc.player));
        else mc.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
