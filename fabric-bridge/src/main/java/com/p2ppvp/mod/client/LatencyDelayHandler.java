package com.p2ppvp.mod.client;

import io.netty.channel.*;
import java.util.concurrent.TimeUnit;

@ChannelHandler.Sharable
public class LatencyDelayHandler extends ChannelDuplexHandler {
    private final int delayMs;

    public LatencyDelayHandler(int delayMs) {
        this.delayMs = delayMs;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (delayMs <= 0) {
            super.channelRead(ctx, msg);
            return;
        }
        ctx.executor().schedule(() -> {
            try {
                super.channelRead(ctx, msg);
            } catch (Exception e) {
                ctx.fireExceptionCaught(e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (delayMs <= 0) {
            super.write(ctx, msg, promise);
            return;
        }
        ctx.executor().schedule(() -> {
            try {
                super.write(ctx, msg, promise);
            } catch (Exception e) {
                promise.setFailure(e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }
}
