# AutoShaftMine – Agent Handoff

Briefing for a Claude agent picking up this project on another machine. Read it fully before touching code.

## 1. What this is

A **client-side Fabric mod for Minecraft** (mod id `autoshaftmine`, group `com.recklessgreed`, author recklessGreed, license CC0-1.0, version 1.0.0).
Press a key (default **G**) and the player automatically digs a straight 1x2 tunnel ("shaft") in the direction they are facing, with the tool in the main hand. It stops on hazards and mines/backfills ore veins it passes.

- Repo: https://github.com/DaWenning/AutoShaftMine (branch `main`)
- Everything runs on the client by driving the same inputs a human would (`keyUp`, `continueDestroyBlock`, `useItemOn`). No server component; `AutoShaftMine` (main entrypoint) only logs and offers `id(path)`.

## 2. Tech stack / environment

| Item | Value |
|---|---|
| Minecraft | **26.3** (new year-based versioning; Mojang names, unobfuscated) |
| Fabric Loader | 0.19.5 (lowered from 0.20.1 in `03cbd16`; `fabric.mod.json` requires `>=0.19.5`) |
| Fabric Loom | `net.fabricmc.fabric-loom` 1.19-SNAPSHOT |
| Fabric API | 0.165.0+26.3 |
| Java | **25** (`options.release = 25`) – JDK 25 required |
| ModMenu | `com.terraformersmc:modmenu:20.0.3`, **`compileOnly`** (maven `https://maven.terraformersmc.com/releases/`) – see §3a |
| Build | Gradle wrapper (`./gradlew build`), configuration cache on |
| CI | `.github/workflows/build.yml` (Ubuntu, JDK 25 Microsoft, `./gradlew build`) |

Setup on a new PC: install JDK 25, clone, then `./gradlew build` (build) or `./gradlew runClient` (launch the game with the mod; creates the git-ignored `run/` dir). IntelliJ project files (`.idea/`) are ignored by git.

Source sets are split (`loom.splitEnvironmentSourceSets()`): `src/main` (common) and `src/client` (client-only). All real code is in `src/client`.

Because MC 26.x APIs differ from older versions you may know (e.g. `player.sendOverlayMessage`, `mc.gui.screen()`, `Identifier.fromNamespaceAndPath`, `KeyMapping.Category`, `getInventory().getSelectedSlot()`, `Item.getAttackAnimation`), **do not "fix" unfamiliar-looking names from memory** – check the decompiled/sources jar via Gradle (`genSources`) first.

## 3. Code map (`src/client/java/com/recklessgreed/autoshaftmine/client/`)

- `AutoShaftMineClient` – client entrypoint. Registers the toggle `KeyMapping` (G, category `autoshaftmine:main`) and an `END_CLIENT_TICK` handler: consumes key clicks → `miner.toggle`, stops with `WORLD_LEFT` if the player vanished, then `miner.tick`.
- `ShaftMiner` – the state machine (see §4).
- `HazardScanner` – stateless look-ahead returning `Optional<StopReason>`.
- `LookController` – humanlike camera used by `ShaftMiner.tick` (setting `smoothMovement`): yaw eased per tick (max 22 deg, factor 0.4), pitch follows the centre of the block being mined (ores via `mineFocus`) or an idle drift, small sinus/random offsets re-rolled per new target, 0-3 tick reaction pause when the target block changes. Walking/mining waits until the yaw is within 6 deg of the segment direction, so turns are visible instead of instant. Untested in-game; tune the constants at the top of the class.
- `StopReason` – enum; each value maps to translation key `message.autoshaftmine.stopped.<key>`.
- `config/` – settings + ModMenu integration (see §3a).
- `mixin/ExampleClientMixin`, `main/.../mixin/ExampleMixin` – leftover template mixins (empty); mixin configs `autoshaftmine(.client).mixins.json`. Can be removed if unused.

Resources: `src/client/resources/assets/autoshaftmine/lang/{en_us,de_de}.json` (all user-facing text, key binding name, overlay messages, `config.autoshaftmine.*` settings labels), `src/main/resources/fabric.mod.json`, `assets/autoshaftmine/icon.png`.

## 3a. ModMenu integration and settings (commit `03cbd16`)

Package `com.recklessgreed.autoshaftmine.client.config`:

- `ModMenuIntegration` implements `ModMenuApi`; `getModConfigScreenFactory()` returns `ConfigScreen::new`. Registered in `fabric.mod.json` under entrypoint key `"modmenu"`. The mod works without ModMenu installed (the entrypoint is only loaded by ModMenu).
- `ConfigScreen` – plain vanilla `Screen` (no Cloth Config / YACL dependency). Two columns of 150px (10px gap, 24px rows, starting at `height / 6`), filled row by row via `addCell`; Done is centred below the grid. Labels are kept short to fit 150px. Boolean options are click-to-toggle buttons labelled via `Component.translatable(key, OPTION_ON/OFF)`; the drop button cycles 1..10 and wraps. `onClose()` calls `ModConfig.get().save()` and returns to the parent screen (`minecraft.setScreenAndShow(parent)`). Settings are saved only on close, not on every click.
- `ModConfig` – Gson POJO (pretty-printed), lazily loaded singleton via `ModConfig.get()`, stored at `<fabric config dir>/autoshaftmine.json`. Broken/unreadable file → silently falls back to defaults; write errors are swallowed.

| Field | Default | Effect | Read in |
|---|---|---|---|
| `autoFillDrops` | `true` | Place filler block on a `DROP` hazard instead of stopping immediately | `ShaftMiner.tick` (hazard branch) |
| `mineOres` | `true` | Ore-vein mining; when off, `oreTarget` is nulled and `holes` cleared | `ShaftMiner.handleOres` |
| `mineOresBelow` | `true` | Also mine ores up to `ORE_DEPTH_BELOW` (2) rows below the feet, i.e. below the tunnel floor; they are refilled by the normal backfill. The block directly under the player is still never mined, and ores touching a fluid below feet level are skipped | `ShaftMiner.findOre/findOreInCube` |
| `followVeins` | `true` | Ore excursion, see §4b | `ShaftMiner.handleOres/planExcursion` |
| `backfillOres` | `true` | Refill mined-out ore cells | `ShaftMiner.handleOres` |
| `maxSafeDrop` | **`1`** | Empty blocks below the next floor tile still treated as safe; read through `safeDrop()` which clamps to 1..`MAX_SAFE_DROP_LIMIT` (10) to survive hand-edited files | `HazardScanner.isDangerousDrop` |
| `advancedMode` | `true` | Branch pattern, see §4a (read once at run start) | `ShaftMiner.start` |
| `smoothMovement` | `true` | Humanlike camera (see `LookController`), `false` = old instant yaw snap | `LookController` |
| `forwardLength` | `3` | Forward steps before each branch pair, 1-7; `forwardLength()` clamps; read once at run start | `ShaftMiner.start/updateSegment` |
| `branchLength` | `3` | Length of each side branch (and its return), 1-7; `branchLength()` clamps; read once at run start | `ShaftMiner.start/updateSegment` |
| `stopOnHealthLoss` | `true` | Stop with `HEALTH_LOST` when health drops | `ShaftMiner.tick` |
| `minToolDurability` | `2` | Stop (`TOOL_BREAKING`) at this many remaining uses; screen cycles 2/5/10/25/50/100 (`DURABILITY_STEPS`) | `ShaftMiner.isToolAlmostBroken` |
| `lavaLookahead` | `2` | Blocks ahead scanned for lava; `lavaLookahead()` clamps to 1..5 | `HazardScanner.hasLavaNearby` |

Lang keys (both `en_us` and `de_de`, keep in sync): `config.autoshaftmine.title`, `.auto_fill_drops`, `.mine_ores`, `.mine_ores_below`, `.follow_veins`, `.backfill_ores`, `.advanced_mode`, `.forward_length`, `.branch_length`, `.smooth_movement`, `.max_safe_drop`, `.stop_on_health_loss`, `.min_tool_durability`, `.lava_lookahead` (the toggles take `%s` for On/Off, the drop label takes the number).

Client commands (`ConfigCommand`, registered in `AutoShaftMineClient.onInitializeClient` via Fabric `ClientCommandRegistrationCallback`; works without ModMenu): `/autoshaftmine` or `/autoshaftmine list` shows all values, `/autoshaftmine set <option> <value>` changes one and saves immediately, `/autoshaftmine reset` restores defaults; `/asm` is an alias. Options: `advanced_mode`, `smooth_movement`, `auto_fill_drops`, `mine_ores`, `mine_ores_below`, `follow_veins`, `backfill_ores`, `stop_on_health_loss` (true/false) and `max_safe_drop` 1-10, `min_tool_durability` 0-1000, `lava_lookahead` 1-5, `forward_length` 1-7, `branch_length` 1-7. New settings must be added to `ConfigCommand.build()` (and `ModConfig.resetToDefaults()`) as well as the screen. Command feedback keys: `command.autoshaftmine.{list,set,reset}`.

Build/dependency caveats:
- ModMenu is **compile-only** because no released ModMenu supports Minecraft 26.3 yet (comment in `build.gradle`). Consequence: `runClient` does **not** have ModMenu, so the config screen cannot be opened there. Once a compatible ModMenu exists, add `localRuntime "com.terraformersmc:modmenu:<version>"`. Until then edit `config/autoshaftmine.json` by hand to test settings, or temporarily open `ConfigScreen` from a debug hook.
- `gradle.properties`: `loader_version` lowered 0.20.1 → 0.19.5 (the commit says "after testing in Prod", presumably to match the loader of the owner's real installation).

Related behavior changes in the same commit (not ModMenu, but easy to miss):
- **`MAX_SAFE_DROP` constant (was 3) removed**; the configurable default is now **1**, so out of the box the miner treats a 2-block-deep hole as dangerous earlier than before. Raise it in the screen if the old behavior is wanted.
- `ChatScreen` no longer triggers `SCREEN_OPENED`; every other screen still stops the miner (this is also why opening the ModMenu config screen mid-run stops mining).
- `isOre` now also matches an explicit `ORE_BLOCKS` set (vanilla ores incl. deepslate variants, nether gold/quartz, ancient debris) because the `c:ores` tag is absent when the server doesn't supply it.

## 4. Behavior (current state, per tick when active)

1. **Abort checks** → `stop(...)`: any screen except `ChatScreen` opened (`SCREEN_OPENED`), health dropped since last tick (`HEALTH_LOST`), tool has ≤ 2 durability left (`TOOL_BREAKING`; only for damageable items), player gone (`WORLD_LEFT`).
2. **Hazard scan** (`HazardScanner.scan(level, feet, dir)`):
   - Lava in a 3 wide x 4 high box (heights -1..+2) from the player out 2 blocks ahead → `LAVA`
   - Any fluid at ahead / ahead+1 → `FLUID`
   - Magma block, fire, soul fire, cactus at ahead, ahead+1, ahead-1 → `DAMAGE_BLOCK`
   - Floor ahead has no collision for `ModConfig.safeDrop()` consecutive blocks down (default 1, max 10) → `DROP`
3. **Drop handling**: on `DROP` (only if `autoFillDrops` is enabled), up to `MAX_FILL_ATTEMPTS = 3` times, place a filler block (cobble/stone-type/deepslate/dirt/netherrack from the **hotbar**) on the floor tile ahead by clicking the side face of the block the player stands on; previous hotbar slot is restored. If it cannot be filled, stop with `DROP`.
4. **Ore handling** (`handleOres`, takes priority over tunnelling, walking is paused while busy):
   - Ore = `c:ores` convention tag (`ConventionalBlockTags.ORES`) or a block in the explicit `ORE_BLOCKS` set (only when `mineOres` is on; backfill only when `backfillOres` is on).
   - Search cubes (3x3 wide, reach ≤ 4 blocks): around the player (rows -1..+2 relative to feet), around an ore in the floor under the player (never mines the block the player stands on), and around **every already mined ore position** → veins are followed ore to ore.
   - Mined-out ore cells go into `holes`. When no ore is left, `backfill` places filler blocks into the holes (placing against any solid neighbor face), skipping shaft cells (the 1x2 column in the tunnel line) and non-air cells. Backfill is capped at `MAX_BACKFILL_TICKS = 60`, and abandoned if no filler is in the hotbar.
5. **Tunnelling**: face the direction fixed at start (`player.getDirection()`), mine the head-level block ahead first (so gravel/sand above can't pile in), then the foot-level block; if both are air, hold `keyUp` to walk. Unbreakable block (destroy speed < 0) → `UNBREAKABLE`.
6. Stop (manual toggle or any reason) releases `keyUp`, calls `stopDestroyBlock`, and shows the reason as an overlay message.

Design notes: only the main-hand tool is used; no tool switching, no inventory management, no pathing/vertical digging; direction is horizontal and fixed per run.

## 4a. Advanced mode (`ModConfig.advancedMode`)

Pattern `PATTERN` in `ShaftMiner` (relative to the facing at start), repeated forever: **F forward → N left → N back to the right → N right → N back to the left** (F = `forwardLength`, N = `branchLength`, both default 3, 1-7; `PATTERN` is a `List<Turn>`, the length is picked by turn type in `updateSegment`). Interpretation of the owner's request ("2 forward, 3 left, 3 right") as classic branch mining with return to the main line; change the `PATTERN` list if a different shape was meant (e.g. left 3, then right 6).

How it works: `direction` is now the *current segment's* direction (`mainDirection` stays fixed). `updateSegment` counts blocks travelled along the segment axis from `segmentStart`; at the end block the player keeps walking until past the block centre (`centering`, skips mining) and then the next segment starts with the new `direction`. Return segments walk through the already mined tunnel (hazard scan and ore handling still run). `tunnelCells` collects every visited feet/head cell and `isShaftCell` treats them as shaft, so backfill never closes the way back.

Centering: walking alone overshoots the block middle, so the player ended up off-centre after returning into the main line. Now the player is placed on the block middle at run start, and at every segment end the along-axis coordinate is snapped to the block centre (`player.setPos`, `CENTER_TOLERANCE` = 0.1) before the turn; this keeps the lateral position of the next segment centred. The snap now sets both horizontal coordinates to the block middle **and zeroes the horizontal velocity**: leftover momentum made the player slide ~0.2 blocks into the side opening after the turn (left return → forward) and then bump into the wall of the 1-wide corridor.

**Untested in-game** – check corner turns (player lateral offset vs. 1-wide corridor), branches with gravel, and ore backfill near branch junctions.

## 4b. Ore excursions (`followVeins`)

When no ore is in reach (4 blocks from the eye) but ores next to mined-out cells (`holes`) or unfilled holes remain, `handleOres` calls `planExcursion`: a BFS (`findPath`, range `EXCURSION_RANGE` = 12 cells) over **walk cells** (cells the player has stood in: `tunnelCells` is now tracked in every mode, feet + head must be air) looks for a cell from which a target is in reach. The first stop stores `returnCell` and `savedDirection`; `followPath` then walks the path (direction per step, `LookController` turns, snap to cell middle at each corner like in `updateSegment`). At each stop the normal ore mining and backfill run again; when nothing is left, `planReturn` walks back to `returnCell` and `finishExcursion` restores the direction. While an excursion runs, `updateSegment` is paused. Safety: `MAX_EXCURSION_TICKS` (900) or a broken path stops the run with `StopReason.STUCK`.

`backfill` now only fills holes in reach and keeps the others in `holes` so a later stop can fill them (it used to give up on them). Without `followVeins` or without a path, leftover holes are cleared as before.

Limits: the walk only uses already visited tunnel cells (never digs a new path), flat (same y), and **is untested in-game**.

## 5. History of work (git)

1. `76214f4` **Initial commit: AutoShaftMine Fabric mod** – generated from the Fabric template, then the core feature: toggle key, `ShaftMiner` tunnelling, `HazardScanner` (lava, fluids, damaging blocks, drops), `StopReason`, de/en translations, durability + health + screen/world-left safety stops, drop filling with a hotbar filler block.
2. `a250f8f` **Fill dangerous drops and mine ore veins with backfill** – added ore vein mining (3x3x3 cubes, chaining along the vein via `holes`) and the backfill phase to refill mined-out ore cells; refactored block placement into `placeAgainst`. (+222 lines in `ShaftMiner`)

Manual testing so far (from `run/` logs and command history, not automated): a creative-flat/“New World” test world, switched to Survival, `/effect give <player> haste infinite 3|10 true` to speed up digging and `night_vision` for visibility. The game ran and stopped cleanly; **no automated tests exist**, and I have no record of the ore/backfill behavior being confirmed in detail in-game – verify it before building on it.
3. `03cbd16` **Many Changes after testing in Prod** (authored by recklessGreed) – ModMenu integration + `ModConfig`/`ConfigScreen` (§3a), explicit ore list, chat screen no longer interrupts, Fabric Loader lowered to 0.19.5. Net +184/-11 lines across 10 files.
4. `6bef8ef` Damage stop, tool durability and lava lookahead settings.
5. *(uncommitted at time of writing)* Advanced mode (§4a).

## 6. Conventions (from the owner's global rules)

- All code, identifiers, comments, logs, commit messages and in-code docs in **English**. User-facing UI strings live in lang files (en_us + de_de – keep both in sync when adding keys).
- Code style: tabs, Javadoc-style `/** */` on non-obvious members, small private helpers.
- Commit messages end with the co-author trailer used in the existing history. Only commit/push when the owner asks.
- The owner communicates in German; reply to them in German.

## 7. Known gaps / ideas for next steps

- Verify ore chaining and backfill in-game (veins touching the shaft, ore under the feet, no filler in hotbar).
- `findFillerSlot` only checks the hotbar; consider inventory pull-in or a "no filler" stop reason/message.
- Falling blocks (gravel/sand) in the head-level row are handled only by mining order, not by detection.
- Configurable: drop depth, feature toggles, damage stop, durability threshold, lava lookahead. Still constants: `MAX_FILL_ATTEMPTS`, `MAX_BACKFILL_TICKS` are still constants and could be added to `ModConfig`/`ConfigScreen` (add lang keys in both files).
- `ConfigScreen` saves only on `onClose()`; pressing Esc routes through `onClose()` too, but a crash loses changes. `ModConfig.load/save` swallow errors silently – consider logging.
- Config screen is untested in-game here (ModMenu is not on the dev runtime classpath); re-add `localRuntime` for ModMenu when a 26.3-compatible release appears.
- Default `maxSafeDrop = 1` differs from the previous hard-coded 3 – confirm that is intended.
- Remove the unused `Example*Mixin` classes and their mixin config entries, and replace the "Hello Fabric world!" log.
- Add tests (e.g. pure logic of `HazardScanner` via GameTest) and a README.

## 8. Quick start for the next agent

```
git clone https://github.com/DaWenning/AutoShaftMine.git
cd AutoShaftMine
./gradlew build        # needs JDK 25
./gradlew runClient    # in-game: press G while holding a pickaxe and facing a wall
```
