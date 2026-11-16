package com.p2ppvp.mod.client.skin;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * Click-the-avatar flow: opens the OS file dialog (tinyfd: Windows, macOS, Linux), validates the PNG and uploads it.
 * Cancel does nothing. If the native dialog cannot open at all, falls back to the drag-and-drop upload screen.
 * Everything runs off the render thread; progress is exposed through status() for the profile overlay.
 */
public final class SkinPicker {
    private static final SkinApi API = new SkinApi();
    private static volatile boolean busy = false;
    private static volatile String status = "";
    private static volatile int statusColor = 0xFFAAAAAA;
    private static volatile long statusUntil = 0L;

    private SkinPicker() {}

    public static boolean isBusy() {
        return busy;
    }

    /** Current status line, or "" when none (messages fade after a few seconds). */
    public static String status() {
        return !busy && System.currentTimeMillis() > statusUntil ? "" : status;
    }

    public static int statusColor() {
        return statusColor;
    }

    private static void set(String text, int color, long ttlMs) {
        status = text;
        statusColor = color;
        statusUntil = System.currentTimeMillis() + ttlMs;
    }

    /** Opens the file dialog and uploads the chosen skin. fallbackParent is the screen to return to from the drag-and-drop fallback. */
    public static void pickAndUpload(Screen fallbackParent) {
        if (busy) return;
        busy = true;
        Minecraft mc = Minecraft.getInstance();
        String name = mc.getUser() != null ? mc.getUser().getName() : null;
        Thread t = new Thread(() -> {
            try {
                if (name == null) {
                    set("Not signed in.", 0xFFFF5555, 4000);
                    return;
                }
                long started = System.currentTimeMillis();
                String picked;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer filters = stack.mallocPointer(1);
                    filters.put(stack.UTF8("*.png"));
                    filters.flip();
                    String home = System.getProperty("user.home", "");
                    String start = home.isEmpty() ? "" : home + java.io.File.separator;
                    picked = TinyFileDialogs.tinyfd_openFileDialog("Choose your skin (64x64 PNG)", start, filters, "PNG images", false);
                } catch (Throwable ex) {
                    openFallback(mc, fallbackParent);
                    return;
                }
                if (picked == null || picked.isEmpty()) {
                    // A dialog that returns instantly with nothing is almost certainly unavailable (no zenity/kdialog on Linux).
                    if (System.currentTimeMillis() - started < 400L) openFallback(mc, fallbackParent);
                    return; // cancelled: do nothing
                }
                upload(name, Path.of(picked));
            } finally {
                busy = false;
            }
        }, "p2ppvp-skin-picker");
        t.setDaemon(true);
        t.start();
    }

    private static void openFallback(Minecraft mc, Screen parent) {
        mc.execute(() -> mc.setScreen(new com.p2ppvp.mod.client.SkinUploadScreen(parent)));
    }

    private static void upload(String name, Path file) {
        byte[] data;
        try {
            if (Files.size(file) > SkinFileCheck.MAX_BYTES) {
                set("File too big (max " + (SkinFileCheck.MAX_BYTES / 1024) + " KB).", 0xFFFF5555, 6000);
                return;
            }
            data = Files.readAllBytes(file);
        } catch (Exception ex) {
            set("Could not read that file.", 0xFFFF5555, 6000);
            return;
        }
        String problem = SkinFileCheck.check(data);
        if (problem != null) {
            set(problem, 0xFFFF5555, 6000);
            return;
        }
        set("Uploading skin...", 0xFFAAAAAA, 15000);
        try {
            SkinApi.UploadResult r = API.upload(name, data).get(20, java.util.concurrent.TimeUnit.SECONDS);
            if (r != null && r.ok()) {
                SkinTextures.invalidate(name);
                set("Skin uploaded!", 0xFF55FF55, 5000);
            } else {
                set(r != null && r.error() != null ? r.error() : "Upload failed.", 0xFFFF5555, 6000);
            }
        } catch (Exception ex) {
            set("Upload failed (server unreachable).", 0xFFFF5555, 6000);
        }
    }
}
