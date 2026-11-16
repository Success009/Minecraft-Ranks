package com.p2ppvp.mod.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Pick a ranked kit to arrange. Opens the layout editor world for that kit. */
public class KitLayoutScreen extends Screen {
    private static final String[] KITS = {"Crystal", "UHC", "Pot", "Mace", "Sword"};
    private static final int PANEL_W = 280;
    private static final int HEADER_H = 44;
    private static final int ROW_H = 26;
    private static final int GOLD = 0xFFD4AF37;
    private final Screen parent;

    public KitLayoutScreen(Screen parent) {
        super(Component.literal("Kit Layout"));
        this.parent = parent;
    }

    private int panelHeight() {
        return HEADER_H + 22 + KITS.length * ROW_H + 18 + 20 + 16;
    }

    private int panelTop() {
        int h = panelHeight();
        return Math.max(2, Math.min(this.height / 2 - h / 2, this.height - h - 2));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = panelTop();
        int rowsTop = top + HEADER_H + 22;
        for (int i = 0; i < KITS.length; i++) {
            final String kit = KITS[i];
            this.addRenderableWidget(new McrButton(cx - 100, rowsTop + i * ROW_H, 200, 22,
                Component.literal("\u00a76" + kit),
                (b) -> com.p2ppvp.mod.customkit.KitEditorManager.launchLayoutEditor(this.minecraft, kit),
                this.font));
        }
        this.addRenderableWidget(new McrButton(cx - 50, rowsTop + KITS.length * ROW_H + 18, 100, 20,
            Component.literal("\u00a77Back"),
            (b) -> this.minecraft.setScreen(this.parent), this.font));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
        // Opaque backdrop: do not draw the parent screen underneath.
        g.fill(0, 0, this.width, this.height, 0xFF050507);

        int cx = this.width / 2;
        int h = panelHeight();
        int top = panelTop();
        int l = cx - PANEL_W / 2, r = cx + PANEL_W / 2, b = top + h;

        // Panel body, outline and header strip
        g.fill(l - 1, top - 1, r + 1, b + 1, 0xFF000000);
        g.fill(l, top, r, b, 0xFF0B0B0F);
        g.fill(l, top, r, top + HEADER_H, 0xFF14141A);
        g.fill(l, top + HEADER_H, r, top + HEADER_H + 1, GOLD);
        g.fill(l, top, l + 1, b, GOLD);
        g.fill(r - 1, top, r, b, GOLD);
        g.fill(l, top, r, top + 1, GOLD);
        g.fill(l, b - 1, r, b, GOLD);

        // Header text
        g.centeredText(this.font, "\u00a76\u00a7lKIT LAYOUT", cx, top + 12, 0xFFFFFFFF);
        g.centeredText(this.font, "\u00a77Arrange where each item sits in your inventory", cx, top + 26, 0xFFBBBBBB);

        // Hint above the kit rows
        g.centeredText(this.font, "\u00a78Choose a ranked kit", cx, top + HEADER_H + 9, 0xFF888888);

        super.extractRenderState(g, mx, my, d);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
