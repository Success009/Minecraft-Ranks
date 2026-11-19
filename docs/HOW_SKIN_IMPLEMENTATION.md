# In-game skin rendering (porting guide)

Audience: an agent adding in-game skin rendering to a different Fabric project.
Source: P2P-PvP-Framework, Minecraft 26.1.2, Mojang mappings.

Scope: ONLY how a skin is shown in game, and the profile hover/click UI for changing it.
Not covered on purpose: server, token lookup, downloading, caching. The target project already has its own skin query.

## 1. What the target project must provide

The renderer needs only these three things from the existing skin query code. Everything below calls them.

```java
Identifier fullTexture(String playerName); // registered 64x64 skin texture, or null if none
boolean    isSlim(String playerName);      // true if the skin uses 3 px (Alex) arms
Identifier headTexture(String playerName); // optional: registered 8x8 head texture, or null (UI only)
```

Player names are compared lowercase. If a function returns null, the renderer falls back to the normal vanilla skin.

## 2. Registering a downloaded skin as a texture

Once the PNG bytes exist, register them on the render thread. Never register from a network thread.

```java
NativeImage img = NativeImage.read(bytes);                       // any thread
boolean slim = SkinModel.isSlim(img.getPixelsABGR(), img.getWidth(), img.getHeight());
Minecraft mc = Minecraft.getInstance();
mc.execute(() -> {                                               // render thread
    Identifier id = Identifier.fromNamespaceAndPath("p2ppvp", "skins/full/" + name + "-" + token);
    mc.getTextureManager().register(id, new DynamicTexture(() -> "skin " + name, img));
    Identifier old = FULLS.put(name, id);                        // FULLS: Map<String, Identifier>
    if (old != null && !old.equals(id)) mc.getTextureManager().release(old);
});
```

- Use a NEW identifier per skin version (name + token or hash). Re-registering the same id does not refresh reliably.
- Release the previous texture of that player, or textures leak.
- Heads work the same way (8x8 PNG, key `HEADS`, path `skins/head/...`).

### Slim arm detection (pure, no Minecraft imports)

```java
public static boolean isSlim(int[] pixels, int width, int height) {
    if (pixels == null || width != 64 || height != 64 || pixels.length < 64 * 64) return false;
    for (int y = 20; y <= 31; y++)
        for (int x = 54; x <= 55; x++)
            if (((pixels[y * 64 + x] >>> 24) & 0xFF) != 0) return false;
    return true;
}
```

A slim skin leaves the right-arm columns x=54..55, rows y=20..31 fully transparent. Alpha is the top byte.

## 3. Swapping the skin in the world: SkinOverride

One central, fail-soft function. It builds a new `PlayerSkin` from the vanilla one and keeps cape, elytra and secure flag.

```java
public static PlayerSkin apply(PlayerSkin vanilla, String name) {
    try {
        if (vanilla == null || name == null || name.isEmpty()) return vanilla;
        // (source project had an AutoUpdater kill-switch check here: drop it)
        // Optional: trigger a lazy skin request for this name here, if the project's query needs a push
        Identifier tex = fullTexture(name);
        if (tex == null) return vanilla;
        if (vanilla.body() != null && tex.equals(vanilla.body().texturePath())) return vanilla;
        PlayerModelType model = isSlim(name) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
        return new PlayerSkin(new ClientAsset.ResourceTexture(tex, tex),
                              vanilla.cape(), vanilla.elytra(), model, vanilla.secure());
    } catch (Throwable t) {
        return vanilla;
    }
}
```

Imports: `net.minecraft.core.ClientAsset`, `net.minecraft.resources.Identifier`,
`net.minecraft.world.entity.player.PlayerModelType`, `net.minecraft.world.entity.player.PlayerSkin`.

`getSkin()` is called every frame, so this must stay cheap: map lookups only, no I/O, no network.

## 4. The two mixins (this is what makes the skin render)

Both inject at `RETURN` of `getSkin()` and replace the return value. Both are wrapped in try/catch so they can never break rendering.

World model, first-person arm, map hand, spectated players, slim/wide renderer choice:

```java
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerSkinMixin {
    @Inject(method = "getSkin()Lnet/minecraft/world/entity/player/PlayerSkin;", at = @At("RETURN"), cancellable = true)
    private void p2ppvp$overrideSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        try {
            AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
            String name = self.getGameProfile().name();
            PlayerSkin out = SkinOverride.apply(cir.getReturnValue(), name);
            if (out != cir.getReturnValue()) cir.setReturnValue(out);
        } catch (Throwable ignored) {}
    }
}
```

Tab list and spectator menu:

```java
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoSkinMixin {
    @Inject(method = "getSkin()Lnet/minecraft/world/entity/player/PlayerSkin;", at = @At("RETURN"), cancellable = true)
    private void p2ppvp$overrideSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        try {
            PlayerInfo self = (PlayerInfo) (Object) this;
            String name = self.getProfile().name();
            PlayerSkin out = SkinOverride.apply(cir.getReturnValue(), name);
            if (out != cir.getReturnValue()) cir.setReturnValue(out);
        } catch (Throwable ignored) {}
    }
}
```

Register both under `"client"` in the project's mixin json (class names only, relative to the `package` field):

```json
"client": [ "PlayerInfoSkinMixin", "AbstractClientPlayerSkinMixin" ]
```

WARNING: the source config has `"injectors": {"defaultRequire": 1}`. If a mixin target method does not exist (for example after a Minecraft update), the game CRASHES at launch. If the target project's mixin config differs, keep this in mind and read `latest.log` first when the game fails to start.

Name matching: the override is keyed by the player's profile name (lowercase). The skin shows for any rendered player whose name has a registered texture.

## 5. Default skin fallback (optional, for UI heads of players with no skin)

For a stable default per name:

```java
UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name.toLowerCase()).getBytes(StandardCharsets.UTF_8));
PlayerSkin s = net.minecraft.client.resources.DefaultPlayerSkin.get(id);
Identifier tex = s.body().texturePath();   // 64x64 sheet
```

Same name always gives the same default skin. Not tested in game in the source project.

## 6. Profile screen: hover overlay and click to change skin

The profile card in the source project is drawn inside `TitleScreenMixin` (project specific: do the same in whatever screen shows the profile).

### The edit icon asset

```
fabric-bridge/src/main/resources/assets/p2ppvp/textures/gui/edit_icon.png
```

64x64, 8-bit RGBA, white pencil on transparent. Copy this file into the target project at
`src/main/resources/assets/<modid>/textures/gui/edit_icon.png` and change the namespace in the code below from `p2ppvp` to the target mod id.

### Drawing the avatar and the hover effect

The avatar is a 36x36 square at `(profAvatarX, profAvatarY)`. Head drawing: use the 8x8 head texture if available, otherwise the face (8,8) and hat layer (40,8) from the 64x64 sheet.

```java
// head: 8x8 head texture, scaled to 36x36
context.blit(RenderPipelines.GUI_TEXTURED, headTex, x, y, 0.0f, 0.0f, 36, 36, 8, 8, 8, 8, 0xFFFFFFFF);

// fallback from a 64x64 sheet: face, then hat layer on top
context.blit(RenderPipelines.GUI_TEXTURED, sheet, x, y,  8.0f, 8.0f, 36, 36, 8, 8, 64, 64, 0xFFFFFFFF);
context.blit(RenderPipelines.GUI_TEXTURED, sheet, x, y, 40.0f, 8.0f, 36, 36, 8, 8, 64, 64, 0xFFFFFFFF);
```

Hover overlay, drawn AFTER the avatar and its frame (translucent black dim, then the pencil at 24x24 centered with a 6 px margin):

```java
if (mouseX >= x && mouseX < x + 36 && mouseY >= y && mouseY < y + 36 && !SkinPicker.isBusy()) {
    context.fill(x, y, x + 36, y + 36, 0x99000000);
    context.blit(RenderPipelines.GUI_TEXTURED,
        Identifier.fromNamespaceAndPath("<modid>", "textures/gui/edit_icon.png"),
        x + 6, y + 6, 0.0f, 0.0f, 24, 24, 64, 64, 64, 64, 0xDDFFFFFF);
}
```

The overlay is hidden while an upload is busy. The source project also draws a 1 px gold frame around the avatar (four `context.fill` strips); this is cosmetic.

### Click handling

In the screen's mouse click handler, check the same 36x36 rectangle (using the same coordinates as the drawing code). In the source project the avatar is not clickable on the History tab.

```java
if (mouseX >= avX && mouseX < avX + 36 && mouseY >= avY && mouseY < avY + 36) {
    if (!SkinPicker.isBusy()) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        SkinPicker.pickAndUpload(thisScreen);   // opens the file dialog, validates, uploads
    }
    cir.setReturnValue(true);                   // consume the click
    return;
}
```

The click code runs inside an injected mouse-click callback that returns a boolean (`cir`). I did not read the injection annotation line, so check how the target screen handles clicks. Do the avatar check BEFORE the Close button check.

### Upload status line

`SkinPicker.status()` and `statusColor()` give a short text ("Uploading skin...", "Skin uploaded!", or an error) that fades after a few seconds. It is drawn just above the Close button:

```java
String skinStatus = SkinPicker.status();
if (!skinStatus.isEmpty()) {
    context.centeredText(this.font, skinStatus, centerX, closeBtnY - 12, SkinPicker.statusColor());
}
```

### What SkinPicker does (so the target can reuse or replace it)

- Runs on its own daemon thread, never the render thread.
- Opens the OS file dialog with `TinyFileDialogs.tinyfd_openFileDialog("Choose your skin (64x64 PNG)", <user home>, filters{"*.png"}, "PNG images", false)`. tinyfd ships with Minecraft (LWJGL).
- Cancel does nothing. If the dialog throws, or returns empty within 400 ms (usually no zenity/kdialog on Linux), it opens a drag-and-drop screen as fallback.
- Checks the PNG before upload: at most 64 KB, PNG signature, exactly 64x64, 8-bit RGB or RGBA, not interlaced.
- After a successful upload it must tell the skin cache to forget that player (so the new skin is fetched and registered), then show "Skin uploaded!". The upload call itself belongs to the target project's own skin code.

## 7. Rules to keep

1. No network, file dialog or heavy I/O on the render thread. Register textures through `mc.execute`.
2. Any failure returns the vanilla skin. Never let an exception out of a mixin.
3. `getSkin()` runs every frame: lookups in `ConcurrentHashMap` only.
4. New texture id per skin version, release the old one.
5. Names are lowercase.

## 8. Not verified in game (source project)

Hover overlay, file dialog on each OS, drag-and-drop fallback, and the skin mixins in third person, first-person arm, spectator, tab list and slim arms. They compile and the pure-logic unit tests pass, but nobody has confirmed them on screen yet. If something does not render, send `latest.log`.

## 9. Replays

The source project has no replay feature. The hooks above cover anything that renders a player through `AbstractClientPlayer.getSkin()` or `PlayerInfo.getSkin()` under the same player name. A replay mod that creates normal client player entities should probably go through them too (reasoned, untested). Anything that draws a skin by another route (custom GUI heads, name tags, other mods' menus) needs its own call to `fullTexture` / `headTexture`.
