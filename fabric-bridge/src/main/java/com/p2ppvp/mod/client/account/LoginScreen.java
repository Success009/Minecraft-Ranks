package com.p2ppvp.mod.client.account;

import com.p2ppvp.mod.client.ui.McrLayout;
import com.p2ppvp.mod.client.ui.McrLayout.Rect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Create account / Sign in. Two fields and one button, nothing else is asked.
 * The name is locked to the Minecraft name for now (the server checks the token against it).
 * After creating an account the recovery code is shown once; the player may ignore it.
 */
public class LoginScreen extends Screen {
    private final Screen parent;
    private final AccountApi api = new AccountApi();
    private EditBox userBox, passBox;
    private boolean registerMode;
    private volatile boolean busy = false;
    private volatile String status = "";
    private volatile int statusColor = 0xFFAAAAAA;
    private volatile String recoveryCode = null;   // set after register: shows the one-time screen

    private Rect card, primaryBtn, switchBtn, backBtn;

    public LoginScreen(Screen parent) {
        super(Component.literal("Account"));
        this.parent = parent;
        this.registerMode = true;
    }

    private String mcName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getUser() != null ? mc.getUser().getName() : "";
    }

    @Override
    protected void init() {
        card = McrLayout.card(this.width, this.height, 260, 170, 8);
        int fx = card.x() + 20, fw = card.w() - 40;
        String keepUser = userBox != null ? userBox.getValue() : mcName();
        String keepPass = passBox != null ? passBox.getValue() : "";

        userBox = new EditBox(this.font, fx, card.y() + 38, fw, 18, Component.literal("Name"));
        userBox.setMaxLength(16);
        userBox.setValue(keepUser);
        userBox.setEditable(false);   // locked to the Minecraft name
        this.addRenderableWidget(userBox);

        passBox = new EditBox(this.font, fx, card.y() + 72, fw, 18, Component.literal("Password"));
        passBox.setMaxLength(128);
        passBox.setHint(Component.literal("Password"));
        passBox.addFormatter((s, i) -> net.minecraft.util.FormattedCharSequence.forward(
            "*".repeat(s.length()), net.minecraft.network.chat.Style.EMPTY));
        passBox.setValue(keepPass);
        this.addRenderableWidget(passBox);
        this.setInitialFocus(passBox);

        Rect row = new Rect(card.x() + 10, card.bottom() - 10 - 18, card.w() - 20, 18);
        Rect[] two = McrLayout.splitRow(row, 6);
        primaryBtn = new Rect(card.x() + 10, card.bottom() - 10 - 18 - 24, card.w() - 20, 18);
        switchBtn = two[0];
        backBtn = two[1];
        userBox.visible = passBox.visible = recoveryCode == null;
    }

    private void setStatus(String t, int c) { status = t; statusColor = c; }

    private void submit() {
        if (busy) return;
        String user = userBox.getValue().trim();
        String pass = passBox.getValue();
        if (user.isEmpty()) { setStatus("No Minecraft name found.", 0xFFFF5555); return; }
        if (pass.length() < 6) { setStatus(AccountApi.friendly("bad_password"), 0xFFFF5555); return; }
        busy = true;
        setStatus(registerMode ? "Creating account..." : "Signing in...", 0xFFAAAAAA);
        var call = registerMode ? api.register(user, pass) : api.login(user, pass);
        call.whenComplete((r, ex) -> {
            try {
                if (ex != null || r == null) { setStatus("Something went wrong", 0xFFFF5555); return; }
                if (!r.ok()) { setStatus(r.message(), 0xFFFF5555); return; }
                String name = r.username() != null ? r.username() : user;
                AccountSession.get().set(name, r.token());
                Minecraft.getInstance().execute(() -> {
                    if (registerMode && r.recoveryCode() != null) {
                        recoveryCode = r.recoveryCode();
                        userBox.visible = passBox.visible = false;
                        setStatus("", 0xFFAAAAAA);
                    } else {
                        onClose();
                    }
                });
            } finally {
                busy = false;
            }
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0xFF0A0A0F);
        g.fill(card.x() - 1, card.y() - 1, card.right() + 1, card.bottom() + 1, 0xFFD4AF37);
        g.fill(card.x(), card.y(), card.right(), card.bottom(), 0xFF0D1017);
        int cx = card.x() + card.w() / 2;

        if (recoveryCode != null) {
            g.centeredText(this.font, "§a§lAccount created", cx, card.y() + 10, 0xFFFFFFFF);
            g.centeredText(this.font, "§7Save this recovery code somewhere safe.", cx, card.y() + 32, 0xFFFFFFFF);
            g.centeredText(this.font, "§7It lets you reset your password. You can skip this.", cx, card.y() + 44, 0xFFFFFFFF);
            g.centeredText(this.font, "§e§l" + recoveryCode, cx, card.y() + 70, 0xFFFFFFFF);
            drawButton(g, primaryBtn, "I saved it", true, mouseX, mouseY, 0xFFB8860B);
        } else {
            g.centeredText(this.font, registerMode ? "§6§lCreate account" : "§6§lSign in", cx, card.y() + 10, 0xFFFFFFFF);
            g.text(this.font, "§7Name", card.x() + 20, card.y() + 28, 0xFFFFFFFF);
            g.text(this.font, "§7Password", card.x() + 20, card.y() + 62, 0xFFFFFFFF);
            g.centeredText(this.font, status, cx, card.y() + 96, statusColor);
            drawButton(g, primaryBtn, busy ? "Working..." : (registerMode ? "Create account" : "Sign in"),
                !busy, mouseX, mouseY, 0xFFB8860B);
            drawButton(g, switchBtn, registerMode ? "I have an account" : "New here?", true, mouseX, mouseY, 0xFF3A3F4B);
            drawButton(g, backBtn, "Quit Game", true, mouseX, mouseY, 0xFF3A3F4B);
        }
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
            if (recoveryCode != null) {
                if (primaryBtn.contains(mx, my)) { onClose(); return true; }
                return true;
            }
            if (primaryBtn.contains(mx, my)) { submit(); return true; }
            if (switchBtn.contains(mx, my)) { registerMode = !registerMode; setStatus("", 0xFFAAAAAA); return true; }
            if (backBtn.contains(mx, my)) { this.minecraft.stop(); return true; }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /** Gate: no way out except signing in (or quitting the game). */
    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void onClose() {
        if (!AccountSession.get().signedIn()) return;
        this.minecraft.setScreen(this.parent);
    }
}
