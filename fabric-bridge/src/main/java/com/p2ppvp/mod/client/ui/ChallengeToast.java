package com.p2ppvp.mod.client.ui;

import com.p2ppvp.mod.client.P2PPvpModClient;
import com.p2ppvp.mod.client.social.SocialModels.Outcome;
import com.p2ppvp.mod.client.ui.McrLayout.Rect;
import com.p2ppvp.mod.client.ui.ToastQueue.Toast;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** Draws the bottom-right toast stack on every screen and handles Accept/Decline clicks. */
public final class ChallengeToast {
    public static final ToastQueue QUEUE = new ToastQueue();
    private static final int W = 190, H = 58, MARGIN = 6, GAP = 4;

    private ChallengeToast() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                long now = System.currentTimeMillis();
                ToastFeeder.syncIncoming(QUEUE, P2PPvpModClient.SOCIAL_POLLER.latest().incoming(), now);
                List<Outcome> outs = P2PPvpModClient.SOCIAL_POLLER.consumeOutcomes();
                if (!outs.isEmpty()) {
                    ToastFeeder.addOutcomes(QUEUE, outs);
                    for (Outcome o : outs) if ("accepted".equals(o.result())) P2PPvpModClient.pendingChallengeMatch = true;
                }
                QUEUE.update(now);
                com.p2ppvp.mod.client.LayoutEditorClient.tick(client);
            } catch (Throwable ignored) {}
        });
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> draw(s, g, mx, my));
            ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !click(s, e.x(), e.y(), e.button()));
        });
    }

    /** Rect of the i-th visible toast (0 = oldest, drawn lowest) before slide offset. */
    static Rect slot(int i, int sw, int sh) {
        int w = Math.min(W, Math.max(60, sw - 2 * MARGIN));
        Rect base = McrLayout.bottomRight(sw, sh, w, H, MARGIN);
        return new Rect(base.x(), base.y() - i * (H + GAP), base.w(), base.h());
    }

    private static Rect[] buttons(Rect r) {
        Rect row = new Rect(r.x() + 46, r.bottom() - 16, r.w() - 52, 12);
        return McrLayout.splitRow(row, 4);
    }

    private static void draw(Screen s, GuiGraphicsExtractor g, int mx, int my) {
        Font font = Minecraft.getInstance().font;
        long now = System.currentTimeMillis();
        List<Toast> list = QUEUE.visible();
        List<String> senders = new java.util.ArrayList<>();
        for (Toast t0 : list) if (t0.isChallenge() && t0.title != null) senders.add(t0.title);
        if (!senders.isEmpty()) com.p2ppvp.mod.client.skin.SkinTextures.requestHeads(senders);
        for (int i = 0; i < list.size(); i++) {
            Toast t = list.get(i);
            Rect r = slot(i, s.width, s.height);
            int x = r.x() + t.offsetX(r.w() + MARGIN, now), y = r.y();
            int accent = switch (t.kind) {
                case CHALLENGE -> 0xFFD4AF37; case ACCEPTED -> 0xFF55FF55;
                case DECLINED, EXPIRED, COOLDOWN -> 0xFFFF5555; default -> 0xFF5599FF;
            };
            g.fill(x - 1, y - 1, x + r.w() + 1, y + r.h() + 1, 0xFF000000);
            g.fill(x, y, x + r.w(), y + r.h(), 0xFF1B1B1B);
            g.fill(x, y, x + r.w(), y + 1, 0xFF6B6B6B);
            g.fill(x, y, x + 1, y + r.h(), 0xFF6B6B6B);
            g.fill(x + 1, y + 1, x + r.w() - 1, y + r.h() - 1, 0xFF262626);
            g.fill(x + 1, y + 1, x + 3, y + r.h() - 1, accent);
            // Avatar tile (initial letter); real skins need a player UUID the server does not store yet.
            String nm = t.title == null ? "" : t.title;
            int hh = Math.abs(nm.toLowerCase().hashCode());
            int tile = 0xFF000000 | (0x40 + (hh % 0x80)) << 16 | (0x40 + ((hh / 7) % 0x80)) << 8 | (0x40 + ((hh / 49) % 0x80));
            g.fill(x + 6, y + 6, x + 38, y + 38, 0xFF000000);
            net.minecraft.resources.Identifier head = t.isChallenge()
                ? com.p2ppvp.mod.client.skin.SkinTextures.headTexture(nm) : null;
            if (head != null) {
                g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, head, x + 7, y + 7, 0.0f, 0.0f, 30, 30, 8, 8, 8, 8, 0xFFFFFFFF);
            } else {
                net.minecraft.resources.Identifier dflt = com.p2ppvp.mod.client.skin.SkinTextures.defaultTexture(nm);
                if (dflt != null) {
                    g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, dflt, x + 7, y + 7, 8.0f, 8.0f, 30, 30, 8, 8, 64, 64, 0xFFFFFFFF);
                    g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, dflt, x + 7, y + 7, 40.0f, 8.0f, 30, 30, 8, 8, 64, 64, 0xFFFFFFFF);
                } else {
                    g.fill(x + 7, y + 7, x + 37, y + 37, tile);
                    String initial = nm.isEmpty() ? "?" : nm.substring(0, 1).toUpperCase();
                    g.centeredText(font, initial, x + 22, y + 18, 0xFFFFFFFF);
                }
            }
            g.text(font, fit(font, t.title, r.w() - 48), x + 44, y + 6, 0xFFFFFFFF);
            String l1 = t.kitType != null ? "§7" + t.kitType + " kit" : (t.line1 == null ? "" : t.line1);
            g.text(font, fit(font, l1, r.w() - 48), x + 44, y + 17, 0xFFAAAAAA);
            String l2 = t.kitName != null ? t.kitName : (t.line2 == null ? "" : t.line2);
            if (!l2.isEmpty()) g.text(font, fit(font, l2, r.w() - 48), x + 44, y + 28, 0xFFFFFFFF);
            if (t.isChallenge() && !t.isDismissing()) {
                Rect[] b = buttons(new Rect(x, y, r.w(), r.h()));
                bevelButton(g, font, b[0], "Accept", 0xFF2E7D32, 0xFF3FA044, b[0].contains(mx, my));
                bevelButton(g, font, b[1], "Decline", 0xFF8E2424, 0xFFB03030, b[1].contains(mx, my));
            }
            int barW = (int) Math.round((r.w() - 2) * t.holdRemaining(now));
            g.fill(x + 1, y + r.h() - 2, x + 1 + barW, y + r.h() - 1, accent);
        }
    }

    private static void bevelButton(GuiGraphicsExtractor g, Font font, Rect b, String label, int base, int hover, boolean hot) {
        g.fill(b.x(), b.y(), b.right(), b.bottom(), 0xFF000000);
        g.fill(b.x() + 1, b.y() + 1, b.right() - 1, b.bottom() - 1, hot ? hover : base);
        g.fill(b.x() + 1, b.y() + 1, b.right() - 1, b.y() + 2, 0x55FFFFFF);
        g.fill(b.x() + 1, b.bottom() - 2, b.right() - 1, b.bottom() - 1, 0x55000000);
        g.centeredText(font, label, b.x() + b.w() / 2, b.y() + 2, 0xFFFFFFFF);
    }

    private static String fit(Font font, String s, int maxW) {
        if (s == null) return "";
        if (font.width(s) <= maxW) return s;
        while (s.length() > 1 && font.width(s + "..") > maxW) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    /** Returns true if the click was consumed by a toast button. */
    private static boolean click(Screen s, double mx, double my, int button) {
        if (button != 0) return false;
        List<Toast> list = QUEUE.visible();
        for (int i = 0; i < list.size(); i++) {
            Toast t = list.get(i);
            if (!t.isChallenge() || t.isDismissing() || t.slideProgress(System.currentTimeMillis()) < 0.9) continue;
            Rect[] b = buttons(slot(i, s.width, s.height));
            int ix = (int) mx, iy = (int) my;
            if (b[0].contains(ix, iy)) { respond(t, true); return true; }
            if (b[1].contains(ix, iy)) { respond(t, false); return true; }
        }
        return false;
    }

    private static void respond(Toast t, boolean accept) {
        QUEUE.dismiss(t.id, accept, System.currentTimeMillis());
        String me = Minecraft.getInstance().getUser().getName();
        P2PPvpModClient.SOCIAL_API.respond(t.challengeId, me, accept).thenAccept(r -> {
            if (r.ok()) {
                if (accept) P2PPvpModClient.pendingChallengeMatch = true;
            } else {
                ToastFeeder.addError(QUEUE, r.code(), r.retryAfter());
            }
        });
    }
}
