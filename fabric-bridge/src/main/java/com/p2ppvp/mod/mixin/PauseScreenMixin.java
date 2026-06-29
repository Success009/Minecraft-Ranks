package com.p2ppvp.mod.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        // Locate and disable the "Open to LAN" button, and rename the quit button to feel like a server
        for (Renderable drawable : ((ScreenAccessor) this).getDrawables()) {
            if (drawable instanceof Button button) {
                Component message = button.getMessage();
                String messageText = message.getString().toLowerCase();
                
                // Match standard "Open to LAN" button and disable it
                if (messageText.contains("lan")) {
                    button.active = false;
                    button.setMessage(Component.literal("§7§mOpen to LAN"));
                }

                // Match standard "Report Bugs" button and disable it
                if (messageText.contains("bug")) {
                    button.active = false;
                    button.setMessage(Component.literal("§7§mReport Bugs"));
                }

                // Match standard "Statistics" button and disable it
                if (messageText.contains("stat")) {
                    button.active = false;
                    button.setMessage(Component.literal("§7§mStatistics"));
                }
                // Match standard English, translations, or default singleplayer quit messages
                if (messageText.contains("quit") || messageText.contains("save") || messageText.contains("menu")) {
                    button.setMessage(Component.literal("§c§lDisconnect"));
                }
            }
        }
    }
}
