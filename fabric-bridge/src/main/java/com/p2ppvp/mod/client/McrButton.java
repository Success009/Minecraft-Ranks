package com.p2ppvp.mod.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class McrButton extends Button {
    private final Font font;
    private boolean selected = false;

    public McrButton(int x, int y, int width, int height, Component message, OnPress onPress, Font font) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.font = font;
    }

    public boolean isSelected() {
        return this.selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    public void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (!this.visible) {
            return;
        }

        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();

        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;

        int bg;
        int goldColor = 0xFFD4AF37;
        int borderColor;
        int textColor;

        if (!this.active) {
            bg = 0x550B0B0F; // muted dark
            borderColor = 0x33444444; // muted gray border
            textColor = 0xFF777777; // gray text
        } else if (this.selected) {
            bg = 0x55D4AF37; // translucent gold highlight
            borderColor = goldColor; // radiant gold border
            textColor = 0xFFFFFFFF; // bright white
        } else if (hovered) {
            bg = 0xCC15151A; // translucent charcoal
            borderColor = goldColor; // radiant gold border
            textColor = 0xFFFFFFFF; // bright white
        } else {
            bg = 0x220B0B0F; // very subtle dark glass background
            borderColor = 0x22D4AF37; // extremely subtle faint gold border
            textColor = 0xFFCCCCCC; // metallic silver text
        }

        // Draw solid background fill
        context.fill(x, y, x + w, y + h, bg);

        // Draw 1px vector border using fill calls (safe, no texture dependencies)
        if (borderColor != 0) {
            context.fill(x, y, x + 1, y + h, borderColor);       // Left Edge
            context.fill(x + w - 1, y, x + w, y + h, borderColor); // Right Edge
            context.fill(x, y, x + w, y + 1, borderColor);         // Top Edge
            context.fill(x, y + h - 1, x + w, y + h, borderColor); // Bottom Edge
        }

        // Draw centered button text
        String textStr = this.getMessage().getString();
        int textX = x + w / 2;
        int textY = y + (h - 8) / 2;

        context.centeredText(this.font, textStr, textX, textY, textColor);
    }
}
