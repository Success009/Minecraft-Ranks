package com.p2ppvp.mod.mixin;

import com.p2ppvp.mod.client.KitNameScreen;
import com.p2ppvp.mod.client.LayoutIconButton;
import com.p2ppvp.mod.customkit.KitEditorManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds beacon-style buttons beside the vanilla inventory: Save/Reset/Cancel for layout editing, Save/Exit for kit creation. */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends Screen {
    protected InventoryScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void mcr$editorButtons(CallbackInfo ci) {
        boolean layout = KitEditorManager.layoutKit != null;
        boolean creator = !layout && KitEditorManager.isEditorActive;
        if (!layout && !creator) return;

        int x = (this.width - 176) / 2 + 176 + 6;
        int bottom = (this.height - 166) / 2 + 166;

        if (layout) {
            int y = bottom - 74 - 6;
            this.addRenderableWidget(new LayoutIconButton(x, y, Component.literal("Save layout"),
                "minecraft:container/beacon/confirm", null, () -> mcr$confirm("Save layout?", "Save this arrangement for the kit.", "save")));
            this.addRenderableWidget(new LayoutIconButton(x, y + 26, Component.literal("Reset to default"),
                null, "R", () -> mcr$confirm("Reset items?", "Put every item back in its default slot.", "reset")));
            this.addRenderableWidget(new LayoutIconButton(x, y + 52, Component.literal("Cancel"),
                "minecraft:container/beacon/cancel", null, () -> mcr$confirm("Cancel?", "Leave without saving.", "exit")));
        } else {
            int y = bottom - 48 - 6;
            this.addRenderableWidget(new LayoutIconButton(x, y, Component.literal("Save kit"),
                "minecraft:container/beacon/confirm", null, () -> Minecraft.getInstance().setScreen(new KitNameScreen())));
            this.addRenderableWidget(new LayoutIconButton(x, y + 26, Component.literal("Exit"),
                "minecraft:container/beacon/cancel", null, () -> mcr$confirm("Exit kit creator?", "Unsaved items will be lost.", "exit")));
        }
    }

    private static void mcr$confirm(String title, String body, String cmd) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ConfirmScreen(yes -> {
            if (yes && mc.player != null) mc.player.connection.sendCommand(cmd);
            if (mc.player != null) mc.setScreen(new InventoryScreen(mc.player));
        }, Component.literal(title), Component.literal(body)));
    }
}
