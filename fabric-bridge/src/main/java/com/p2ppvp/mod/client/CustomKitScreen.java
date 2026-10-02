package com.p2ppvp.mod.client;

import com.p2ppvp.mod.customkit.CustomKit;
import com.p2ppvp.mod.customkit.CustomKitManager;
import com.p2ppvp.mod.customkit.KitEditorManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class CustomKitScreen extends Screen {
    private final Screen parent;

    public CustomKitScreen(Screen parent) {
        super(Component.literal("Custom Kits"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int cardWidth = 340;
        int cardHeight = 230;
        int top = centerY - cardHeight / 2;

        boolean isRandomCustomSelected = MatchmakingOptionsScreen.selectedKits.contains("Custom:Random") ||
                MatchmakingOptionsScreen.selectedKits.contains("Custom") && !MatchmakingOptionsScreen.selectedKits.stream().anyMatch(k -> k.startsWith("Custom:") && !k.equals("Custom:Random"));

        // 1. Queue Random Custom Kit button
        McrButton randomCustomBtn = new McrButton(
            centerX - 100,
            top + 34,
            200,
            20,
            Component.literal("§d🎲 Queue Random Custom Kit"),
            (b) -> {
                MatchmakingOptionsScreen.selectedKits.clear();
                MatchmakingOptionsScreen.selectedKits.add("Custom:Random");
                this.minecraft.setScreen(this.parent);
            },
            this.font
        );
        randomCustomBtn.setSelected(isRandomCustomSelected);
        this.addRenderableWidget(randomCustomBtn);

        // 2. List of Player's Saved Custom Kits
        List<CustomKit> kits = CustomKitManager.getCustomKits();
        int maxDisplayKits = 5;
        int displayCount = Math.min(kits.size(), maxDisplayKits);

        for (int i = 0; i < displayCount; i++) {
            CustomKit kit = kits.get(i);
            final String kitName = kit.getName();
            boolean isKitSelected = MatchmakingOptionsScreen.selectedKits.contains("Custom:" + kitName);

            int itemY = top + 74 + i * 22;

            // Selection Button
            McrButton kitBtn = new McrButton(
                centerX - 100,
                itemY,
                165,
                20,
                Component.literal((isKitSelected ? "§a✓ " : "") + kitName + " §8(" + kit.getItems().size() + " items)"),
                (b) -> {
                    MatchmakingOptionsScreen.selectedKits.clear();
                    MatchmakingOptionsScreen.selectedKits.add("Custom:" + kitName);
                    this.minecraft.setScreen(this.parent);
                },
                this.font
            );
            kitBtn.setSelected(isKitSelected);
            this.addRenderableWidget(kitBtn);

            // Delete Button
            McrButton deleteBtn = new McrButton(
                centerX + 70,
                itemY,
                30,
                20,
                Component.literal("§c✕"),
                (b) -> {
                    CustomKitManager.deleteCustomKit(kitName);
                    if (MatchmakingOptionsScreen.selectedKits.contains("Custom:" + kitName)) {
                        MatchmakingOptionsScreen.selectedKits.clear();
                        MatchmakingOptionsScreen.selectedKits.add("Random");
                    }
                    this.minecraft.setScreen(new CustomKitScreen(this.parent));
                },
                this.font
            );
            this.addRenderableWidget(deleteBtn);
        }

        // 3. Bottom Action Buttons
        int bottomY = top + cardHeight - 30;

        // Create New Kit button (launches singleplayer editor)
        McrButton createBtn = new McrButton(
            centerX - 100,
            bottomY,
            125,
            20,
            Component.literal("§a✚ Create New Kit"),
            (b) -> {
                KitEditorManager.launchEditor(this.minecraft);
            },
            this.font
        );
        this.addRenderableWidget(createBtn);

        // Back / Close button
        McrButton backBtn = new McrButton(
            centerX + 32,
            bottomY,
            68,
            20,
            Component.literal("Back"),
            (b) -> {
                this.minecraft.setScreen(this.parent);
            },
            this.font
        );
        this.addRenderableWidget(backBtn);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (this.parent != null) {
            this.parent.extractRenderState(context, mouseX, mouseY, delta);
        } else {
            super.extractRenderState(context, mouseX, mouseY, delta);
        }

        context.fill(0, 0, this.width, this.height, 0x88050507);

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int cardWidth = 340;
        int cardHeight = 230;
        int left = centerX - cardWidth / 2;
        int right = centerX + cardWidth / 2;
        int top = centerY - cardHeight / 2;
        int bottom = centerY + cardHeight / 2;

        // Card backing
        context.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF000000);
        context.fill(left + 1, top + 1, right - 1, bottom - 1, 0xDD0B0B0F);

        // Radiant amethyst / gold border trim
        int borderColor = 0xFFD4AF37;
        context.fill(left, top, left + 1, bottom, borderColor);
        context.fill(right - 1, top, right, bottom, borderColor);
        context.fill(left, top, right, top + 1, borderColor);
        context.fill(left, bottom - 1, right, bottom, borderColor);

        // Title and Subtitles
        context.centeredText(this.font, "§d§l=== CUSTOM KITS ===", centerX, top + 10, 0xFFFFFFFF);
        context.centeredText(this.font, "§7Select a kit to queue, play random, or create new", centerX, top + 22, 0xFFBBBBBB);

        List<CustomKit> kits = CustomKitManager.getCustomKits();
        context.centeredText(this.font, "§6Saved Custom Kits (" + kits.size() + "):", centerX, top + 60, 0xFFE0E0E0);

        if (kits.isEmpty()) {
            context.centeredText(this.font, "§8No custom kits saved yet.", centerX, top + 95, 0xFF888888);
            context.centeredText(this.font, "§8Click [✚ Create New Kit] to design one!", centerX, top + 115, 0xFF888888);
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }
}
