package com.p2ppvp.mod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2ppvp.mod.client.ui.KitLayout;
import com.p2ppvp.mod.client.ui.LayoutBoard;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;

/** Arrange a ranked kit in a normal screen: click an item, then a slot. No world, always closable with Esc. */
public class KitLayoutEditorScreen extends Screen {
    private static final int S = 18;
    private final Screen parent;
    private final String kit;
    private final List<ItemStack> stacks = new ArrayList<>();
    private LayoutBoard board;
    private int selected = -1;
    private String error = null;

    public KitLayoutEditorScreen(Screen parent, String kit) {
        super(Component.literal("Arrange " + kit));
        this.parent = parent;
        this.kit = kit.toLowerCase(java.util.Locale.ROOT);
        load();
    }

    private static File layoutFile() { return new File("nml_inventory_layout.json"); }

    private JsonObject readLayouts() {
        try {
            File f = layoutFile();
            if (!f.exists()) return new JsonObject();
            return KitLayout.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) { return new JsonObject(); }
    }

    private void load() {
        List<Integer> defs = new ArrayList<>();
        try (var is = KitLayoutEditorScreen.class.getResourceAsStream("/assets/p2ppvp/kits/" + kit + ".json")) {
            JsonArray items = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("items");
            for (var el : items) {
                JsonObject o = el.getAsJsonObject();
                Item item = BuiltInRegistries.ITEM.get(Identifier.tryParse(o.get("id").getAsString())).map(Holder::value).orElse(null);
                if (item == null) continue;
                ItemStack st = new ItemStack(item, o.has("count") ? o.get("count").getAsInt() : 1);
                if (o.has("potion")) {
                    var key = ResourceKey.create(Registries.POTION, Identifier.tryParse(o.get("potion").getAsString()));
                    var holder = BuiltInRegistries.POTION.get(key).orElse(null);
                    if (holder != null) st.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS, new PotionContents(holder));
                }
                stacks.add(st);
                defs.add(o.get("slot").getAsInt());
            }
        } catch (Exception e) {
            error = "Could not load kit";
        }
        JsonObject root = readLayouts();
        this.board = new LayoutBoard(defs.stream().mapToInt(Integer::intValue).toArray(), d -> KitLayout.slotFor(root, kit, d));
    }

    private int left() { return this.width / 2 - 140; }
    private int top() { return Math.max(2, this.height / 2 - 80); }
    private int gridX() { return left() + 10; }

    private int slotX(int slot) { return gridX() + (slot % 9) * S; }
    private int slotY(int slot) { return slot < 9 ? top() + 116 : top() + 56 + (slot / 9 - 1) * S; }

    private int slotAt(double mx, double my) {
        for (int s = 0; s < LayoutBoard.MOVABLE; s++) {
            int x = slotX(s), y = slotY(s);
            if (mx >= x && mx < x + S && my >= y && my < y + S) return s;
        }
        return -1;
    }

    @Override
    protected void init() {
        int bx = left() + 184, bw = 86;
        this.addRenderableWidget(new McrButton(bx, top() + 56, bw, 20, Component.literal("\u00a7aSave"), (b) -> save(), this.font));
        this.addRenderableWidget(new McrButton(bx, top() + 80, bw, 20, Component.literal("\u00a7eReset"), (b) -> { board.reset(); selected = -1; }, this.font));
        this.addRenderableWidget(new McrButton(bx, top() + 104, bw, 20, Component.literal("\u00a7cCancel"), (b) -> onClose(), this.font));
    }

    private void save() {
        try {
            JsonObject mapping = board.toMapping();
            JsonObject root = KitLayout.withKit(readLayouts(), kit, mapping.size() == 0 ? null : mapping);
            Files.write(layoutFile().toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            this.minecraft.setScreen(parent);
        } catch (Exception e) {
            error = "Could not save layout";
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0) return false;
        int s = slotAt(event.x(), event.y());
        if (s < 0) return false;
        if (selected < 0) {
            if (board.itemAt(s) >= 0) selected = s;
        } else if (s == selected) {
            selected = -1;
        } else {
            board.move(selected, s);
            selected = -1;
        }
        return true;
    }

    private void drawSlot(GuiGraphicsExtractor g, int x, int y, boolean sel) {
        g.fill(x, y, x + S, y + S, 0xFF000000);
        g.fill(x + 1, y + 1, x + S - 1, y + S - 1, sel ? 0xFF8A6F00 : 0xFF3A3A3A);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
        g.fill(0, 0, this.width, this.height, 0xCC050507);
        int l = left(), t = top(), r = l + 280, b = t + 160;
        g.fill(l - 1, t - 1, r + 1, b + 1, 0xFF000000);
        g.fill(l + 1, t + 1, r - 1, b - 1, 0xEE0B0B0F);
        int gold = 0xFFD4AF37;
        g.fill(l, t, l + 1, b, gold);
        g.fill(r - 1, t, r, b, gold);
        g.fill(l, t, r, t + 1, gold);
        g.fill(l, b - 1, r, b, gold);
        g.centeredText(this.font, "\u00a76\u00a7l" + kit.substring(0, 1).toUpperCase() + kit.substring(1) + " Layout", l + 140, t + 8, 0xFFFFFFFF);
        g.text(this.font, "\u00a77Armor and offhand are fixed", gridX(), t + 20, 0xFFFFFFFF);

        int[] order = {39, 38, 37, 36, 40};
        for (int i = 0; i < order.length; i++) {
            int x = gridX() + i * S, y = t + 32;
            drawSlot(g, x, y, false);
            int it = board.itemAt(order[i]);
            if (it >= 0) { g.item(stacks.get(it), x + 1, y + 1); g.itemDecorations(this.font, stacks.get(it), x + 1, y + 1); }
        }
        for (int s = 0; s < LayoutBoard.MOVABLE; s++) drawSlot(g, slotX(s), slotY(s), s == selected);
        ItemStack hover = null;
        for (int s = 0; s < LayoutBoard.MOVABLE; s++) {
            int it = board.itemAt(s);
            if (it < 0) continue;
            int x = slotX(s), y = slotY(s);
            g.item(stacks.get(it), x + 1, y + 1);
            g.itemDecorations(this.font, stacks.get(it), x + 1, y + 1);
            if (mx >= x && mx < x + S && my >= y && my < y + S) hover = stacks.get(it);
        }
        String hint = error != null ? "\u00a7c" + error : (selected >= 0 ? "\u00a7eNow click a slot to move it" : "\u00a77Click an item, then a slot");
        g.text(this.font, hint, gridX(), t + 142, 0xFFFFFFFF);
        super.extractRenderState(g, mx, my, d);
        if (hover != null) g.setTooltipForNextFrame(this.font, hover, mx, my);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
