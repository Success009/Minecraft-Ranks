package com.p2ppvp.mod.client;

import com.p2ppvp.mod.client.social.SocialModels.KitRef;
import com.p2ppvp.mod.client.social.SocialModels.OnlinePlayer;
import com.p2ppvp.mod.client.ui.ChallengeKit;
import com.p2ppvp.mod.client.ui.ChallengeToast;
import com.p2ppvp.mod.client.ui.McrLayout;
import com.p2ppvp.mod.client.ui.McrLayout.Rect;
import com.p2ppvp.mod.client.ui.PlayersRules;
import com.p2ppvp.mod.client.ui.ToastFeeder;
import com.p2ppvp.mod.client.ui.ToastQueue;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Online players list with a Challenge button and an official-kit picker. Layout from McrLayout. */
public class PlayersScreen extends Screen {
    private static final int ROW_H = 20, ROW_GAP = 2, PAD = 6, HEADER = 22, FOOTER = 48;
    private static final long REFRESH_MS = 3000;

    private final Screen parent;
    private volatile List<OnlinePlayer> players = List.of();
    private volatile boolean loaded = false;
    private volatile boolean failed = false;
    private long lastFetch = 0;
    private boolean fetching = false;
    private int scroll = 0;
    private String selectedId = null;
    private com.p2ppvp.mod.client.ui.ChallengeKit.Choice choice() {
        return com.p2ppvp.mod.client.ui.ChallengeKit.resolve(MatchmakingOptionsScreen.selectedKits, new java.util.Random());
    }

    private Rect card, list, kitBtn, challengeBtn, backBtn;

    public PlayersScreen(Screen parent) {
        super(Component.literal("Players"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        card = McrLayout.card(this.width, this.height, 320, 240, 8);
        list = McrLayout.listArea(card, HEADER, FOOTER, PAD, ROW_H);
        int w = card.w() - 2 * PAD;
        Rect kitRow = new Rect(card.x() + PAD, card.bottom() - PAD - 18 - 4 - 18, w, 18);
        Rect btnRow = new Rect(card.x() + PAD, card.bottom() - PAD - 18, w, 18);
        kitBtn = kitRow;
        Rect[] two = McrLayout.splitRow(btnRow, 6);
        challengeBtn = two[0];
        backBtn = two[1];
        refresh();
    }

    private String me() {
        return this.minecraft != null && this.minecraft.getUser() != null ? this.minecraft.getUser().getName() : null;
    }

    private void refresh() {
        if (fetching || me() == null) return;
        fetching = true;
        lastFetch = System.currentTimeMillis();
        P2PPvpModClient.SOCIAL_API.onlinePlayers(me()).whenComplete((r, ex) -> {
            try {
                if (ex == null && r.ok()) {
                    players = r.value();
                    loaded = true;
                    failed = false;
                } else if (ex == null && r != null && !"network".equals(r.code())) {
                    players = List.of();
                    loaded = true;
                    failed = false;
                } else {
                    failed = true;
                }
            } finally {
                fetching = false;
            }
        });
    }

    private OnlinePlayer selected() {
        for (OnlinePlayer p : players) if (p.playerId().equals(selectedId)) return p;
        return null;
    }

    private PlayersRules.ButtonState state() {
        return PlayersRules.buttonFor(selected(), me(), P2PPvpModClient.SOCIAL_POLLER.isOnline());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (System.currentTimeMillis() - lastFetch >= REFRESH_MS) refresh();

        g.fill(0, 0, this.width, this.height, 0xAA000000);
        g.fill(card.x() - 1, card.y() - 1, card.right() + 1, card.bottom() + 1, 0xFFD4AF37);
        g.fill(card.x(), card.y(), card.right(), card.bottom(), 0xFF0D1017);
        g.centeredText(this.font, "§6§lOnline Players", card.x() + card.w() / 2, card.y() + PAD + 2, 0xFFFFFFFF);

        List<OnlinePlayer> ps = players;
        int visible = McrLayout.visibleRows(list.h(), ROW_H, ROW_GAP);
        scroll = McrLayout.clampScroll(scroll, ps.size(), visible);

        if (ps.isEmpty()) {
            String msg = loaded ? "No other players online." : (failed ? "§cCould not reach matchmaker." : "Loading...");
            g.centeredText(this.font, msg, list.x() + list.w() / 2, list.y() + list.h() / 2 - 4, 0xFFAAAAAA);
        } else {
            g.enableScissor(list.x(), list.y(), list.right(), list.bottom());
            for (int i = 0; i < visible && scroll + i < ps.size(); i++) {
                OnlinePlayer p = ps.get(scroll + i);
                int y = list.y() + i * (ROW_H + ROW_GAP);
                boolean sel = p.playerId().equals(selectedId);
                boolean hov = mouseX >= list.x() && mouseX < list.right() && mouseY >= y && mouseY < y + ROW_H;
                g.fill(list.x(), y, list.right(), y + ROW_H, sel ? 0xFF2A3350 : (hov ? 0xFF1C2230 : 0xFF141821));
                g.fill(list.x() + 3, y + 3, list.x() + 17, y + 17, 0xFF2A2F3A);
                String init = p.name() == null || p.name().isEmpty() ? "?" : p.name().substring(0, 1).toUpperCase();
                g.centeredText(this.font, init, list.x() + 10, y + 6, 0xFFD4AF37);
                g.text(this.font, p.name(), list.x() + 22, y + 6, 0xFFFFFFFF);
                String right = PlayersRules.statusColor(p.status()) + p.status().replace('_', ' ') + " §7" + p.points() + " pts";
                g.text(this.font, right, list.right() - this.font.width(right.replaceAll("§.", "")) - 4, y + 6, 0xFFFFFFFF);
            }
            g.disableScissor();
        }

        var ch = choice();
        g.centeredText(this.font, "§7Kit: §f" + ChallengeKit.label(MatchmakingOptionsScreen.selectedKits), kitBtn.x() + kitBtn.w() / 2, kitBtn.y() + 5, 0xFFFFFFFF);
        PlayersRules.ButtonState st = state();
        boolean canChallenge = st.enabled() && ch.ok();
        String chLabel = st.enabled() && !ch.ok() ? "Pick a kit" : st.label();
        drawButton(g, challengeBtn, chLabel, canChallenge, mouseX, mouseY, 0xFFB8860B);
        drawButton(g, backBtn, "Back", true, mouseX, mouseY, 0xFF3A3F4B);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void drawButton(GuiGraphicsExtractor g, Rect r, String label, boolean enabled, int mx, int my, int color) {
        int c = enabled ? (r.contains(mx, my) ? color | 0x00202020 : color) : 0xFF2A2A2A;
        g.fill(r.x(), r.y(), r.right(), r.bottom(), c);
        g.centeredText(this.font, label, r.x() + r.w() / 2, r.y() + (r.h() - 8) / 2, enabled ? 0xFFFFFFFF : 0xFF777777);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() == 0) {
            int mx = (int) e.x(), my = (int) e.y();
            if (backBtn.contains(mx, my)) { onClose(); return true; }
            if (challengeBtn.contains(mx, my)) {
                if (state().enabled() && choice().ok()) sendChallenge();
                return true;
            }
            if (list.contains(mx, my)) {
                int idx = scroll + (my - list.y()) / (ROW_H + ROW_GAP);
                List<OnlinePlayer> ps = players;
                if (idx >= 0 && idx < ps.size()) selectedId = ps.get(idx).playerId();
                return true;
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    private void sendChallenge() {
        OnlinePlayer p = selected();
        String me = me();
        if (p == null || me == null) return;
        var ch = choice();
        if (!ch.ok()) return;
        KitRef kitRef = ch.kit();
        String kitName = kitRef.name();
        P2PPvpModClient.SOCIAL_API.sendChallenge(me, p.playerId(), kitRef).thenAccept(r -> {
            if (r.ok()) {
                ChallengeToast.QUEUE.push(ToastQueue.Kind.INFO, "Challenge sent",
                    "Waiting for " + p.name(), null, kitRef.typeLabel(), kitName, ToastFeeder.RESULT_HOLD_MS);
            } else {
                ToastFeeder.addError(ChallengeToast.QUEUE, r.code(), r.retryAfter());
            }
            refresh();
        });
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (list != null && list.contains((int) x, (int) y)) {
            scroll -= (int) Math.signum(sy);
            return true;
        }
        return super.mouseScrolled(x, y, sx, sy);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
