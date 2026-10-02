package com.p2ppvp.mod.mixin;

import com.p2ppvp.mod.MatchCoordinator;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void onSetHealth(float health, CallbackInfo ci) {
        if (com.p2ppvp.mod.client.AutoUpdater.isQuietlyDisabled()) return;

        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity instanceof ServerPlayer && MatchCoordinator.matchRunning) {
            if (health <= 0.0f) {
                ServerPlayer player = (ServerPlayer) entity;
                // If holding a Totem of Undying, do NOT cancel the setHealth, so checkTotem() can trigger
                if (player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND).is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING) ||
                    player.getItemInHand(net.minecraft.world.InteractionHand.OFF_HAND).is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) {
                    return;
                }

                // Intercept health change to prevent actual death or red screens
                ci.cancel();

                MinecraftServer server = ((ServerLevel) player.level()).getServer();
                if (server != null) {
                    com.p2ppvp.mod.DebugLogger.log("[MatchCoordinator] Player would die! setHealth called with: " + health + " for " + player.getGameProfile().name() + ". Intercepting, setting to spectator, and resolving match.");
                    
                    // Force survivor game mode spectator and heal to full (which triggers setHealth with 20.0f, going through cleanly)
                    player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                    player.setHealth(20.0f);

                    // Clear inventories of both players instantly
                    for (ServerPlayer p : new java.util.ArrayList<>(server.getPlayerList().getPlayers())) {
                        try {
                            p.getInventory().clearContent();
                            p.containerMenu.broadcastChanges();
                            p.inventoryMenu.broadcastFullState();
                        } catch (Exception ignored) {}
                    }

                    // Find winner
                    String loserName = player.getGameProfile().name();
                    String winnerName = null;
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        if (!p.getGameProfile().name().equalsIgnoreCase(loserName)) {
                            winnerName = p.getGameProfile().name();
                            break;
                        }
                    }
                    if (winnerName == null) {
                        winnerName = "Mock_Opponent";
                    }

                    MatchCoordinator.resolveMatch(server, winnerName, loserName);
                }
            }
        }
    }

        @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void onEntityDie(DamageSource damageSource, CallbackInfo ci) {
        if (com.p2ppvp.mod.client.AutoUpdater.isQuietlyDisabled()) return;

        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity.level() != null && !entity.level().isClientSide()) {
            if (MatchCoordinator.matchRunning) {
                if (entity instanceof ServerPlayer) {
                    ServerPlayer player = (ServerPlayer) entity;
                    
                    // If holding a Totem of Undying, do NOT cancel the death, let vanilla totem logic activate
                    if (player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND).is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING) ||
                        player.getItemInHand(net.minecraft.world.InteractionHand.OFF_HAND).is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) {
                        return;
                    }

                    MinecraftServer server = ((ServerLevel) player.level()).getServer();
                    if (server != null) {
                        com.p2ppvp.mod.DebugLogger.log("[MatchCoordinator] Player died fallback hook: " + player.getGameProfile().name() + "! Triggering defeat.");
                        
                        // Cancel actual death to prevent death screen
                        ci.cancel();
                        
                        // Set spectator and heal immediately
                        player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                        player.setHealth(20.0f);
                        for (ServerPlayer p : new java.util.ArrayList<>(server.getPlayerList().getPlayers())) {
                            try {
                                p.getInventory().clearContent();
                                p.containerMenu.broadcastChanges();
                                p.inventoryMenu.broadcastFullState();
                            } catch (Exception ignored) {}
                        }
                        // Find winner
                        String loserName = player.getGameProfile().name();
                        String winnerName = null;
                        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                            if (!p.getGameProfile().name().equalsIgnoreCase(loserName)) {
                                winnerName = p.getGameProfile().name();
                                break;
                            }
                        }
                        if (winnerName == null) {
                            winnerName = "Mock_Opponent";
                        }
                        
                        MatchCoordinator.resolveMatch(server, winnerName, loserName);
                    }
                }
            }
        }
    }
}
