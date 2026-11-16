# Skin System: Objective, Design and Checklist

> READ THIS FIRST after any context transfer. Continue from the first unchecked item.
> Rules: small steps, read code before editing, one change at a time, run tests after each step,
> tick the box here right after an item is done, and log surprises in "Notes / Findings".

## Immediate objective
Players upload their own skin from the profile screen. The server stores it. Everyone sees the
uploaded skin: in the leaderboard, toasts and profile cards (head only), AND on the player model
in matches (both players query each other's skin when a match starts). Our skin must win over
TL Skin Cape or any other skin mod.

## Design decisions (agreed with the user)
- No authentication for now. Skins are cosmetic and unverified. All skin writes go through ONE
  server function so an account system can plug in later.
- Upload: 64x64 PNG only, size cap, validated. The server keeps ONE full skin per player and
  deletes the old one on re-upload.
- At upload the server also cuts the head (8x8 face + 8x8 hat layer) and stores it as a separate
  small image.
- Each player has a skin token that changes on every upload. Stored name: `<player>` + separator
  + `<token>`.
- Clients ask for heads only for the players visible on screen, cache by name+token, and
  re-download only when the token changed.
- Anti-abuse without login: per-IP upload rate limit, and a manual reset for one player.
- In a match, each client fetches the opponent's full skin and forces it on the player model,
  overriding TL Skin Cape or any other skin mod.

## Checklist
### A. Server
- [x] A1. Read how matchmaking_server.py / social_routes.py register routes and serve binary data
- [x] A2. skins.py: PNG validation (signature, 64x64, size cap), token, storage, head extraction
- [x] A3. Server tests for skins.py (valid, bad PNG, wrong size, too big, re-upload replaces, head pixels)
- [x] A4. Routes: upload, full-skin download, head download, token lookup (batch by names)
- [x] A5. Per-IP rate limit and manual reset, with tests
- [x] A6. Route tests; run the full server test suite

### B. Client: data layer
- [x] B1. Read how the client calls the server (HTTP helper, base URL) and how the profile screen is built
- [x] B2. SkinCache (pure logic, tested): name+token keys, stale detection, batch lookup of visible names
- [x] B3. Head and full-skin download, registered as dynamic textures (in-memory only, no disk cache: re-downloaded each launch)

### C. Client: upload UI
- [x] C1. Find out how to open a file picker in 26.1 (check the jar, do not guess)
- [x] C2. Profile screen: "Upload skin" button, client-side validation, upload, success/error message (code complete, compiles, tests pass; NOT run in game yet)

### D. Client: display
- [x] D1. Leaderboard faces from the head cache (replace the initial square) (code complete, compiles, tests pass; NOT run in game yet)
- [x] D2. Toast and profile-card faces from the head cache (code complete, compiles, tests pass; NOT run in game yet)
- [x] D3. Local player's own face uses our skin first (code complete, compiles, tests pass; NOT run in game yet)

### E. Client: in-match skins
- [x] E1. Read how the match start passes opponent names to the client
- [x] E2. Both clients fetch the opponent's full skin when a match starts (code complete, compiles, tests pass; NOT run in game yet)
- [x] E3. Mixin forcing our skin on player models, overriding TL Skin Cape (read TL's hooks first) (code complete, compiles, tests pass; NOT run in game: mixin target resolves only at game load, defaultRequire=1 so a wrong target would crash on launch)
- [x] E4. Fallback to the normal skin if the lookup fails or the player has no uploaded skin (mixin returns early when no downloaded full texture exists; whole body in try/catch)

### F. Release
- [x] F1. Full Java and server test suites pass (Java: ./gradlew test OK; server: 121 tests OK)
- [ ] F2. Build jar, back up the old one, deploy (game closed) -- BLOCKED on a decision, see Notes "F2 findings"
- [ ] F3. Upload the new server files to the live server with a backup and an import check
- [ ] F4. Docs: docs/SKINS.md for the finished system

## Notes / Findings
(append here: API names verified in the 26.1 jar, TL Skin Cape hook details, bugs found)
- A1: server = BaseHTTPRequestHandler in matchmaking_server.py (do_GET/do_POST). JSON replies: send_json_response(status, dict). JSON body: _read_json_body() (returns dict or None). Social routes: self._social(fn, *args) runs under the global `lock`. No binary reply helper exists: add one for skin PNGs. Small modules (layouts.py, kits.py) are pure Python imported by the handler; tests live in matchmaking-server/tests (unittest). Persistence is plain files (stats.json).

- A2: matchmaking-server/skins.py written (stdlib only, no Pillow). SkinStore(root, rate_limit=5, rate_window=3600, clock=time.time): store(name,data)->token is the ONLY write path; get(kind,name) kind='full'|'head'; token_of; tokens_for(names, limit=64); stored_name -> '<name>-<token>'; reset(name); check_rate(ip). Files: <root>/full/<name>.png, <root>/head/<name>.png, <root>/tokens.txt. Names: ^[A-Za-z0-9_]{1,16}$, lowercased. PNG: 8-bit RGB/RGBA, non-interlaced, exactly 64x64, max 64 KB. Smoke-checked only; real tests are A3.

- A3: matchmaking-server/tests/test_skins.py, 23 tests pass (validation, head pixels, store, tokens, persistence, rate limit, reset). Run: python3 -m unittest tests.test_skins

- A4-A6 DONE. Route contract (matchmaking_server.py, tests in tests/test_skin_http.py, 11 pass; full server suite 121 tests):
  - POST /api/skin/upload?name=<player>  body = raw PNG bytes (NOT JSON). 200 {status:'ok', token}; 400 {error}; 429 rate limited (5 per hour per IP).
  - GET /api/skin/full?name=<player> and GET /api/skin/head?name=<player> -> image/png, 404 JSON if no skin. Head = 8x8 RGBA PNG (face + hat).
  - GET /api/skin/tokens?names=a,b,c (max 64) -> {tokens: {lowercased_name: token}}; names without a skin are omitted.
  - POST /api/skin/reset JSON {name} -> localhost only (403 otherwise). 200 {status:'ok', removed:bool}.
  - Server data lives in matchmaking-server/skin_data/ (gitignored). Not uploaded to the live server yet (F3).
  - Fixed on the way: matchmaking_server.py had no module-level `import os`; added.

- B1 DONE. Client facts: fabric-bridge/src/main/java/com/p2ppvp/mod/client/social/SocialApi.java is the async fail-soft HTTP pattern to copy (HttpClient.sendAsync, base URL from P2PPvpMod.getMatchmakerUrl() resolved off-thread and cached 10 s, 3 s connect / 4 s request timeout, never throws, errors -> ApiResult.failure). It is string-only, so write a separate client/skin/SkinApi.java with byte[] bodies/replies. Local player name: minecraft.getUser().getName(). Profile card is drawn in mixin/TitleScreenMixin.java (card ~lines 831-880, detailed overlay from ~1046, click handling near ~1442); its face comes from getPlayerSkin() (~line 1228) via getSkinManager().createLookup(), which TL Skin Cape can alter. LeaderboardScreen.selfSkin() (~line 256) does the same. Unit tests for pure client logic live in fabric-bridge/src/test/java/com/p2ppvp/mod/client/ui/ (JUnit, run with ./gradlew test).

- B2 DONE. fabric-bridge/src/main/java/com/p2ppvp/mod/client/skin/SkinCache.java (pure, no MC imports; SkinCacheTest passes). SkinCache(missTtlMillis): key(name), cacheKey(name,token), namesToQuery(visible, now) -> up to MAX_BATCH=64 valid names not recently missing, staleAfterLookup(queried, serverTokens, now) -> names needing download (marks absent names as missing for the TTL), markCached(name, token), tokenOf, hasSkin, invalidate(name). Next: B3 needs SkinApi (byte[] HTTP) and texture registration; check the 26.1 jar with javap (NativeImage, DynamicTexture, TextureManager) before writing.

- B3 DONE. client/skin/SkinApi.java (async, fail-soft: tokens(names), download(kind,name)->byte[]|null, upload(name,png)->UploadResult(ok,token,error); parsers tested in SkinApiTest). client/skin/SkinTextures.java (static): headTexture(name)->Identifier|null, fullTexture(name), requestHeads(visibleNames) throttled to 1 per 2 s, requestFull(name), invalidate(name). Textures: p2ppvp:skins/<head|full>/<name>-<token>, registered on the render thread via mc.execute, old one released on token change. Verified in 26.1 jar: NativeImage.read(byte[]), new DynamicTexture(Supplier<String>, NativeImage), TextureManager.register/release, Identifier.fromNamespaceAndPath. Compiles; SkinTextures itself has no unit test (needs the game). Next: C1 (file picker).

- C1 DONE. File picker options verified in the 26.1 jar / game libraries: (1) Screen.onFilesDrop(List<Path>) is vanilla, so dragging a PNG onto the window always works. (2) lwjgl-tinyfd is bundled in ~/.minecraft/libraries/org/lwjgl: org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(CharSequence title, CharSequence defaultPath, PointerBuffer filterPatterns, CharSequence filterDescription, boolean allowMultiple) -> String path or null; build the PointerBuffer with MemoryStack.stackPush() + mallocPointer(1) + UTF8("*.png"). Not tested on the desktop yet. Plan: client/SkinUploadScreen with a Choose file button (tinyfd, wrapped in try/catch, run off the render thread) AND onFilesDrop; validate PNG signature, 64x64 and <=64 KB client-side before upload; show server error text.

- C2 IN PROGRESS. Step 1 done: client/skin/SkinFileCheck.java (pure: PNG signature, IHDR 64x64, 8-bit RGB/RGBA, non-interlaced, max 64 KB; check(byte[]) -> null or message) + SkinFileCheckTest (passes). Verified: Screen.onFilesDrop(List<Path>) is public; tinyfd_openFileDialog(CharSequence, CharSequence, PointerBuffer, CharSequence, boolean) exists and lwjgl-tinyfd 3.4.1 is already on the compile classpath (no build change). Gradle wrapper is fabric-bridge/gradlew (run from fabric-bridge/, use --offline). Screen conventions in 26.1: extractRenderState(GuiGraphicsExtractor,int,int,float), mouseClicked(MouseButtonEvent, boolean), layout helpers in client/ui/McrLayout (see PlayersScreen). Profile overlay in TitleScreenMixin: card 340x220 (ovX/ovY), Close button 100x18 at centerX-50, ovY+ovHeight-25 (render ~line 1215, click ~line 1412); plan: add an "Upload skin" button left of Close and open SkinUploadScreen. Remaining C2 steps: (2) client/SkinUploadScreen (Choose file via tinyfd off the render thread + onFilesDrop, validate with SkinFileCheck, upload via SkinApi, call SkinTextures.invalidate(name), show success/error), (3) button in TitleScreenMixin overlay + click handling.

- C2 DONE. client/SkinUploadScreen.java: card with Choose file (tinyfd on a daemon thread, try/catch Throwable falls back to a "drag a PNG" message) + Back; onFilesDrop(List<Path>) takes the first path; both go through handle(): size check, read, SkinFileCheck.check, SkinApi.upload, on success SkinTextures.invalidate(name) and a green status; `busy` flag cleared on every path. TitleScreenMixin: "Upload skin" button drawn left of Close (x = centerX-156, 100x18, gold border) and its click check placed BEFORE the Close and click-outside checks; it opens SkinUploadScreen with the title screen as parent. Compile and full ./gradlew test pass. Next: D1 (leaderboard faces from the head cache: read LeaderboardScreen.drawFace ~line 269 and selfSkin ~line 256 first; call SkinTextures.requestHeads(visible names) while rendering).

- D1 DONE. LeaderboardScreen.drawFace: SkinTextures.headTexture(name) first (single 8x8 blit scaled to 10x10, since the server head already holds face + hat), then the old selfSkin() lookup, then the colored initial square. extractRenderState collects the current page's names plus the local player (pinned row) and calls SkinTextures.requestHeads(visibleNames) each frame (internally throttled to 1 per 2 s). Compile and full ./gradlew test pass. This also covers the local player's face on the leaderboard (part of D3). Still open for D3: TitleScreenMixin getPlayerSkin() (profile card ~line 854 and overlay avatar ~line 1175) still uses the vanilla skin lookup. Next: D2 (toast and profile-card faces): read ui/ChallengeToast.java and the profile card render in TitleScreenMixin (~lines 854-866) first.

- D2/D3 DONE. TitleScreenMixin: profile card (32x32) and detailed overlay avatar (36x36) draw SkinTextures.headTexture(ownName) first (single 8x8 blit), then the vanilla getPlayerSkin() lookup, then the gray placeholder; the card render calls SkinTextures.requestHeads(List.of(ownName)) each frame (throttled). ui/ChallengeToast: only CHALLENGE toasts carry a player name in `title` (ToastFeeder.syncIncoming passes c.sender()); result toasts (accepted/declined/expired) have a headline title and the name in line1, so they keep the colored initial tile. draw() collects challenge-toast senders and calls requestHeads; the avatar tile draws the head (30x30 inside the 32x32 frame) when available. Compile and full ./gradlew test pass. Possible later polish: show heads on result toasts (needs the target name passed separately in ToastQueue.push). Next: E1 (how match start passes opponent names to the client): search P2PPvpModClient and MatchCoordinator for opponent/match_found handling and read it before E2.

- E1/E2 DONE. Match start: TitleScreenMixin.handleMatchedResponse(String body) (~line 437) is the single hook. Callers: the /api/queue/join response (~line 373) and the /api/queue/status poll loop (~line 547). Challenge accepts set P2PPvpModClient.pendingChallengeMatch, which onRender (~line 765) turns into startQueuePolling(), so challenge matches reach the same handler. Payload fields: role (host/guest), host_ip, opponent_id (the opponent's player name), kit, custom_kit, opponent_layouts. It stores opponent_id in P2PPvpMod.authorizedOpponentName. E2: right after that line it calls SkinTextures.requestFull(opponentId) (skipped when empty or containing "Mock") and requestFull(own name), async and fail-soft. Both clients run this handler, so both fetch both skins. Compile and full ./gradlew test pass. Spectators join via pendingSpectateHostIp and do NOT go through this handler (they get no skins yet; possible later polish). Next: E3 (mixin that forces our skin on player models, overriding TL Skin Cape): find TL Skin Cape's jar in ~/.minecraft/mods, javap its mixins to see what it hooks, and find the 26.1 skin lookup (PlayerInfo.getSkin / PlayerSkin) with javap before writing anything.

- E3/E4 DONE (code only). TL Skin Cape findings (read from ~/.minecraft/libraries/org/tlauncher/tl_skin_cape_26.2/1.39 jar, NOT necessarily the build the game loads): it is NOT a Mixin/Fabric mod. It is a LaunchWrapper Tweaker (org.tlauncher.tweaker.Tweaker) with its own ASM transformer (org.tlauncher.injection.InjectionClassTransformer / Injections). Its targets include PlayerInfo.getSkin (it inserts a call to org/tlauncher/TLSkinCape.getPlayerSkin(this.getProfile()) at the head of the method), AbstractClientPlayer, PlayerModel, preRenderCape. So hooking PlayerInfo.getSkin with a Mixin would fight TL with unknown transform order. Chosen approach: mixin/AvatarRendererSkinMixin injects at TAIL of AvatarRenderer.extractRenderState(Avatar, AvatarRenderState, float)V (descriptor verified with javap) and replaces AvatarRenderState.skin (public field) with a new PlayerSkin whose body is ClientAsset.ResourceTexture(SkinTextures.fullTexture(name)); cape, elytra, model type and secure flag are kept. Applies only to the local player and P2PPvpMod.authorizedOpponentName, matched by entity.getName().getString(). Returns early when no downloaded texture exists (fallback = normal skin); body is in try/catch(Throwable). Registered in mcr.mixins.json (client list). Compile and full ./gradlew test pass. RISKS to verify in game: (1) the mixin target only resolves at game load and defaultRequire=1, so a wrong target crashes on launch; check the log first. (2) The model type (slim vs classic) is NOT changed: a 64x64 skin with slim arms will render with the arm model of the original skin. Possible later polish: send/store the arm model with the upload. (3) First-person arm (ItemInHandRenderer) was not checked and may still show the vanilla skin. (4) Whether TL's other patches (AbstractClientPlayer, PlayerModel) interfere with the model is unknown. (5) Spectators do not get skins (they bypass handleMatchedResponse).

- F2 findings (nothing deployed yet). `./gradlew build --offline` from fabric-bridge/ succeeds and writes build/libs/mcr-26.1.2-beta.1.53.jar (47 MB), and its copyJarToRootMods task also copies build/libs/*.jar into <repo>/mods (this refreshed the old mcr-*.jar files there). The installed jar is ~/.minecraft/mods/MCR-26.1.2-beta.1.53-linux.jar (21 MB, Oct 3), a different artifact; .git_sync_cache/build_deploy.sh produces MCR-<version>-linux.jar via a cloud build (Go daemon natives). Copying the local 47 MB jar next to it risks a duplicate mod id (same version 1.53), and replacing it may drop the natives. The version in gradle.properties is also still 1.53, the same as the installed jar. Needs a user decision on how to deploy (run build_deploy.sh? bump the version first? which jar to install?) before F2. F3 (live server upload) also needs explicit user approval.

- REVISION after E3 (user asked for skins in ALL states: first/third person, spectator, etc.). AvatarRendererSkinMixin was DELETED. New design: client/skin/SkinOverride.apply(vanillaSkin, name) swaps the body texture for any player with an uploaded skin (not only self/opponent), sets model SLIM/WIDE from SkinTextures.isSlim(name), keeps cape/elytra/secure, returns vanilla on any problem. It is called from two RETURN injections: mixin/AbstractClientPlayerSkinMixin (AbstractClientPlayer.getSkin(): world model, slim/wide renderer choice in EntityRenderDispatcher, first-person arm renderPlayerArm, map hand renderMapHand, spectated players) and mixin/PlayerInfoSkinMixin (PlayerInfo.getSkin(): tab list, spectator menu). Both registered in mcr.mixins.json (client). LocalPlayer does not override getSkin (verified with javap). RETURN injection runs after TL Skin Cape's head patch of PlayerInfo.getSkin. SkinTextures gained requestFullLazy(name) (called by SkinOverride; once per name, retried every 30 s while no skin is known), isSlim(name), and a SLIMS map filled when a full skin downloads. client/skin/SkinModel.isSlim(pixels,w,h) detects slim arms (right-arm columns x=54..55, y=20..31 fully transparent) with SkinModelTest. Match-start requestFull calls remain as a prefetch. Compile and full ./gradlew test pass. Everything is still UNVERIFIED in game.

- REFRESH FIX. SkinTextures.requestFullLazy now re-checks known skins every 60 s (30 s while unknown); requestFull only downloads when the server token differs from FULL_TOKENS (filled in register). invalidate(name) also clears the lazy timer, so a fresh upload shows quickly. Missing refmap in the jar is normal (installed jar has none either). Both getSkin() mixin targets verified with javap (descriptor ()Lnet/minecraft/world/entity/player/PlayerSkin;). Compile and full ./gradlew test pass. Known privacy note: every rendered player's name is sent to the matchmaker for token lookup.

## Not verified in game (update as things get tested)
- Nothing in this plan has been run in game yet. Specifically untested: tinyfd dialog on the desktop, drag-and-drop onto the window, the Upload skin button position/click in the profile overlay, AbstractClientPlayerSkinMixin and PlayerInfoSkinMixin (launch, third/first person, spectator, tab list, slim arms, with and without TL Skin Cape), head loading on the leaderboard/toasts/profile card.
- Next: F1 (run the full Java suite AND the server suite: cd matchmaking-server && python3 -m unittest discover -s tests), then F2 (build jar, back up the old one, deploy with the game closed), F3, F4.
