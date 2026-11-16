package com.p2ppvp.mod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Beacon-style 22x22 icon button (same sprites as the beacon screen's confirm and cancel buttons). */
public class LayoutIconButton extends AbstractButton {
    private static final Identifier BTN = Identifier.tryParse("minecraft:container/beacon/button");
    private static final Identifier BTN_HOT = Identifier.tryParse("minecraft:container/beacon/button_highlighted");
    private final Identifier icon;
    private final String letter;
    private final Runnable action;

    public LayoutIconButton(int x, int y, Component label, String iconSprite, String letter, Runnable action) {
        super(x, y, 22, 22, label);
        this.icon = iconSprite == null ? null : Identifier.tryParse(iconSprite);
        this.letter = letter;
        this.action = action;
    }

    @Override
    public void onPress(InputWithModifiers input) { action.run(); }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, this.isHoveredOrFocused() ? BTN_HOT : BTN, getX(), getY(), 22, 22);
        if (icon != null) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, icon, getX() + 2, getY() + 2, 18, 18);
        } else if (letter != null) {
            g.centeredText(Minecraft.getInstance().font, letter, getX() + 11, getY() + 7, 0xFFFFFFFF);
        }
        if (this.isHoveredOrFocused()) g.setTooltipForNextFrame(Minecraft.getInstance().font, getMessage(), mx, my);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) { this.defaultButtonNarrationText(out); }
}
