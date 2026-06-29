package com.p2ppvp.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.server.LanServerPinger;
import net.minecraft.client.Minecraft;
import com.p2ppvp.mod.DebugLogger;

@Mixin(LanServerPinger.class)
public class LanServerPingerMixin {

    @Inject(method = "run", at = @At("HEAD"), cancellable = true)
    private void onRun(CallbackInfo ci) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.getSingleplayerServer() != null) {
                String levelName = client.getSingleplayerServer().getWorldData().getLevelName();
                boolean matches = levelName != null && (
                    levelName.toLowerCase().contains("pvp") || 
                    levelName.toLowerCase().contains("arena") || 
                    levelName.toLowerCase().contains("cache")
                );
                if (matches) {
                    DebugLogger.log("[SERVER] LanServerPinger broadcast blocked for private match to prevent actual physical LAN players from seeing or joining.");
                    ci.cancel();
                }
            }
        } catch (Exception e) {
            DebugLogger.log("[SERVER] Error inside LanServerPingerMixin", e);
        }
    }
}