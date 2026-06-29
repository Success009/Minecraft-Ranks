package com.p2ppvp.mod.mixin;

import com.p2ppvp.mod.client.LatencyManager;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerInfo.class)
public class PlayerInfoMixin {

    @Inject(method = "getLatency", at = @At("HEAD"), cancellable = true)
    private void onGetLatency(CallbackInfoReturnable<Integer> cir) {
        int delay = LatencyManager.getActiveDelay();
        if (delay > 0) {
            // Return double the one-way delay (representing round-trip ping)
            cir.setReturnValue(delay * 2);
        }
    }
}
