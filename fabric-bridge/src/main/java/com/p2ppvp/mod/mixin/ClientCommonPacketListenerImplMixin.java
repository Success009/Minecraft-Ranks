package com.p2ppvp.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.p2ppvp.mod.client.P2PPvpModClient;

@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonPacketListenerImplMixin {
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void onDisconnectInject(DisconnectionDetails details, CallbackInfo ci) {
        if (details != null && details.reason() != null) {
            String text = details.reason().getString();
            com.p2ppvp.mod.DebugLogger.log("[Mixin ClientCommonPacketListenerImpl] onDisconnect received reason: " + text);
            if (text.startsWith("MATCH_RESOLVED:")) {
                String[] parts = text.split(":");
                String winner = null;
                String loser = null;
                String kit = null;
                for (String part : parts) {
                    if (part.startsWith("Winner=")) winner = part.substring(7);
                    if (part.startsWith("Loser=")) loser = part.substring(6);
                    if (part.startsWith("Kit=")) kit = part.substring(4);
                }
                if (winner != null && loser != null) {
                    P2PPvpModClient.redirectingToTitle = true;
                    P2PPvpModClient.reportMatchResult(winner, loser, kit);
                }
            }
        }
    }
}