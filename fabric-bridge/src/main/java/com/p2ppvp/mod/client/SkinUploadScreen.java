package com.p2ppvp.mod.client;

import com.p2ppvp.mod.client.skin.SkinApi;
import com.p2ppvp.mod.client.skin.SkinFileCheck;
import com.p2ppvp.mod.client.skin.SkinTextures;
import com.p2ppvp.mod.client.ui.McrLayout;
import com.p2ppvp.mod.client.ui.McrLayout.Rect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** Upload your own 64x64 skin PNG: Choose file button (native dialog) or drag a PNG onto the window. */
public class SkinUploadScreen extends Screen {
    private final Screen parent;
    private final SkinApi api = new SkinApi();
    private volatile String status = "Choose a 64x64 PNG, or drag one onto this window.";
    private volatile int statusColor = 0xFFAAAAAA;
    private volatile boolean busy = false;

    private Rect card, chooseBtn, backBtn;

    public SkinUploadScreen(Screen parent) {
        super(Component.literal("Upload skin"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        card = McrLayout.card(this.width, this.height, 300, 130, 8);
        Rect row = new Rect(card.x() + 6, card.bottom() - 6 - 18, card.w() - 12, 18);
        Rect[] two = McrLayout.splitRow(row, 6);
        chooseBtn = two[0];
        backBtn = two[1];
    }

    private String me() {
        return this.minecraft != null && this.minecraft.getUser() != null ? this.minecraft.getUser().getName() : null;
    }

    private void setStatus(String text, int color) {
        status = text;
        statusColor = color;
    }

    private void chooseFile() {
        if (busy) return;
        busy = true;
        setStatus("Opening file dialog...", 0xFFAAAAAA);
        Thread t = new Thread(() -> {
            String picked = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.png"));
                filters.flip();
                picked = TinyFileDialogs.tinyfd_openFileDialog("Choose skin PNG", "", filters, "PNG images", false);
            } catch (Throwable ex) {
                busy = false;
                setStatus("File dialog unavailable. Drag a PNG onto this window instead.", 0xFFFF5555);
                return;
            }
            if (picked == null || picked.isEmpty()) {
                busy = false;
                setStatus("No file chosen.", 0xFFAAAAAA);
                return;
            }
            handle(Path.of(picked));
        }, "p2ppvp-skin-picker");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        if (paths == null || paths.isEmpty() || busy) return;
        busy = true;
        Path first = paths.get(0);
        Thread t = new Thread(() -> handle(first), "p2ppvp-skin-drop");
        t.setDaemon(true);
        t.start();
    }

    /** Runs off the render thread. Caller has set busy = true. Clears busy on every path. */
    private void handle(Path file) {
        String name = me();
        if (name == null) {
            busy = false;
            setStatus("Not signed in.", 0xFFFF5555);
            return;
        }
        byte[] data;
        try {
            if (Files.size(file) > SkinFileCheck.MAX_BYTES) {
                busy = false;
                setStatus("File too big (max " + (SkinFileCheck.MAX_BYTES / 1024) + " KB).", 0xFFFF5555);
                return;
            }
            data = Files.readAllBytes(file);
        } catch (Exception ex) {
            busy = false;
            setStatus("Could not read that file.", 0xFFFF5555);
            return;
        }
        String problem = SkinFileCheck.check(data);
        if (problem != null) {
            busy = false;
            setStatus(problem, 0xFFFF5555);
            return;
        }
        setStatus("Uploading...", 0xFFAAAAAA);
        api.upload(name, data).whenComplete((r, ex) -> {
            try {
                if (ex == null && r != null && r.ok()) {
                    SkinTextures.invalidate(name);
                    setStatus("Skin uploaded! Everyone will see it soon.", 0xFF55FF55);
                } else {
                    String err = r != null && r.error() != null ? r.error() : "Upload failed.";
                    setStatus(err, 0xFFFF5555);
                }
            } finally {
                busy = false;
            }
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0xAA000000);
        g.fill(card.x() - 1, card.y() - 1, card.right() + 1, card.bottom() + 1, 0xFFD4AF37);
        g.fill(card.x(), card.y(), card.right(), card.bottom(), 0xFF0D1017);
        int cx = card.x() + card.w() / 2;
        g.centeredText(this.font, "§6§lUpload skin", cx, card.y() + 8, 0xFFFFFFFF);
        g.centeredText(this.font, "§7Must be a 64x64 PNG, up to 64 KB.", cx, card.y() + 26, 0xFFFFFFFF);
        g.centeredText(this.font, status, cx, card.y() + 52, statusColor);
        drawButton(g, chooseBtn, busy ? "Working..." : "Choose file", !busy, mouseX, mouseY, 0xFFB8860B);
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
            if (chooseBtn.contains(mx, my)) { chooseFile(); return true; }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
