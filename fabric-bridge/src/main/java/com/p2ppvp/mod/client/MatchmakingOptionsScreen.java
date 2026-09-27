package com.p2ppvp.mod.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

public class MatchmakingOptionsScreen extends Screen {
    // Safe static fields hosted outside TitleScreenMixin to prevent Mixin transformation issues
    public static int selectedPingLimit = 100;
    public static java.util.Set<String> selectedKits = new java.util.HashSet<>(java.util.Arrays.asList("Random"));

    private final Screen parent;

    // References to buttons for updating highlight state
    private final Map<Integer, McrButton> pingButtons = new HashMap<>();
    private final Map<String, McrButton> kitButtons = new HashMap<>();

    public MatchmakingOptionsScreen(Screen parent) {
        super(Component.literal("Matchmaking Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int cardHeight = 200;
        int top = centerY - cardHeight / 2;

        // 1. Initialize Ping buttons
        int[ ] pings = {50, 100, 300, 999}; // 999 is Unlimited
        String[ ] pingLabels = {"50ms", "100ms", "300ms", "Unlimited"};
        int pingBtnW = 60;
        int pingSpacing = 6;
        int totalPingW = 4 * pingBtnW + 3 * pingSpacing;
        int pingStartX = centerX - totalPingW / 2;

        for (int i = 0; i < pings.length; i++) {
            final int pVal = pings[i];
            McrButton btn = new McrButton(
                pingStartX + i * (pingBtnW + pingSpacing),
                top + 46,
                pingBtnW,
                20,
                Component.literal(pingLabels[i]),
                (b) -> {
                    selectedPingLimit = pVal;
                    updateHighlightStates();
                },
                this.font
            );
            this.pingButtons.put(pVal, btn);
            this.addRenderableWidget(btn);
        }

        // 2. Initialize Kit buttons (tabs) in two rows of 3 buttons (wider and less congested)
        String[ ] kits = {"Random", "Crystal", "UHC", "Pot", "Mace", "Sword"};
        int kitBtnW = 65;
        int kitSpacing = 6;
        int rowW = 3 * kitBtnW + 2 * kitSpacing;
        int kitStartX = centerX - rowW / 2;

        for (int i = 0; i < kits.length; i++) {
            final String kName = kits[i];
            int col = i % 3;
            int row = i / 3;
            int btnX = kitStartX + col * (kitBtnW + kitSpacing);
            int btnY = top + 98 + row * 24;

            McrButton btn = new McrButton(
                btnX,
                btnY,
                kitBtnW,
                20,
                Component.literal(kName),
                (b) -> {
                    toggleKitSelection(kName);
                    updateHighlightStates();
                },
                this.font
            );
            this.kitButtons.put(kName, btn);
            this.addRenderableWidget(btn);
        }

        // 3. Done/Close button at the bottom
        McrButton doneBtn = new McrButton(
            centerX - 50,
            top + 160,
            100,
            20,
            Component.literal("§aDone"),
            (b) -> {
                this.minecraft.setScreen(this.parent);
            },
            this.font
        );
        this.addRenderableWidget(doneBtn);

        // Update selected flags on buttons to reflect state on entrance
        updateHighlightStates();
    }

    private void toggleKitSelection(String kit) {
        if (kit.equals("Random")) {
            selectedKits.clear();
            selectedKits.add("Random");
        } else {
            // Unselect random if selecting individual kits
            selectedKits.remove("Random");

            if (selectedKits.contains(kit)) {
                selectedKits.remove(kit);
            } else {
                selectedKits.add(kit);
            }

            // If player cleared all, default back to Random
            if (selectedKits.isEmpty()) {
                selectedKits.add("Random");
            }
        }
    }

    private void updateHighlightStates() {
        // Highlight active ping limit
        for (Map.Entry<Integer, McrButton> entry : this.pingButtons.entrySet()) {
            entry.getValue().setSelected(selectedPingLimit == entry.getKey());
        }

        // Highlight active kits
        for (Map.Entry<String, McrButton> entry : this.kitButtons.entrySet()) {
            entry.getValue().setSelected(selectedKits.contains(entry.getKey()));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (this.parent != null) {
            this.parent.extractRenderState(context, mouseX, mouseY, delta);
        } else {
            super.extractRenderState(context, mouseX, mouseY, delta);
        }

        // Dark translucent overlay covering the title screen
        context.fill(0, 0, this.width, this.height, 0x88050507);

        // Draw the Matchmaking Options Modal Panel Card
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int cardWidth = 320;
        int cardHeight = 200;
        int cardX = centerX - cardWidth / 2;
        int cardY = centerY - cardHeight / 2;

        int left = cardX;
        int right = cardX + cardWidth;
        int top = cardY;
        int bottom = cardY + cardHeight;

        // Opaque Charcoal Backing
        context.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF000000);
        context.fill(left + 1, top + 1, right - 1, bottom - 1, 0xCC0B0B0F);

        // Pure Gold Border Trim
        int goldColor = 0xFFD4AF37;
        context.fill(left, top, left + 1, bottom, goldColor);       // Left Edge
        context.fill(right - 1, top, right, bottom, goldColor);     // Right Edge
        context.fill(left, top, right, top + 1, goldColor);         // Top Edge
        context.fill(left, bottom - 1, right, bottom, goldColor);   // Bottom Edge

        // Labels
        context.centeredText(this.font, "§6§l=== MATCHMAKING CONFIGURATION ===", centerX, top + 12, 0xFFFFFFFF);
        context.centeredText(this.font, "§7Select Max Latency Threshold", centerX, top + 34, 0xFFBBBBBB);
        
        // Muted instruction note deliberately placed directly under the latency threshold selectors
        context.centeredText(this.font, "§8(Higher threshold will result in faster matchmaking)", centerX, top + 71, 0xFF888888);
        
        // Active Queue formats section header shifted slightly down to maintain spacing balance
        context.centeredText(this.font, "§7Select Active Queue Formats", centerX, top + 88, 0xFFBBBBBB);

        // Renders our child widgets (the custom buttons) on top
        super.extractRenderState(context, mouseX, mouseY, delta);
    }
}
