# MCR (Minecraft Ranks): Master UI Visual Overhaul & Handover Blueprint

> **CRITICAL DIRECTIVE FOR THE NEXT AI DEVELOPMENT MODEL:**
> * Read this document entirely first. It establishes the exact architecture of the project and outlines the step-by-step master visual transformation plan.
> * A complete, fully stable working backup of the pre-transformation project is securely preserved at `/mnt/data_vault/Documents/BigFish-cli/projects/P2P-PvP-Framework_backup/`. **Do NOT touch or delete the backup.**
> * All development must take place within this live directory `/mnt/data_vault/Documents/BigFish-cli/projects/P2P-PvP-Framework/`.
> * Remember the **JavaScript Bracket Bug**: Under no circumstances should you output empty brackets `[ ]` without a space in between them inside code blocks or text files. Always use `[ ]` (with a space) or `new Array()`.

---

## 1. Executive Summary & Current State
The P2P-PvP-Framework is fully functional, highly stable, and built on a sophisticated decentralization architecture. 

During our last session, we accomplished the following key milestones:
1. **Matchmaking Server Syntax Fix**: Corrected all Python syntax and indentation issues inside `/matchmaking-server/matchmaking_server.py` (specifically surrounding the `/api/queue/join` and `/api/leaderboard` path handlers).
2. **Backend Remote Deployment**: Successfully deployed the corrected python code to the production matchmaking server at `100.120.244.95` and restarted the `p2p-matchmaking.service` systemd service. It is active, healthy, and listening on port `8000`.
3. **De-cluttered Detailed Profile Card**: Overhauled the horizontal "crammed tabs" design in `TitleScreenMixin.java` with a highly modern vertical sidebar category layout on the left.
4. **Click Bounds Alignment**: Updated `onMouseClicked` in `TitleScreenMixin.java` to map coordinate clicks perfectly to the new vertical tab alignment (`ovWidth = 340`).
5. **Theme Audio Integration**: Successfully imported and converted the MP3 track `Bracketed Glory.mp3` into an optimized `.ogg` file format, stored inside the assets directory at:
   `fabric-bridge/src/main/resources/assets/p2ppvp/sounds/music/bracketed_glory.ogg`
6. **Multiplatform Release Compilation**: Ran the `./build_multiplatform.sh` script, producing completely clean, warning-free Fabric mod builds (release version `26.1.2-beta.1.32`) for Windows, Linux, and macOS.

---

## 2. Core Project Architecture Overview
Before modifying any files, read and understand how the underlying modules communicate:

```
                  [ Minecraft Client (Java Fabric Mod) ]
                                   │
                      (Local TCP Port 5005 IPC)
                                   │
                                   ▼
                     [ core-daemon (Native Go) ]
                       │                     │
      (User-Space WireGuard P2P)       (HTTP Loopback Ghost Proxy)
                       │                     │
                       ▼                     ▼
               [ Opponent Client ]  [ Remote Signaling Server ]
                                       (100.120.244.95:8000)
```

1. **Process Isolation & JNA Daemon**:
   The Java Fabric mod (`fabric-bridge`) spawns the compiled native Go binary `core-daemon` as an in-memory child process. This daemon maps Tailscale's user-space mesh network stack. High-speed, sub-millisecond IPC is conducted over local TCP loopback on port `5005`.
2. **Local loopback Ghost Proxies**:
   To bypass routing blocks when Tailscale is disabled on the host OS, the mod makes HTTP queries to `127.0.0.1:8000`. The local `core-daemon` intercepting proxy securely forwards those requests over user-space Tailscale directly to the production remote matchmaking signaling server (`100.120.244.95:8000`).
3. **Local Cache Serialization**:
   Local player stats and the recent 50 match histories are persistent inside a JSON file named `p2p_player_cache.json`. When a match resolves, `/api/match/report` returns updated stats which are instantly written to this local cache inside `P2PPvpModClient.java`.

---

## 3. Master Visual & Audio Overhaul Plan
The target of this transformation is to elevate the UI graphics to look incredibly fluid, animated, and professional (competing directly with high-end premium client systems). This is divided into 5 logical Parts, to be executed sequentially.

### Part 1: Ambient Menu Particle System (Eldenscape)
Add a lightweight, beautiful 2D floating ember particle system to the background of the main menu and cards.

*   **Implementation Target**: `TitleScreenMixin.java` inside `onRender`.
*   **Aesthetic**: 25 to 40 tiny, soft, semi-transparent golden/orange dots floating upwards, swaying in wave motions, and slowly fading out.
*   **The Blueprint**:
    1. Define a private static nested class `GuiParticle` inside `TitleScreenMixin.java`:
       ```java
       private static class GuiParticle {
           public float x, y;
           public float speedY;
           public float swaySpeed;
           public float swayWidth;
           public float scale;
           public float maxLife;
           public float life;
           
           public GuiParticle(float x, float y) {
               this.x = x;
               this.y = y;
               this.speedY = 0.2f + (float)Math.random() * 0.4f;
               this.swaySpeed = 0.01f + (float)Math.random() * 0.02f;
               this.swayWidth = 1.0f + (float)Math.random() * 3.0f;
               this.scale = 1.0f + (float)Math.random() * 1.5f;
               this.maxLife = 100.0f + (float)Math.random() * 100.0f;
               this.life = 0.0f;
           }
       }
       ```
    2. Maintain a `private final java.util.List<GuiParticle> ambientParticles = new java.util.ArrayList<>();` in `TitleScreenMixin.java`.
    3. Initialize particles once in `init()` or populate them dynamically during `onRender` (ensuring we cap them at 35 particles max).
    4. In `onRender`, iterate over the particles, update their positions (`y -= speedY`, add sinusoidal horizontal sway using `Math.sin`), render them as small colored fills with alphas proportional to their remaining life, and recreate them at the bottom of the screen (`y = height`) when they die.

---

### Part 2: Kinetic UI Transitions & Glassmorphic Gradient Card
Overhaul the Personal Detailed Profile Overlay so it opens with a gorgeous, fluid scale, slide, and fade-in transition, using semi-transparent gradient backings.

*   **Implementation Target**: `TitleScreenMixin.java` (`onRender` & `onMouseClicked`).
*   **Aesthetic**: Translucent charcoal glass backing, neon golden outline, slide-up easing, and scaling transition.
*   **The Blueprint**:
    1. Declare a private float field inside `TitleScreenMixin.java`:
       `private float detailedProfileTransition = 0.0f;`
    2. In `onRender` or a dedicated tick method, update the float variable:
       * If `showDetailedProfile` is true, increment `detailedProfileTransition` by `0.08f` (or frame-rate independent `delta * 4.0f`) up to `1.0f`.
       * If `showDetailedProfile` is false, decrement it down to `0.0f`.
    3. Modify `onRender`'s check: instead of checking `if (this.showDetailedProfile)`, render the overlay whenever `this.detailedProfileTransition > 0.0f`.
    4. Calculate transition transformations:
       * **Alpha Interpolation**: Multiply overlay fills and font alphas by `detailedProfileTransition` (mapping transparency dynamically).
       * **Vertical Slide-up Offset**: Apply a vertical offset `int slideY = (int)((1.0f - detailedProfileTransition) * 20.0f);`. Add `slideY` to `oTop` and `oBottom` during rendering.
       * **Scale Ease-Out**: Scale the rendering matrix slightly around the center using standard `PoseStack` matrix manipulation (expanding from `95%` to `100%`).
    5. **Glassmorphism Fills**: Replace standard solid `fill(..., 0xCC0B0B0F)` with a semi-transparent gradient fill (using vertical linear interpolation) blending from an upper translucent dark purple/charcoal to a lower deep black-blue (e.g., blending from `0xAA13101C` to `0xD8050508`).

---

### Part 3: 3D Living Player Skin Model Renderer
Fully replace the static 2D head avatar in the detailed profile stats card with a miniature, fully rendered, rotating 3D player character entity.

*   **Implementation Target**: `TitleScreenMixin.java` inside the stats panel rendering block.
*   **Aesthetic**: A small 3D model of the player's active character skin that slowly auto-rotates and follows the mouse cursor with its head/eyes.
*   **The Blueprint**:
    1. Import and utilize Minecraft's native entity rendering method:
       `net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventoryFollowsMouse(...)`
       *(Note: In Minecraft 1.20+ or 26.1.2 environments, check the exact mapped method name. It is typically a static method on `InventoryScreen` or `InventoryScreen.drawEntity(...)` taking GuiGraphics, coordinates, size, look vectors, and the LivingEntity).*
    2. If the active player entity `this.minecraft.player` is loaded and not null, invoke this method inside the profile details panel (e.g., at `oLeft + 130`, `oTop + 75` with a size/scale of `30` or `35`).
    3. Feed the mouse coordinates (`mouseX`, `mouseY`) into the look vector parameters so the 3D skin model's head smoothly tracks the player's cursor.
    4. Apply a slow auto-rotation offset based on `System.currentTimeMillis() % 36000` to create an automatic, gentle rotating turntable presentation.

---

### Part 4: Custom Audio Soundscape & Music Settings
Register and loop the newly uploaded `bracketed_glory.ogg` track on the main menu, and build clean interactive audio controls.

*   **Implementation Target**: `fabric-bridge/src/main/resources/assets/p2ppvp/sounds.json`, `TitleScreenMixin.java`, `MatchmakingOptionsScreen.java`, and `P2PPvpModClient.java`.
*   **The Blueprint**:
    1. Create or verify `assets/p2ppvp/sounds.json` at:
       `fabric-bridge/src/main/resources/assets/p2ppvp/sounds.json`
    2. Register the custom theme song as a sound event:
       ```json
       {
         "music.bracketed_glory": {
           "category": "music",
           "sounds": [
             {
               "name": "p2ppvp:music/bracketed_glory",
               "stream": true
             }
           ]
         }
       }
       ```
    3. In `P2PPvpModClient.java`, maintain a static sound reference or volume control settings (persisting a `p2p_music_volume` float inside `p2p_player_cache.json` or standard settings).
    4. Implement an active background loop in `TitleScreenMixin.java`. On initialization, if background music is enabled and not already playing, trigger the loop:
       ```java
       // Playing custom sound event using SoundManager
       this.minecraft.getSoundManager().play(
           net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
               new Identifier("p2ppvp", "music.bracketed_glory"), 1.0f, volume
           )
       );
       ```
    5. **Settings Integration**: Add a sliding volume bar (or cycling buttons: *Off | 25% | 50% | 100%*) inside `MatchmakingOptionsScreen.java` to dynamically adjust the volume parameter and save it to the cache disk file instantly.
    6. **Slick SFX**: Intercept button clicks to play high-quality mechanical triggers instead of default wooden click events.

---

### Part 5: Interactive Tab Hover Scaling & Glowing Transitions
Animate the vertical sidebar tabs so they glow and expand smoothly upon cursor drift, and trigger a visual "pulse scale" feedback on click.

*   **Implementation Target**: `TitleScreenMixin.java` (`onRender` & `onMouseClicked`).
*   **The Blueprint**:
    1. Inside `TitleScreenMixin.java`, declare an array or map to track hover animations:
       `private final float[ ] tabHoverProgress = new float[7];`
    2. In `onRender`, check if `mouseX` and `mouseY` are inside the boundaries of each vertical tab index `i`.
       * If hovered: increase `tabHoverProgress[i]` by `0.15f` up to `1.0f`.
       * If not hovered: decrease `tabHoverProgress[i]` by `0.15f` down to `0.0f`.
    3. When drawing the tab's border and background:
       * Blend the border color dynamically from dark gray (`0xFF444446`) to a glowing neon gold (`0xFFD4AF37`) using `tabHoverProgress[i]` as the interpolation alpha.
       * Render a subtle gold-colored outer horizontal highlight line that expands outward from the center based on `tabHoverProgress[i]`.
    4. When a tab is clicked, trigger a brief pulse animation by multiplying the tab size by `1.08f` and decaying it rapidly back to `1.00f` over 5 frames.

---

## 4. Immediate Action Checklist for the Next Model
When you receive this project, execute these actions step-by-step:

1. **Step 1: Read the Live Code**
   Read `fabric-bridge/src/main/java/com/p2ppvp/mod/mixin/TitleScreenMixin.java` starting around line 915 to see how `showDetailedProfile` is currently rendered, and line 1250 to check how mouse clicks are registered.
2. **Step 2: Implement Part 1 (Particles)**
   Edit `TitleScreenMixin.java` to declare `GuiParticle` and implement the 2D floating ember particles in `onRender`. Compile using `./build_multiplatform.sh` to verify syntax.
3. **Step 3: Implement Part 2 (Transitions & Glassmorphism)**
   Integrate the transition timer `detailedProfileTransition` float and ease-out curves to smoothly slide and fade-in the overlay. Apply the gradient glass backing.
4. **Step 4: Implement Part 3 (3D Player Model)**
   Hook into Minecraft's native `InventoryScreen` entity render pipeline to display the living 3D player skin rotating in the stats card.
5. **Step 5: Implement Part 4 (Music & Audio Settings)**
   Register the `bracketed_glory.ogg` track inside `sounds.json`, implement background play loop inside Java, and add volume cycling options in `MatchmakingOptionsScreen.java`.
6. **Step 6: Build & Test**
   Run the `./build_multiplatform.sh` compiler script to make sure all changes package cleanly into optimized client files.

*The codebase is perfectly isolated and stable. Proceed with high confidence and make the visual design stunning!*
