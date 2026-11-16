package com.p2ppvp.mod.mixin;

import com.p2ppvp.mod.client.skin.SkinOverride;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Covers every world-space read of a player's skin: third person, first-person arm, map hand,
 * spectated players, and the slim/wide renderer choice (all go through AbstractClientPlayer.getSkin()).
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerSkinMixin {
    @Inject(method = "getSkin()Lnet/minecraft/world/entity/player/PlayerSkin;", at = @At("RETURN"), cancellable = true)
    private void p2ppvp$overrideSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        try {
            AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
            String name = self.getGameProfile().name();
            PlayerSkin out = SkinOverride.apply(cir.getReturnValue(), name);
            if (out != cir.getReturnValue()) cir.setReturnValue(out);
        } catch (Throwable ignored) {
            // never break rendering
        }
    }
}
