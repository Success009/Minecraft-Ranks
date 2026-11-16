package com.p2ppvp.mod.client.skin;

import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** Central place that swaps in an uploaded skin. Fail-soft: returns the vanilla skin whenever anything is missing. */
public final class SkinOverride {
    private SkinOverride() {}

    public static PlayerSkin apply(PlayerSkin vanilla, String name) {
        try {
            if (vanilla == null || name == null || name.isEmpty()) return vanilla;
            if (com.p2ppvp.mod.client.AutoUpdater.isQuietlyDisabled()) return vanilla;
            SkinTextures.requestFullLazy(name);
            Identifier tex = SkinTextures.fullTexture(name);
            if (tex == null) return vanilla;
            if (vanilla.body() != null && tex.equals(vanilla.body().texturePath())) return vanilla;
            PlayerModelType model = SkinTextures.isSlim(name) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
            return new PlayerSkin(new ClientAsset.ResourceTexture(tex), vanilla.cape(), vanilla.elytra(), model, vanilla.secure());
        } catch (Throwable t) {
            return vanilla;
        }
    }
}
