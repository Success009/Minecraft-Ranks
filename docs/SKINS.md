# Custom Skins

Players upload their own 64x64 skin from the profile screen. The matchmaker stores it, and every
MCR client shows it: head only in the leaderboard, toasts and profile cards, and the full skin on
the player model (third person, first-person arm, map hand, spectated players, tab list,
spectator menu).

## For players

1. Open the main menu and click your profile card to open the detailed profile (any tab except History).
2. Hover your head. It dims and a pencil icon appears.
3. Click it. The operating system file dialog opens.
4. Pick a PNG. It is checked and uploaded right away. Cancelling does nothing.
5. The status line above the Close button shows progress, then "Skin uploaded!" or the reason it failed.

A re-uploaded skin replaces the old one. Other players see the change within about 60 seconds.

### Skin file requirements

- PNG, exactly 64x64 pixels, 8-bit RGB or RGBA, not interlaced.
- At most 64 KB.
- Slim (Alex) arms are detected automatically from the PNG: if the right-arm columns x=54..55,
  y=20..31 are fully transparent, the slim model is used.

### Uploading on each operating system

The file dialog uses the LWJGL tinyfd library that ships with Minecraft:

- Windows and macOS: the native dialog, nothing to install.
- Linux: needs `zenity` or `kdialog` installed. If the dialog cannot open, the game falls back to a
  drag-and-drop screen: drag a PNG onto the Minecraft window.

### Limits

- 5 uploads per hour per IP address.
- There is no login. Skins are cosmetic and unverified.

## For server operators

Skin code lives in `matchmaking-server/skins.py` (Python standard library only, no Pillow).
`matchmaking_server.py` imports it, so BOTH files must be deployed together.

Data is stored in `skin_data/` next to the server script, created on first use:

```
skin_data/full/<player>.png   full 64x64 skin
skin_data/head/<player>.png   8x8 head (face + hat layer), cut at upload time
skin_data/tokens.txt          current token per player
```

Every upload gets a new token. Clients cache heads by name + token and only download again when the
token changes. Names must match `^[A-Za-z0-9_]{1,16}$` and are stored lowercase.

### HTTP routes

| Route | Purpose |
|---|---|
| `POST /api/skin/upload?name=<player>` | Body is the raw PNG bytes (not JSON). 200 `{status, token}`, 400 `{error}`, 429 when rate limited. |
| `GET /api/skin/full?name=<player>` | The full skin as `image/png`. 404 `{error: "No skin"}` if none. |
| `GET /api/skin/head?name=<player>` | The 8x8 head as `image/png`. 404 if none. |
| `GET /api/skin/tokens?names=a,b,c` | Up to 64 names. Returns `{tokens: {name: token}}`. Players without a skin are omitted. |
| `POST /api/skin/reset` | JSON `{name}`. Localhost only (403 otherwise). Removes that player's skin. |

All skin writes go through `SkinStore.store()`, so an account system can be added there later.

### Deploying

`deploy_server.sh` copies only `matchmaking_server.py`. For skins it must also copy `skins.py`.
Back up the old `matchmaking_server.py` first, and do not overwrite the live `stats.json` or
`skin_data/`. Check the files on the server before restarting:

```
cd /opt/p2p_matchmaking && python3 -m py_compile matchmaking_server.py skins.py && python3 -c "import skins"
```

### Removing a skin

Run this on the server itself (the route only accepts localhost):

```
curl -X POST http://localhost:8000/api/skin/reset -d '{"name":"PlayerName"}'
```

## For developers

### Client files (`fabric-bridge/src/main/java/com/p2ppvp/mod/`)

| File | Role |
|---|---|
| `client/skin/SkinFileCheck.java` | Pure PNG validation (signature, size, 64x64, format). |
| `client/skin/SkinApi.java` | Async, fail-soft HTTP client for the routes above. |
| `client/skin/SkinCache.java` | Pure logic: name + token keys, stale detection, batch lookups. |
| `client/skin/SkinTextures.java` | Downloads and registers head and full textures. Re-checks known skins every 60 s. |
| `client/skin/SkinModel.java` | Slim-arm detection from pixels. |
| `client/skin/SkinOverride.java` | Swaps a vanilla `PlayerSkin` for the uploaded one. Returns vanilla on any problem. |
| `client/skin/SkinPicker.java` | Click-the-avatar flow: OS file dialog, validation, upload, status line. |
| `client/SkinUploadScreen.java` | Drag-and-drop fallback screen. |
| `mixin/AbstractClientPlayerSkinMixin.java` | `AbstractClientPlayer.getSkin()` RETURN hook: world model, first-person arm, map hand, spectated players. |
| `mixin/PlayerInfoSkinMixin.java` | `PlayerInfo.getSkin()` RETURN hook: tab list, spectator menu. |
| `mixin/TitleScreenMixin.java` | Profile overlay: hover overlay, click handling, upload status line. |

The pencil icon is `assets/p2ppvp/textures/gui/edit_icon.png` (64x64, white on transparent).

### Rules the code follows

- No network or file dialog work on the render thread. Textures are registered through `mc.execute`.
- Any failure falls back to the normal skin. Mixin bodies are wrapped in try/catch.
- Match start prefetches both players' full skins (`TitleScreenMixin.handleMatchedResponse`). Any
  other player is looked up lazily the first time their skin is requested.
- Textures are in memory only and are downloaded again on each launch.

### Tests

```
cd fabric-bridge && ./gradlew test --offline
cd matchmaking-server && python3 -m unittest discover -s tests
python3 test_all.py
```

Server tests: `tests/test_skins.py` (store, validation, tokens, rate limit) and
`tests/test_skin_http.py` (routes). Client tests: `SkinFileCheckTest`, `SkinCacheTest`,
`SkinApiTest`, `SkinModelTest`.

## Known limitations

- Not yet verified in game: the hover overlay, the file dialog on each OS, drag-and-drop, and the
  skin mixins (third person, first-person arm, spectator, tab list, slim arms).
- Every rendered player's name is sent to the matchmaker for a token lookup, including players on
  other servers.
- There is no account check, so anyone can upload a skin under any valid name.
- The mixins resolve only when the game loads. `mcr.mixins.json` uses `defaultRequire: 1`, so a
  target that does not exist crashes the game on launch. Check the log first after a Minecraft update.
- Interaction with TL Skin Cape was read from its 26.2 build and is not confirmed for the build the
  game loads. Our mixins run at RETURN, after TL's head patch.
