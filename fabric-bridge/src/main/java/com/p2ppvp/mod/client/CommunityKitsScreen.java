package com.p2ppvp.mod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2ppvp.mod.P2PPvpMod;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class CommunityKitsScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("p2ppvp-community-kits");
    private final Screen parent;

    public static class CommunityCategory {
        public String id;
        public String name;
        public String description;
        public String difficulty;
        public JsonObject kitJson;

        public CommunityCategory(String id, String name, String description, String difficulty, JsonObject kitJson) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.difficulty = difficulty != null ? difficulty : "normal";
            this.kitJson = kitJson;
        }
    }

        private static final List<CommunityCategory> cachedCategories = new ArrayList<>();
    private final List<CommunityCategory> displayedCategories = new ArrayList<>();
    private boolean isLoading = true;
    private String statusText = "Querying server for community kits...";

    public static List<CommunityCategory> getBuiltinCategories() {
        List<CommunityCategory> list = new ArrayList<>();

        // Fist
        JsonObject fistKit = new JsonObject();
        fistKit.addProperty("name", "Fist");
        fistKit.addProperty("category", "community");
        fistKit.addProperty("difficulty", "normal");
        JsonArray fistItems = new JsonArray();
        JsonObject star = new JsonObject();
        star.addProperty("slot", 13);
        star.addProperty("id", "minecraft:nether_star");
        star.addProperty("count", 1);
        fistItems.add(star);
        fistKit.add("items", fistItems);
        list.add(new CommunityCategory("fist", "Fist", "Hand combat duel with Nether Star", "normal", fistKit));

        // SpearDrift
        JsonObject spearKit = new JsonObject();
        spearKit.addProperty("name", "SpearDrift");
        spearKit.addProperty("category", "community");
        spearKit.addProperty("difficulty", "peaceful");
        JsonArray spearCmds = new JsonArray();
        spearCmds.add("difficulty peaceful");
        spearCmds.add("execute as @e run attribute @s minecraft:attack_speed base set 9999");
        spearCmds.add("attribute @a minecraft:attack_speed base set 9999");
        spearKit.add("commands", spearCmds);

        JsonArray spearItems = new JsonArray();
        JsonObject woodSpear = new JsonObject();
        woodSpear.addProperty("slot", 4);
        woodSpear.addProperty("id", "minecraft:wooden_spear");
        woodSpear.addProperty("count", 1);
        JsonArray woodEnchs = new JsonArray();
        JsonObject lungeWood = new JsonObject();
        lungeWood.addProperty("id", "minecraft:lunge");
        lungeWood.addProperty("lvl", 3);
        woodEnchs.add(lungeWood);
        woodSpear.add("enchantments", woodEnchs);
        spearItems.add(woodSpear);

        JsonObject cobble1 = new JsonObject();
        cobble1.addProperty("slot", 12);
        cobble1.addProperty("id", "minecraft:cobblestone");
        cobble1.addProperty("count", 64);
        spearItems.add(cobble1);

        JsonObject netheriteSpear = new JsonObject();
        netheriteSpear.addProperty("slot", 13);
        netheriteSpear.addProperty("id", "minecraft:netherite_spear");
        netheriteSpear.addProperty("count", 1);
        JsonArray nethEnchs = new JsonArray();
        JsonObject lungeNeth = new JsonObject();
        lungeNeth.addProperty("id", "minecraft:lunge");
        lungeNeth.addProperty("lvl", 3);
        nethEnchs.add(lungeNeth);
        netheriteSpear.add("enchantments", nethEnchs);
        spearItems.add(netheriteSpear);

        JsonObject cobble2 = new JsonObject();
        cobble2.addProperty("slot", 14);
        cobble2.addProperty("id", "minecraft:cobblestone");
        cobble2.addProperty("count", 64);
        spearItems.add(cobble2);

        spearKit.add("items", spearItems);
        list.add(new CommunityCategory("speardrift", "SpearDrift", "Spear duel with lunge on peaceful", "peaceful", spearKit));

        return list;
    }

    public static CommunityCategory findCategory(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String clean = name.trim();
        synchronized (cachedCategories) {
            for (CommunityCategory cat : cachedCategories) {
                if (cat.name.equalsIgnoreCase(clean) || cat.id.equalsIgnoreCase(clean)) {
                    return cat;
                }
            }
        }
        for (CommunityCategory cat : getBuiltinCategories()) {
            if (cat.name.equalsIgnoreCase(clean) || cat.id.equalsIgnoreCase(clean)) {
                return cat;
            }
        }
        return null;
    }

    public CommunityKitsScreen(Screen parent) {
        super(Component.literal("Community Kits"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!cachedCategories.isEmpty()) {
            this.displayedCategories.clear();
            this.displayedCategories.addAll(cachedCategories);
            this.isLoading = false;
        }

        rebuildCategoryWidgets();
        fetchServerCategories();
    }

    private void rebuildCategoryWidgets() {
        this.clearWidgets();

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int cardWidth = 340;
        int cardHeight = 230;
        int top = centerY - cardHeight / 2;

        int itemWidth = 240;
        int startY = top + 42;

                if (this.displayedCategories.isEmpty()) {
            this.displayedCategories.addAll(getBuiltinCategories());
        }

        for (int i = 0; i < this.displayedCategories.size(); i++) {
            CommunityCategory cat = this.displayedCategories.get(i);
            final String catName = cat.name;
            boolean isSelected = MatchmakingOptionsScreen.selectedKits.contains("Community:" + catName) ||
                                 MatchmakingOptionsScreen.selectedKits.contains("Unofficial:" + catName);

            String label = (isSelected ? "§a✓ " : "§e★ ") + catName;

            McrButton btn = new McrButton(
                centerX - itemWidth / 2,
                startY + i * 26,
                itemWidth,
                22,
                Component.literal(label),
                (b) -> {
                    MatchmakingOptionsScreen.selectedKits.clear();
                    MatchmakingOptionsScreen.selectedKits.add("Community:" + catName);
                    this.minecraft.setScreen(this.parent);
                },
                this.font
            );
            btn.setSelected(isSelected);
            this.addRenderableWidget(btn);
        }

        // Back button at bottom
        int bottomY = top + cardHeight - 28;
        McrButton backBtn = new McrButton(
            centerX - 50,
            bottomY,
            100,
            20,
            Component.literal("Back"),
            (b) -> this.minecraft.setScreen(this.parent),
            this.font
        );
        this.addRenderableWidget(backBtn);
    }

    private void fetchServerCategories() {
        Thread thread = new Thread(() -> {
            try {
                String baseUrl = P2PPvpMod.getMatchmakerUrl();
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(1200))
                    .build();

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/categories/community"))
                    .timeout(Duration.ofMillis(2000))
                    .GET()
                    .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
                    List<CommunityCategory> list = new ArrayList<>();
                    for (JsonElement el : array) {
                        JsonObject obj = el.getAsJsonObject();
                        String id = obj.has("id") ? obj.get("id").getAsString() : "unknown";
                        String name = obj.has("name") ? obj.get("name").getAsString() : id;
                        String desc = obj.has("description") ? obj.get("description").getAsString() : "";
                        String diff = obj.has("difficulty") ? obj.get("difficulty").getAsString() : "normal";
                        JsonObject kit = obj.has("kit") && obj.get("kit").isJsonObject() ? obj.getAsJsonObject("kit") : null;
                        list.add(new CommunityCategory(id, name, desc, diff, kit));
                    }

                    synchronized (cachedCategories) {
                        cachedCategories.clear();
                        cachedCategories.addAll(list);
                    }

                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> {
                            this.displayedCategories.clear();
                            this.displayedCategories.addAll(list);
                            this.isLoading = false;
                            this.statusText = "Loaded " + list.size() + " community kits from server.";
                            rebuildCategoryWidgets();
                        });
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Could not query server community kits, using default fallback: ", e);
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> {
                        this.isLoading = false;
                        this.statusText = "Offline mode - default community kits active.";
                        rebuildCategoryWidgets();
                    });
                }
            }
        }, "P2P-Community-Kits-Fetcher");
        thread.setDaemon(true);
        thread.start();
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
        int cardX = centerX - cardWidth / 2;
        int cardY = centerY - cardHeight / 2;

        int left = cardX;
        int right = cardX + cardWidth;
        int top = cardY;
        int bottom = cardY + cardHeight;

        context.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF000000);
        context.fill(left + 1, top + 1, right - 1, bottom - 1, 0xCC0B0B0F);

        int goldColor = 0xFFD4AF37;
        context.fill(left, top, left + 1, bottom, goldColor);
        context.fill(right - 1, top, right, bottom, goldColor);
        context.fill(left, top, right, top + 1, goldColor);
        context.fill(left, bottom - 1, right, bottom, goldColor);

        context.centeredText(this.font, "§6§l=== COMMUNITY KITS ===", centerX, top + 12, 0xFFFFFFFF);
        context.centeredText(this.font, "§7Select a community kit loaded from server", centerX, top + 26, 0xFFBBBBBB);

        if (this.isLoading && this.displayedCategories.isEmpty()) {
            context.centeredText(this.font, "§e" + this.statusText, centerX, centerY, 0xFFFFFF55);
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }
}
