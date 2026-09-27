package com.p2ppvp.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.p2ppvp.mod.client.P2PPvpModClient;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        if (com.p2ppvp.mod.client.AutoUpdater.isQuietlyDisabled()) return;

        if (screen instanceof DisconnectedScreen && P2PPvpModClient.redirectingToTitle) {
            P2PPvpModClient.redirectingToTitle = false;
            ci.cancel();
            Minecraft.getInstance().setScreen(new TitleScreen());
        }
    }

    @Inject(method = "canSwitchGameMode", at = @At("HEAD"), cancellable = true)
    private void onCanSwitchGameMode(CallbackInfoReturnable<Boolean> cir) {
        if (com.p2ppvp.mod.client.AutoUpdater.isQuietlyDisabled()) return;

        Minecraft client = (Minecraft) (Object) this;
        if (client.player != null) {
            String name = client.player.getGameProfile().name();
            if (name == null || !name.equalsIgnoreCase("success009")) {
                cir.setReturnValue(false);
            }
        }
    }
}
