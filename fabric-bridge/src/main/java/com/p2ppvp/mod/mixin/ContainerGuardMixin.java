package com.p2ppvp.mod.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Layout editing: the inventory cannot be closed and nothing can be thrown out. Everything is cancelled silently. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerGuardMixin {
    private boolean mcr$locked() {
        return com.p2ppvp.mod.customkit.KitEditorManager.layoutKit != null && (Object) this instanceof InventoryScreen;
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void mcr$keys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!mcr$locked()) return;
        int k = event.key();
        if (k == 256 || k == 69 || k == 81) cir.setReturnValue(true);
    }

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void mcr$slots(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
        if (!mcr$locked()) return;
        if (slot == null || input == ContainerInput.THROW || input == ContainerInput.CLONE) ci.cancel();
    }
}
