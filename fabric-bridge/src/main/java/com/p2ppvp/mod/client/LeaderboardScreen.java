package com.p2ppvp.mod.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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
    private static final String LEADERBOARD_URL = "http://127.0.0.1:8000/api/leaderboard";
    
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
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
                
                String queryUrl = LEADERBOARD_URL + "?category=" + currentCategory + "&kit=" + selectedKit;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(queryUrl))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    String body = response.body();
                    leaderboardCache.put(cacheKey, new CachedLeaderboard(body, System.currentTimeMillis()));
                    parseLeaderboard(body);
                    this.isLoading = false;
                    this.statusMessage = "";
                } else {
                    this.statusMessage = "§cFailed to load (Status: " + response.statusCode() + ")";
                }
            } catch (Exception e) {
                this.statusMessage = "§cMatchmaker Server is offline!";
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
                
                String objStr = json.substring(objStart, objEnd);
                String playerId = extractJSONValue(objStr, "player_id");
                int elo = extractJSONInt(objStr, "elo", 100);
                int wins = extractJSONInt(objStr, "wins", 0);
                int losses = extractJSONInt(objStr, "losses", 0);
                
                if (!playerId.isEmpty()) {
                    entries.add(new LeaderboardEntry(playerId, elo, wins, losses));
                }
                index = objEnd + 1;
            }
        } catch (Exception e) {
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
            String val = json.substring(start, end).trim().replace("\"", "");
            return Integer.parseInt(val);
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Draw the default menu background and Close button first
        super.extractRenderState(context, mouseX, mouseY, delta);
        
        int centerX = this.width / 2;
        context.centeredText(this.font, "§6§l=== MCR LEADERBOARD ===", centerX, 10, 0xFFFFFFFF);
        
        // Container bounds shifted downward to fit kit filter tabs (Y=48)
        int left = centerX - 170;
        int right = centerX + 170;
        int top = 68;
        int bottom = this.height - 35;

        // 1. Draw solid black backing shadow plate to ensure complete opacity under the panel
        context.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF000000);
        
        // 2. Draw the premium translucent deep dark charcoal glass panel inside
        context.fill(left + 1, top + 1, right - 1, bottom - 1, 0xD8111115);
        
        // 3. Draw pure solid metallic gold border lines (only 1px thick)
        int goldColor = 0xFFD4AF37;
        context.fill(left, top, left + 1, bottom, goldColor);       // Left edge
        context.fill(right - 1, top, right, bottom, goldColor);     // Right edge
        context.fill(left, top, right, top + 1, goldColor);         // Top edge
        context.fill(left, bottom - 1, right, bottom, goldColor);   // Bottom edge
        
        if (isLoading) {
            context.centeredText(this.font, this.statusMessage, centerX, this.height / 2 + 15, 0xFFFFAA00);
        } else if (entries.isEmpty()) {
            context.centeredText(this.font, "No registered players found.", centerX, this.height / 2 + 15, 0xFF888888);
        } else {
            int startY = top + 6;
            
            // Column Headers
            if (currentCategory.equals("elo")) {
                context.centeredText(this.font, "§bRank", centerX - 135, startY, 0xFFFFFFFF);
                context.text(this.font, "§bPlayer Name", centerX - 100, startY, 0xFFFFFFFF);
                context.centeredText(this.font, "§bELO Rating", centerX + 110, startY, 0xFFFFFFFF);
            } else if (currentCategory.equals("wins")) {
                context.centeredText(this.font, "§bRank", centerX - 135, startY, 0xFFFFFFFF);
                context.text(this.font, "§bPlayer Name", centerX - 100, startY, 0xFFFFFFFF);
                context.centeredText(this.font, "§bWins", centerX + 60, startY, 0xFFFFFFFF);
                context.centeredText(this.font, "§bLosses", centerX + 120, startY, 0xFFFFFFFF);
            } else if (currentCategory.equals("matches")) {
                context.centeredText(this.font, "§bRank", centerX - 135, startY, 0xFFFFFFFF);
                context.text(this.font, "§bPlayer Name", centerX - 100, startY, 0xFFFFFFFF);
                context.centeredText(this.font, "§bMatches Played", centerX + 110, startY, 0xFFFFFFFF);
            }
            
            // Sleek gold divider line separating headers and rows
            context.horizontalLine(left + 10, right - 10, startY + 11, 0x88D4AF37);
            
            // Dynamic scaling layout to prevent overlapping bottom elements
            int rowHeight = 15;
            int maxRows = (bottom - (startY + 15)) / rowHeight;
            
            String localPlayerName = this.minecraft != null && this.minecraft.getUser() != null ? this.minecraft.getUser().getName() : "";
            
            for (int i = 0; i < Math.min(entries.size(), maxRows); i++) {
                LeaderboardEntry entry = entries.get(i);
                int rowY = startY + 14 + (i * rowHeight);
                
                boolean isSelf = !localPlayerName.isEmpty() && entry.playerId.equalsIgnoreCase(localPlayerName);
                
                if (isSelf) {
                    context.fill(left + 4, rowY - 2, right - 4, rowY + 11, 0x33FFAA00);
                } else if (i % 2 == 0) {
                    context.fill(left + 4, rowY - 2, right - 4, rowY + 11, 0x14FFFFFF);
                }
                
                String rankStr = "§7#" + (i + 1);
                if (i == 0) rankStr = "§e§l🥇 #1";
                else if (i == 1) rankStr = "§7§l🥈 #2";
                else if (i == 2) rankStr = "§6§l🥉 #3";
                
                String nameStr = entry.playerId;
                if (nameStr.length() > 18) {
                    nameStr = nameStr.substring(0, 16) + "...";
                }
                
                if (isSelf) {
                    nameStr = "§e★ " + nameStr + " (You)";
                }
                
                context.centeredText(this.font, rankStr, centerX - 135, rowY, 0xFFFFFFFF);
                context.text(this.font, nameStr, centerX - 100, rowY, 0xFFFFFFFF);
                
                if (currentCategory.equals("elo")) {
                    context.centeredText(this.font, "§a" + entry.elo, centerX + 110, rowY, 0xFFFFFFFF);
                } else if (currentCategory.equals("wins")) {
                    context.centeredText(this.font, "§2" + entry.wins, centerX + 60, rowY, 0xFFFFFFFF);
                    context.centeredText(this.font, "§c" + entry.losses, centerX + 120, rowY, 0xFFFFFFFF);
                } else if (currentCategory.equals("matches")) {
                    int totalMatches = entry.wins + entry.losses;
                    context.centeredText(this.font, "§b" + totalMatches, centerX + 110, rowY, 0xFFFFFFFF);
                }
            }
        }
    }
}
