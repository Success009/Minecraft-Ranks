# Kit Layouts (Ranked Kits)

Status: implemented, NOT yet tested in game (void world, stationary player and buttons are all unverified).

## What it does
You arrange the slots of a ranked kit once (Crystal, UHC, Pot, Mace, Sword).
Every match then gives you that kit in your arrangement, whether you are the host or the guest.
Community and custom kits are not affected.

## How to use
1. Open **Queue Settings** and press **Kit Layout**.
2. Pick a ranked kit. A local void world opens and the vanilla inventory is shown with the kit in its default slots.
3. Drag items between slots 0-35 to arrange them.
4. Use the buttons beside the inventory. Each one asks for confirmation first:
   - **Check (Save)**: saves the layout and leaves.
   - **R (Reset)**: clears the inventory and puts every item back in its default slot. The world stays open,
     and the saved layout file is not touched (press Check to keep the default).
   - **Cross (Cancel)**: leaves without saving.
   The editor prints no chat messages. `/save`, `/reset` and `/exit` still exist behind the buttons.

## The editor world
- Built from `void_world_gen_settings.dat`: a flat generator with one air layer, the `the_void` biome,
  `generate_structures = 0` and an empty `structure_overrides` list. All three standard dimensions must stay declared: with only the overworld,
  26.1.2 shows "Worlds using Experimental Settings are not supported" and refuses to load.
  `void_game_rules.dat` (copied from the arena) freezes time and weather, and turns off mob spawning and random ticks. No arena files are extracted,
  so the world loads and saves quickly and nothing generates (no structures, no mobs).
- Rebuild the settings file with `python3 fabric-bridge/tools/make_void_world_gen.py` (run from `fabric-bridge/`).
- The match arena cache still uses `helios.tar.gz`; only the kit editor world changed.
- Earlier bug: the first void settings left structures enabled, so trial chambers generated beside the player.

## The player in layout mode
- Survival, no flight, invulnerable. Custom kit creation still uses creative.
- No gravity, and one barrier block at (0, -61, -43) under the player at (0, -60, -43).
- The player is only teleported back if they drift more than one block. There is no per-tick teleport.
- Item use, block clicks and attacks are cancelled silently. Dropped items are removed.
- Any inventory change that removes, adds or swaps in a foreign item is silently rolled back.
  Items may be moved between slots 0-35, armor and offhand, so armor can be taken off and put on. Shift-click works.
- The inventory is reopened if closed (`LayoutEditorClient`). `ContainerGuardMixin` swallows Esc, E and Q
  and blocks throw, quick-move and clone clicks in the inventory.

## Load time (measured from latest.log, world files written to login, about 9 s)
- About 5 s: vanilla recipes, advancements and integrated server start. Not under the mod's control.
- About 2.1 s: spawn preparation ("Loading 48 chunks for player spawn"). 26.1.2 has no spawn chunk game rule to shrink it.
- The mod's own world setup (writing three small files) is negligible.

## Rules
- Armor and offhand (slots 36-40) can be changed in the editor, but only slots 0-35 are saved in the layout.
- `/save` refuses if a kit item is missing from slots 0-35 (for example still worn as armor) and names it. Put it back in an inventory slot or press Reset.
- At match time an invalid or colliding layout falls back to the default slots.

## How it works
- Entry: `KitLayoutScreen` calls `KitEditorManager.launchLayoutEditor`, which sets `layoutKit` and opens the `p2p_kit_editor` world.
- Save: `KitLayoutMapper` maps default slot to current slot, `KitLayout.withKit` merges it into `nml_inventory_layout.json`.
- Apply: `MatchCoordinator` reads the layout in the ranked-kit item loop through `KitLayout.slotFor`.
  The host uses its own file; the guest's layout comes from `P2PPvpMod.opponentLayouts`.
- Buttons: `InventoryScreenMixin` adds them; `LayoutIconButton` draws the vanilla beacon check and cross sprites.
- Unused code: `KitLayoutEditorScreen` and `LayoutBoard` (an earlier click-to-move screen) are not referenced any more. Delete once the world editor is confirmed.

## Guest sync
1. The client sends its saved layouts in the `/api/queue/join` payload (`layouts`).
2. The server cleans them with `layouts.sanitize_layouts` (slots 0-35, no shared target slot, size limits).
3. The host's match payload carries `opponent_layouts` (the guest's layouts). The guest's payload carries none.
4. The host stores them in `P2PPvpMod.opponentLayouts` and applies them when giving the guest their kit.
5. Challenges: layouts are sent with `/api/challenge/send` and `/api/challenge/respond` (accept), stored per player,
   and attached to the host's match record in `_create_match`. A decline stores nothing.

## Limits
- Both players need the new jar; a player on an older jar sends no layout and gets the default.
- Multiple presets per kit are not supported.

## Tests
- Java: full suite passes (`KitLayoutTest`, `KitLayoutMapperTest`, `LayoutGuardTest`, `LayoutBoardTest`).
- Server: `tests/test_layouts.py`, `tests/test_challenge_layouts.py`; suite 87, 0 failures.

## Not verified in game
- Void world load speed (and that the overworld-only settings file loads at all), and that no structures or mobs appear.
- Reset restoring default slots in the open inventory without tripping the item guard.
- Taking armor off and putting it on, and shift-click, with the guard accepting it.
- The player staying still on the barrier with no jitter.
- The inventory staying open, and Save / Reset / Cancel with their confirmation popups.
- A saved layout applied to both host and guest in a real match.
