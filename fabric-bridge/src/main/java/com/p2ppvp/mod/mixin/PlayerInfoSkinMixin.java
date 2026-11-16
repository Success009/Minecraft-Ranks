package com.p2ppvp.mod.mixin;

import com.p2ppvp.mod.client.skin.SkinOverride;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Covers the tab list and the spectator menu (both read PlayerInfo.getSkin()). */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoSkinMixin {
    @Inject(method = "getSkin()Lnet/minecraft/world/entity/player/PlayerSkin;", at = @At("RETURN"), cancellable = true)
    private void p2ppvp$overrideSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        try {
            PlayerInfo self = (PlayerInfo) (Object) this;
            String name = self.getProfile().name();
            PlayerSkin out = SkinOverride.apply(cir.getReturnValue(), name);
            if (out != cir.getReturnValue()) cir.setReturnValue(out);
        } catch (Throwable ignored) {
            // never break rendering
        }
    }
}
