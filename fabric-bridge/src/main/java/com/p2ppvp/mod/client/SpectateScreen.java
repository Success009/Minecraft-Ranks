package com.p2ppvp.mod.client;

import com.p2ppvp.mod.client.social.SocialModels.LiveMatch;
import com.p2ppvp.mod.client.ui.McrLayout;
import com.p2ppvp.mod.client.ui.McrLayout.Rect;
import com.p2ppvp.mod.client.ui.SpectateRules;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Lists live matches and lets an idle player spectate one. Layout is computed from McrLayout. */
public class SpectateScreen extends Screen {
    private static final int ROW_H = 24, ROW_GAP = 2, PAD = 6, HEADER = 22, FOOTER = 26;
    private static final long REFRESH_MS = 3000;

    private final Screen parent;
    private volatile List<LiveMatch> matches = List.of();
    private volatile boolean loaded = false;
    private volatile boolean failed = false;
    private long lastFetch = 0;
    private boolean fetching = false;
    private int scroll = 0;
    private String selectedId = null;

    private Rect card, list, spectateBtn, backBtn;

    public SpectateScreen(Screen parent) {
        super(Component.literal("Spectate"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        card = McrLayout.card(this.width, this.height, 320, 220, 8);
        list = McrLayout.listArea(card, HEADER, FOOTER, PAD, ROW_H);
        int by = card.bottom() - PAD - 18;
        Rect row = new Rect(card.x() + PAD, by, card.w() - 2 * PAD, 18);
        Rect[] two = McrLayout.splitRow(row, 6);
        spectateBtn = two[0];
        backBtn = two[1];
        refresh();
    }

    private void refresh() {
        if (fetching) return;
        fetching = true;
        lastFetch = System.currentTimeMillis();
        P2PPvpModClient.SOCIAL_API.liveMatches().whenComplete((r, ex) -> {
            try {
                if (ex == null && r.ok()) {
                    matches = r.value();
                    loaded = true;
                    failed = false;
                } else if (ex == null && r != null && !"network".equals(r.code())) {
                    matches = java.util.List.of();
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

    private String me() {
        return this.minecraft != null && this.minecraft.getUser() != null ? this.minecraft.getUser().getName() : null;
    }

    private LiveMatch selected() {
        for (LiveMatch m : matches) if (m.matchId().equals(selectedId)) return m;
        return null;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (System.currentTimeMillis() - lastFetch >= REFRESH_MS) refresh();

        g.fill(0, 0, this.width, this.height, 0xAA000000);
        g.fill(card.x() - 1, card.y() - 1, card.right() + 1, card.bottom() + 1, 0xFFD4AF37);
        g.fill(card.x(), card.y(), card.right(), card.bottom(), 0xFF0D1017);
        g.centeredText(this.font, "§6§lLive Matches", card.x() + card.w() / 2, card.y() + PAD + 2, 0xFFFFFFFF);

        List<LiveMatch> ms = matches;
        int visible = McrLayout.visibleRows(list.h(), ROW_H, ROW_GAP);
        scroll = McrLayout.clampScroll(scroll, ms.size(), visible);

        if (ms.isEmpty()) {
            String msg = failed ? "§cCould not reach matchmaker." : (loaded ? "No live matches right now." : "Loading...");
            g.centeredText(this.font, msg, list.x() + list.w() / 2, list.y() + list.h() / 2 - 4, 0xFFAAAAAA);
        } else {
            g.enableScissor(list.x(), list.y(), list.right(), list.bottom());
            for (int i = 0; i < visible && scroll + i < ms.size(); i++) {
                LiveMatch m = ms.get(scroll + i);
                int y = list.y() + i * (ROW_H + ROW_GAP);
                boolean sel = m.matchId().equals(selectedId);
                boolean hov = mouseX >= list.x() && mouseX < list.right() && mouseY >= y && mouseY < y + ROW_H;
                g.fill(list.x(), y, list.right(), y + ROW_H, sel ? 0xFF2A3350 : (hov ? 0xFF1C2230 : 0xFF141821));
                g.text(this.font, m.host() + " vs " + m.guest(), list.x() + 4, y + 3, 0xFFFFFFFF);
                String kit = (m.kit() == null ? "" : m.kit().typeLabel() + ": " + m.kit().name());
                g.text(this.font, "§7" + kit, list.x() + 4, y + 13, 0xFFAAAAAA);
                String cnt = SpectateRules.countLabel(m);
                g.text(this.font, (m.full() ? "§c" : "§a") + cnt, list.right() - this.font.width(cnt) - 4, y + 3, 0xFFFFFFFF);
            }
            g.disableScissor();
        }

        SpectateRules.ButtonState st = SpectateRules.buttonFor(selected(), me(), P2PPvpModClient.SPECTATOR_SESSION.isActive());
        drawButton(g, spectateBtn, st.label(), st.enabled(), mouseX, mouseY, 0xFF2E7D32);
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
            SpectateRules.ButtonState st = SpectateRules.buttonFor(selected(), me(), P2PPvpModClient.SPECTATOR_SESSION.isActive());
            if (spectateBtn.contains(mx, my)) {
                if (st.enabled()) {
                    P2PPvpModClient.startSpectating(me(), selectedId);
                    onClose();
                }
                return true;
            }
            if (list.contains(mx, my)) {
                int idx = scroll + (my - list.y()) / (ROW_H + ROW_GAP);
                List<LiveMatch> ms = matches;
                if (idx >= 0 && idx < ms.size()) selectedId = ms.get(idx).matchId();
                return true;
            }
        }
        return super.mouseClicked(e, doubleClick);
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
