package com.p2ppvp.mod.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.p2ppvp.mod.client.ui.LeaderboardRules;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LeaderboardScreen extends Screen {
        private final Screen parent;
    private static String getLeaderboardUrl() {
        return com.p2ppvp.mod.P2PPvpMod.getMatchmakerUrl() + "/api/leaderboard";
    }
    
    private static class LeaderboardEntry {
        String playerId;
        int elo;
        int wins;
        int losses;
        
        LeaderboardEntry(String playerId, int elo, int wins, int losses) {
            this.playerId = playerId;
            this.elo = elo;
            this.wins = wins;
            this.losses = losses;
        }
    }
    
    private final List<LeaderboardEntry> entries = new ArrayList<>();
    private String statusMessage = "Loading leaderboard...";
    private boolean isLoading = true;
    private int page = 0;
    private McrButton prevBtn;
    private McrButton nextBtn;
    
    // Category selection (ELO, Wins, Matches)
    private McrButton eloButton;
    private McrButton winsButton;
    private McrButton matchesButton;
    private String currentCategory = "elo";

    // Kit selection (Overall, Crystal, UHC, Pot, Mace, Sword)
    private final Map<String, McrButton> kitTabs = new HashMap<>();
    private String selectedKit = "overall";

    private static final Map<String, CachedLeaderboard> leaderboardCache = new HashMap<>();

    private static class CachedLeaderboard {
        final String json;
        final long timestamp;

        CachedLeaderboard(String json, long timestamp) {
            this.json = json;
            this.timestamp = timestamp;
        }
    }

    public LeaderboardScreen(Screen parent) {
        super(Component.literal("Leaderboard"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        
        // Category selections at Y = 26
        this.eloButton = this.addRenderableWidget(new McrButton(centerX - 155, 26, 100, 18, Component.literal("ELO"), (b) -> {
            this.currentCategory = "elo";
            updateTabStates();
            fetchLeaderboard();
        }, this.font));

        this.winsButton = this.addRenderableWidget(new McrButton(centerX - 50, 26, 100, 18, Component.literal("Wins"), (b) -> {
            this.currentCategory = "wins";
            updateTabStates();
            fetchLeaderboard();
        }, this.font));

        this.matchesButton = this.addRenderableWidget(new McrButton(centerX + 55, 26, 100, 18, Component.literal("Matches"), (b) -> {
            this.currentCategory = "matches";
            updateTabStates();
            fetchLeaderboard();
        }, this.font));

        // Kit filters at Y = 48
        String[ ] kits = {"Overall", "Crystal", "UHC", "Pot", "Mace", "Sword"};
        int kitBtnW = 50;
        int kitSpacing = 4;
        int totalKitW = kits.length * kitBtnW + (kits.length - 1) * kitSpacing;
        int kitStartX = centerX - totalKitW / 2;

        for (int i = 0; i < kits.length; i++) {
            final String kName = kits[i];
            McrButton btn = this.addRenderableWidget(new McrButton(
                kitStartX + i * (kitBtnW + kitSpacing),
                48,
                kitBtnW,
                16,
                Component.literal(kName),
                (b) -> {
                    this.selectedKit = kName.toLowerCase();
                    updateTabStates();
                    fetchLeaderboard();
                },
                this.font
            ));
            this.kitTabs.put(kName.toLowerCase(), btn);
        }

        // Close button at the bottom
        this.addRenderableWidget(new McrButton(centerX - 100, this.height - 30, 200, 20, Component.literal("Close"), (b) -> {
            this.minecraft.setScreen(this.parent);
        }, this.font));
        
        this.prevBtn = this.addRenderableWidget(new McrButton(centerX - 170, this.height - 30, 60, 20, Component.literal("< Prev"), (b) -> {
            this.page = Math.max(0, this.page - 1);
        }, this.font));
        this.nextBtn = this.addRenderableWidget(new McrButton(centerX + 110, this.height - 30, 60, 20, Component.literal("Next >"), (b) -> {
            this.page = this.page + 1;
        }, this.font));

        updateTabStates();
        fetchLeaderboard();
    }

    private void updateTabStates() {
        // Categories
        this.eloButton.setSelected(this.currentCategory.equals("elo"));
        this.winsButton.setSelected(this.currentCategory.equals("wins"));
        this.matchesButton.setSelected(this.currentCategory.equals("matches"));

        // Kits
        for (Map.Entry<String, McrButton> entry : this.kitTabs.entrySet()) {
            entry.getValue().setSelected(this.selectedKit.equals(entry.getKey()));
        }
    }

    private void fetchLeaderboard() {
        this.isLoading = true;
        this.statusMessage = "Loading...";
        this.entries.clear();
        this.page = 0;

        String cacheKey = currentCategory + "_" + selectedKit;
        CachedLeaderboard cached = leaderboardCache.get(cacheKey);
        long now = System.currentTimeMillis();

        if (cached != null && (now - cached.timestamp) < 60000) {
            parseLeaderboard(cached.json);
            this.isLoading = false;
            this.statusMessage = "";
            return;
        }
        
        Thread thread = new Thread(() -> {
            String queryUrl = getLeaderboardUrl() + "?category=" + currentCategory + "&kit=" + selectedKit;
            try {
                com.p2ppvp.mod.DebugLogger.log("[LEADERBOARD] Fetching leaderboard from: " + queryUrl);
                HttpClient client = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
                
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(queryUrl))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                com.p2ppvp.mod.DebugLogger.log("[LEADERBOARD] Received status code: " + response.statusCode());
                if (response.statusCode() == 200) {
                    String body = response.body();
                    leaderboardCache.put(cacheKey, new CachedLeaderboard(body, System.currentTimeMillis()));
                    parseLeaderboard(body);
                    this.isLoading = false;
                    this.statusMessage = "";
                } else {
                    this.statusMessage = "§cFailed to load (Status: " + response.statusCode() + ")";
                    this.isLoading = false;
                }
            } catch (Exception e) {
                com.p2ppvp.mod.DebugLogger.log("[LEADERBOARD] Failed to fetch leaderboard from " + queryUrl, e);
                this.statusMessage = "§cMatchmaker Server is offline!";
                this.isLoading = false;
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void parseLeaderboard(String json) {
        try {
            int index = 0;
            while (true) {
                int objStart = json.indexOf("{", index);
                if (objStart == -1) break;
                int objEnd = json.indexOf("}", objStart);
                if (objEnd == -1) break;
                
                String objStr = json.substring(objStart, objEnd + 1);
                String playerId = extractJSONValue(objStr, "player_id");
                int elo = extractJSONInt(objStr, "elo", 100);
                int wins = extractJSONInt(objStr, "wins", 0);
                int losses = extractJSONInt(objStr, "losses", 0);
                
                if (!playerId.isEmpty()) {
                    entries.add(new LeaderboardEntry(playerId, elo, wins, losses));
                }
                index = objEnd + 1;
            }
            com.p2ppvp.mod.DebugLogger.log("[LEADERBOARD] Successfully parsed " + entries.size() + " leaderboard records.");
        } catch (Exception e) {
            com.p2ppvp.mod.DebugLogger.log("[LEADERBOARD] Error parsing leaderboard JSON", e);
            this.statusMessage = "§cError parsing leaderboard data";
        }
    }

    private String extractJSONValue(String json, String key) {
        try {
            int index = json.indexOf("\"" + key + "\"");
            if (index == -1) return "";
            int start = json.indexOf(":", index) + 1;
            int valStart = json.indexOf("\"", start) + 1;
            int valEnd = json.indexOf("\"", valStart);
            if (valStart == 0 || valEnd == -1) return "";
            return json.substring(valStart, valEnd);
        } catch (Exception e) {
            return "";
        }
    }

    private int extractJSONInt(String json, String key, int def) {
        try {
            int index = json.indexOf("\"" + key + "\"");
            if (index == -1) return def;
            int start = json.indexOf(":", index) + 1;
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            if (end == -1) end = json.length();
            String val = json.substring(start, end).trim().replace("\"", "");
            return Integer.parseInt(val);
        } catch (Exception e) {
            return def;
        }
    }
    private net.minecraft.resources.Identifier selfSkin() {
        if (this.minecraft == null || this.minecraft.getUser() == null) return null;
        try {
            com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(this.minecraft.getUser().getProfileId(), this.minecraft.getUser().getName());
            var supplier = this.minecraft.getSkinManager().createLookup(profile, true);
            if (supplier != null) {
                var skin = supplier.get();
                if (skin != null && skin.body() != null) return skin.body().texturePath();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void drawFace(GuiGraphicsExtractor context, String name, boolean isSelf, int x, int y) {
        net.minecraft.resources.Identifier head = com.p2ppvp.mod.client.skin.SkinTextures.headTexture(name);
        if (head != null) {
            context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, head, x, y, 0.0f, 0.0f, 10, 10, 8, 8, 8, 8, 0xFFFFFFFF);
            return;
        }
        net.minecraft.resources.Identifier tex = isSelf ? selfSkin() : null;
        if (tex == null) tex = com.p2ppvp.mod.client.skin.SkinTextures.defaultTexture(name);
        if (tex != null) {
            context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, x, y, 8.0f, 8.0f, 10, 10, 8, 8, 64, 64, 0xFFFFFFFF);
            context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, x, y, 40.0f, 8.0f, 10, 10, 8, 8, 64, 64, 0xFFFFFFFF);
            return;
        }
        int h = Math.abs(name.toLowerCase().hashCode());
        int color = 0xFF000000 | (0x40 + (h % 0x80)) << 16 | (0x40 + ((h / 7) % 0x80)) << 8 | (0x40 + ((h / 49) % 0x80));
        context.fill(x, y, x + 10, y + 10, color);
        String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        context.centeredText(this.font, initial, x + 5, y + 1, 0xFFFFFFFF);
    }

    private void drawRow(GuiGraphicsExtractor context, LeaderboardRules.Row r, int index, boolean isSelf, int rowY, int centerX, int left, int right, boolean shaded) {
        if (isSelf) {
            context.fill(left + 4, rowY - 2, right - 4, rowY + 11, 0x33FFAA00);
        } else if (shaded) {
            context.fill(left + 4, rowY - 2, right - 4, rowY + 11, 0x14FFFFFF);
        }
        context.centeredText(this.font, LeaderboardRules.rankLabel(index), centerX - 148, rowY, LeaderboardRules.rankColor(index));
        drawFace(context, r.name(), isSelf, centerX - 128, rowY - 1);
        String nameStr = LeaderboardRules.shortName(r.name(), 16) + (isSelf ? " (You)" : "");
        context.text(this.font, nameStr, centerX - 114, rowY, isSelf ? 0xFFFFD27F : 0xFFFFFFFF);
        if (currentCategory.equals("elo")) {
            context.centeredText(this.font, "\u00a7a" + r.elo(), centerX + 110, rowY, 0xFFFFFFFF);
        } else if (currentCategory.equals("wins")) {
            context.centeredText(this.font, "\u00a72" + r.wins(), centerX + 60, rowY, 0xFFFFFFFF);
            context.centeredText(this.font, "\u00a7c" + r.losses(), centerX + 120, rowY, 0xFFFFFFFF);
        } else {
            context.centeredText(this.font, "\u00a7b" + r.matches(), centerX + 110, rowY, 0xFFFFFFFF);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        context.centeredText(this.font, "\u00a76\u00a7lMCR Leaderboard", centerX, 10, 0xFFFFFFFF);

        int left = centerX - 170;
        int right = centerX + 170;
        int top = 68;
        int bottom = this.height - 35;

        context.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF000000);
        context.fill(left + 1, top + 1, right - 1, bottom - 1, 0xD8111115);
        int goldColor = 0xFFD4AF37;
        context.fill(left, top, left + 1, bottom, goldColor);
        context.fill(right - 1, top, right, bottom, goldColor);
        context.fill(left, top, right, top + 1, goldColor);
        context.fill(left, bottom - 1, right, bottom, goldColor);

        boolean showPaging = false;
        if (isLoading) {
            context.centeredText(this.font, this.statusMessage, centerX, this.height / 2 + 15, 0xFFFFAA00);
        } else if (entries.isEmpty()) {
            String msg = this.statusMessage != null && !this.statusMessage.isEmpty() ? this.statusMessage : "No registered players found.";
            context.centeredText(this.font, msg, centerX, this.height / 2 + 15, 0xFF888888);
        } else {
            int startY = top + 6;
            context.centeredText(this.font, "\u00a7bRank", centerX - 148, startY, 0xFFFFFFFF);
            context.text(this.font, "\u00a7bPlayer", centerX - 114, startY, 0xFFFFFFFF);
            if (currentCategory.equals("elo")) {
                context.centeredText(this.font, "\u00a7bELO", centerX + 110, startY, 0xFFFFFFFF);
            } else if (currentCategory.equals("wins")) {
                context.centeredText(this.font, "\u00a7bWins", centerX + 60, startY, 0xFFFFFFFF);
                context.centeredText(this.font, "\u00a7bLosses", centerX + 120, startY, 0xFFFFFFFF);
            } else {
                context.centeredText(this.font, "\u00a7bMatches", centerX + 110, startY, 0xFFFFFFFF);
            }
            context.horizontalLine(left + 10, right - 10, startY + 11, 0x88D4AF37);

            List<LeaderboardRules.Row> rows = new ArrayList<>();
            for (LeaderboardEntry e : entries) rows.add(new LeaderboardRules.Row(e.playerId, e.elo, e.wins, e.losses));

            String me = this.minecraft != null && this.minecraft.getUser() != null ? this.minecraft.getUser().getName() : "";
            int selfIndex = LeaderboardRules.indexOfPlayer(rows, me);

            int rowHeight = 15;
            int footer = 14 + rowHeight + 2;
            int pageSize = Math.max(1, (bottom - (startY + 15) - footer) / rowHeight);
            this.page = LeaderboardRules.clampPage(this.page, rows.size(), pageSize);
            int[] range = LeaderboardRules.pageRange(this.page, rows.size(), pageSize);

            List<String> visibleNames = new ArrayList<>();
            for (int i = range[0]; i < range[1]; i++) visibleNames.add(rows.get(i).name());
            if (selfIndex >= 0) visibleNames.add(rows.get(selfIndex).name());
            com.p2ppvp.mod.client.skin.SkinTextures.requestHeads(visibleNames);

            for (int i = range[0]; i < range[1]; i++) {
                int rowY = startY + 14 + ((i - range[0]) * rowHeight);
                drawRow(context, rows.get(i), i, i == selfIndex, rowY, centerX, left, right, (i - range[0]) % 2 == 0);
            }

            if (LeaderboardRules.shouldPinSelf(selfIndex, this.page, rows.size(), pageSize)) {
                int pinY = bottom - 14 - rowHeight + 2;
                context.horizontalLine(left + 10, right - 10, pinY - 4, 0x88D4AF37);
                drawRow(context, rows.get(selfIndex), selfIndex, true, pinY, centerX, left, right, false);
            }

            int pages = LeaderboardRules.pageCount(rows.size(), pageSize);
            context.centeredText(this.font, "\u00a77Page " + (this.page + 1) + " / " + pages, centerX, bottom - 11, 0xFFFFFFFF);
            showPaging = pages > 1;
            this.prevBtn.active = this.page > 0;
            this.nextBtn.active = this.page < pages - 1;
        }
        this.prevBtn.visible = showPaging;
        this.nextBtn.visible = showPaging;
    }
}
