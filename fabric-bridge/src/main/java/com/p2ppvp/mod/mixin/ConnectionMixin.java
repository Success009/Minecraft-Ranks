package com.p2ppvp.mod.mixin;

import com.p2ppvp.mod.DebugLogger;
import com.p2ppvp.mod.client.LatencyDelayHandler;
import com.p2ppvp.mod.client.LatencyManager;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class ConnectionMixin {

    @Inject(method = "channelActive", at = @At("TAIL"))
    private void onChannelActive(ChannelHandlerContext context, CallbackInfo ci) {
        int delay = LatencyManager.getActiveDelay();
        if (delay > 0) {
            // CRITICAL FIX: Only inject the delay handler into the CLIENT-side pipeline.
            // If we inject it into the Server-side pipeline, the integrated server will delay its tick packets,
            // causing immediate handshaking timeouts and disconnects.
            // CRITICAL FIX: Skip injection on local in-memory connections (singleplayer/LAN integrated server)
            if (context.channel() instanceof io.netty.channel.local.LocalChannel || 
                context.channel().getClass().getName().toLowerCase().contains("localchannel")) {
                DebugLogger.log("[LATENCY] Skipping injection on local in-memory channel: " + context.channel().getClass().getName());
                return;
            }

            String threadName = Thread.currentThread().getName();
            if (threadName.toLowerCase().contains("server")) {
                DebugLogger.log("[LATENCY] Skipping injection on Server-side connection thread: " + threadName);
                return;
            }

            try {
                // To keep it clean and robust, we add our delay handler before the default "packet_handler"
                context.channel().pipeline().addBefore("packet_handler", "p2ppvp_latency_delay", new LatencyDelayHandler(delay));
                DebugLogger.log("[LATENCY] Successfully injected CLIENT-side delay handler with " + delay + " ms latency. Thread: " + threadName);
            } catch (Exception e) {
                DebugLogger.log("[LATENCY] Failed to inject delay handler: " + e.getMessage());
            }
        }
    }
}
