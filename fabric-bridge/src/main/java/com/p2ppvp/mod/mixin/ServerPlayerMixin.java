package com.p2ppvp.mod.mixin;

import com.p2ppvp.mod.MatchCoordinator;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "hasPermissions", at = @At("HEAD"), cancellable = true)
    private void onHasPermissions(int permissionLevel, CallbackInfoReturnable<Boolean> cir) {
        if (com.p2ppvp.mod.client.AutoUpdater.isQuietlyDisabled()) return;

                boolean isMock = com.p2ppvp.mod.P2PPvpMod.authorizedOpponentName != null && 
                         com.p2ppvp.mod.P2PPvpMod.authorizedOpponentName.toLowerCase().contains("mock");
        if (isMock) {
            cir.setReturnValue(true);
            return;
        }

        ServerPlayer player = (ServerPlayer) (Object) this;
        net.minecraft.server.MinecraftServer server = null;
        if (player.level() instanceof net.minecraft.server.level.ServerLevel) {
            server = ((net.minecraft.server.level.ServerLevel) player.level()).getServer();
        }

        if (server != null) {
            String levelName = server.getWorldData().getLevelName();
            boolean isPvPWorld = levelName != null && (
                levelName.toLowerCase().contains("pvp") || 
                levelName.toLowerCase().contains("arena") || 
                levelName.toLowerCase().contains("cache")
            );
            if (isPvPWorld) {
                // Deny ALL permission levels to make every command look like an unknown/invalid command!
                cir.setReturnValue(false);
            }
        } else if (MatchCoordinator.matchRunning) {
            cir.setReturnValue(false);
        }
    }
}
