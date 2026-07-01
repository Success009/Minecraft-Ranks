# MCR Matchmaking Client Visual & Audio Overhaul Guide

> **CRITICAL ARCHITECTURAL DIRECTIVE FOR ALL INCOMING AI MODELS (MANDATORY):**
> * Before proposing, editing, or writing any code, you MUST completely read this entire instructions file AND **`DEVELOPMENT_PLAN.md`** (the master roadmap).
> * The project is in an active, rapid visual transformation stage. You must analyze where the current codebase stands compared to the roadmap, understand its end-to-end user-space Tunnel proxying design, JNA process lifecycles, and then proceed with high-fidelity visual and audio modifications.
> * ALWAYS run the multiplatform compiler `./build_multiplatform.sh` to package, test, and release all targets cleanly.
> * Remember the **JavaScript Bracket Bug**: NEVER output empty square brackets `[ ]` without a space in between them inside code blocks or text files. Always write them as `[ ]` (with a space) or `new Array()`.

This directory acts as the central reference point for continuing the comprehensive visual and audio transformation of the Minecraft Ranks (MCR) P2P PvP Framework.

---

## 1. Directory & File Reference Index
Use these paths to locate and modify the respective components for the visual transformation:

*   **Main Menu & Profile GUI Rendering**: 
    `fabric-bridge/src/main/java/com/p2ppvp/mod/mixin/TitleScreenMixin.java`
*   **Matchmaking Options & Music Configuration UI**: 
    `fabric-bridge/src/main/java/com/p2ppvp/mod/client/MatchmakingOptionsScreen.java`
*   **Custom Buttons & Visual Interactive Highlights**: 
    `fabric-bridge/src/main/java/com/p2ppvp/mod/client/McrButton.java`
*   **Sound Event registrations Config**: 
    `fabric-bridge/src/main/resources/assets/p2ppvp/sounds.json` (Create if missing)
*   **Theme Music Audio Track (OGG format)**: 
    `fabric-bridge/src/main/resources/assets/p2ppvp/sounds/music/bracketed_glory.ogg`

---

## 2. Reading Order for Context Acquisition
To fully understand the current architecture before making changes, read these documents in order:
1. **`DEVELOPMENT_PLAN.md`**: Master visual transformation plan containing coordinate mappings, variable configurations, and specific math equations.
2. **`docs/DEVELOPER_GUIDE.md`**: Core guide detailing Go child daemon lifecycle, JNA loopback IPC, and local profile caching.
3. **`ARCHITECTURE.md`**: Component modularity mappings.

---

## 3. Step-by-Step Implementation Tasks

### Part 1: Ambient Menu Particle System (Eldenscape)
*   **Target**: `TitleScreenMixin.java` inside `onRender`.
*   **Task**: Implement a 2D floating golden particle/ember system. Draw up to 35 tiny, semi-transparent orange/gold dots floating upwards with sinusoidal horizontal wave motions (sway), fading out as they approach the top.
*   **Details**: Refer to `DEVELOPMENT_PLAN.md` Section 3 Part 1 for the custom `GuiParticle` nested class blueprint.

### Part 2: Kinetic UI Transitions & Glassmorphism Backing
*   **Target**: `TitleScreenMixin.java` (`onRender` & `onMouseClicked`).
*   **Task**: Create an interactive float transition progress tracker (`detailedProfileTransition`) ranging from `0.0f` to `1.0f` that tracks when the profile card is opening or closing.
*   **Effects**:
    *   **Alpha Fading**: Multiply overlays and font colors by the transition percentage.
    *   **Slide-up Easing**: Animate the card sliding up from a 20-pixel vertical offset into center screen.
    *   **Matrix Scaling**: Apply ease-out scaling on the rendering matrix from `95%` to `100%` scale.
    *   **Glassmorphism**: Replace solid colors with a vertical gradient blending from translucent violet (`0xAA13101C`) to deep translucent black-blue (`0xD8050508`).

### Part 3: Living 3D Player Character Model
*   **Target**: `TitleScreenMixin.java` inside the detailed profile panel.
*   **Task**: Integrate Minecraft's native entity rendering method (`InventoryScreen.renderEntityInInventoryFollowsMouse(...)` or standard mapped draw equivalent) to draw a rotating 3D player character entity inside the card details.
*   **Interaction**: Configure the entity renderer so the model's head/eyes track the cursor position, and apply an automatic slow turntable rotation.

### Part 4: Theme Soundscapes & Audio Options
*   **Target**: `sounds.json`, `TitleScreenMixin.java`, `MatchmakingOptionsScreen.java`.
*   **Task**: Register the newly imported `bracketed_glory.ogg` as a streamable background track event under `p2ppvp:music.bracketed_glory`.
*   **Controls**: Add an interactive sound settings interface (e.g. volume sliding bar or cycling buttons) to `MatchmakingOptionsScreen.java` to dynamically adjust theme music volume and save settings to local cache disk.
*   **SFX**: Replace standard wooden click sounds with distinct mechanical click sounds when buttons are pressed or vertical tabs are switched.

### Part 5: Interactive Tab Hover Glowing Fades
*   **Target**: `TitleScreenMixin.java` (`onRender` & `onMouseClicked`).
*   **Task**: Track cursor hover progress (`0.0f` to `1.0f`) for individual vertical sidebar tabs. Smoothly fade tab borders from dark gray to glowing neon gold on hover, and execute a quick pulse scaling effect when a tab is clicked.

---

## 4. Build & Release Verification
Compile and build the modified client files for all target platforms by executing:
```bash
./build_multiplatform.sh
```
Verify that the output displays:
`=== MULTI-PLATFORM BUILD & RELEASE COMPLETE ===`
and updates the production releases accordingly.
