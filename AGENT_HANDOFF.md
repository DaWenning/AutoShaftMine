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
| Fabric Loader | 0.20.1 |
| Fabric Loom | `net.fabricmc.fabric-loom` 1.19-SNAPSHOT |
| Fabric API | 0.165.0+26.3 |
| Java | **25** (`options.release = 25`) – JDK 25 required |
| Build | Gradle wrapper (`./gradlew build`), configuration cache on |
| CI | `.github/workflows/build.yml` (Ubuntu, JDK 25 Microsoft, `./gradlew build`) |

Setup on a new PC: install JDK 25, clone, then `./gradlew build` (build) or `./gradlew runClient` (launch the game with the mod; creates the git-ignored `run/` dir). IntelliJ project files (`.idea/`) are ignored by git.

Source sets are split (`loom.splitEnvironmentSourceSets()`): `src/main` (common) and `src/client` (client-only). All real code is in `src/client`.

Because MC 26.x APIs differ from older versions you may know (e.g. `player.sendOverlayMessage`, `mc.gui.screen()`, `Identifier.fromNamespaceAndPath`, `KeyMapping.Category`, `getInventory().getSelectedSlot()`, `Item.getAttackAnimation`), **do not "fix" unfamiliar-looking names from memory** – check the decompiled/sources jar via Gradle (`genSources`) first.

## 3. Code map (`src/client/java/com/recklessgreed/autoshaftmine/client/`)

- `AutoShaftMineClient` – client entrypoint. Registers the toggle `KeyMapping` (G, category `autoshaftmine:main`) and an `END_CLIENT_TICK` handler: consumes key clicks → `miner.toggle`, stops with `WORLD_LEFT` if the player vanished, then `miner.tick`.
- `ShaftMiner` – the state machine (see §4).
- `HazardScanner` – stateless look-ahead returning `Optional<StopReason>`.
- `StopReason` – enum; each value maps to translation key `message.autoshaftmine.stopped.<key>`.
- `mixin/ExampleClientMixin`, `main/.../mixin/ExampleMixin` – leftover template mixins (empty); mixin configs `autoshaftmine(.client).mixins.json`. Can be removed if unused.

Resources: `src/client/resources/assets/autoshaftmine/lang/{en_us,de_de}.json` (all user-facing text, key binding name + overlay messages), `src/main/resources/fabric.mod.json`, `assets/autoshaftmine/icon.png`.

## 4. Behavior (current state, per tick when active)

1. **Abort checks** → `stop(...)`: screen opened (`SCREEN_OPENED`), health dropped since last tick (`HEALTH_LOST`), tool has ≤ 2 durability left (`TOOL_BREAKING`; only for damageable items), player gone (`WORLD_LEFT`).
2. **Hazard scan** (`HazardScanner.scan(level, feet, dir)`):
   - Lava in a 3 wide x 4 high box (heights -1..+2) from the player out 2 blocks ahead → `LAVA`
   - Any fluid at ahead / ahead+1 → `FLUID`
   - Magma block, fire, soul fire, cactus at ahead, ahead+1, ahead-1 → `DAMAGE_BLOCK`
   - Floor ahead has no collision for 3 consecutive blocks down (`MAX_SAFE_DROP = 3`) → `DROP`
3. **Drop handling**: on `DROP`, up to `MAX_FILL_ATTEMPTS = 3` times, place a filler block (cobble/stone-type/deepslate/dirt/netherrack from the **hotbar**) on the floor tile ahead by clicking the side face of the block the player stands on; previous hotbar slot is restored. If it cannot be filled, stop with `DROP`.
4. **Ore handling** (`handleOres`, takes priority over tunnelling, walking is paused while busy):
   - Ore = `c:ores` convention tag (`ConventionalBlockTags.ORES`) or ancient debris.
   - Search cubes (3x3 wide, reach ≤ 4 blocks): around the player (rows -1..+2 relative to feet), around an ore in the floor under the player (never mines the block the player stands on), and around **every already mined ore position** → veins are followed ore to ore.
   - Mined-out ore cells go into `holes`. When no ore is left, `backfill` places filler blocks into the holes (placing against any solid neighbor face), skipping shaft cells (the 1x2 column in the tunnel line) and non-air cells. Backfill is capped at `MAX_BACKFILL_TICKS = 60`, and abandoned if no filler is in the hotbar.
5. **Tunnelling**: face the direction fixed at start (`player.getDirection()`), mine the head-level block ahead first (so gravel/sand above can't pile in), then the foot-level block; if both are air, hold `keyUp` to walk. Unbreakable block (destroy speed < 0) → `UNBREAKABLE`.
6. Stop (manual toggle or any reason) releases `keyUp`, calls `stopDestroyBlock`, and shows the reason as an overlay message.

Design notes: only the main-hand tool is used; no tool switching, no inventory management, no pathing/vertical digging; direction is horizontal and fixed per run.

## 5. History of work (git)

1. `76214f4` **Initial commit: AutoShaftMine Fabric mod** – generated from the Fabric template, then the core feature: toggle key, `ShaftMiner` tunnelling, `HazardScanner` (lava, fluids, damaging blocks, drops), `StopReason`, de/en translations, durability + health + screen/world-left safety stops, drop filling with a hotbar filler block.
2. `a250f8f` **Fill dangerous drops and mine ore veins with backfill** – added ore vein mining (3x3x3 cubes, chaining along the vein via `holes`) and the backfill phase to refill mined-out ore cells; refactored block placement into `placeAgainst`. (+222 lines in `ShaftMiner`)

Manual testing so far (from `run/` logs and command history, not automated): a creative-flat/“New World” test world, switched to Survival, `/effect give <player> haste infinite 3|10 true` to speed up digging and `night_vision` for visibility. The game ran and stopped cleanly; **no automated tests exist**, and I have no record of the ore/backfill behavior being confirmed in detail in-game – verify it before building on it.

## 6. Conventions (from the owner's global rules)

- All code, identifiers, comments, logs, commit messages and in-code docs in **English**. User-facing UI strings live in lang files (en_us + de_de – keep both in sync when adding keys).
- Code style: tabs, Javadoc-style `/** */` on non-obvious members, small private helpers.
- Commit messages end with the co-author trailer used in the existing history. Only commit/push when the owner asks.
- The owner communicates in German; reply to them in German.

## 7. Known gaps / ideas for next steps

- Verify ore chaining and backfill in-game (veins touching the shaft, ore under the feet, no filler in hotbar).
- `findFillerSlot` only checks the hotbar; consider inventory pull-in or a "no filler" stop reason/message.
- Falling blocks (gravel/sand) in the head-level row are handled only by mining order, not by detection.
- Thresholds are hard-coded constants (durability, drop depth, lava lookahead); a config screen/file could expose them.
- Remove the unused `Example*Mixin` classes and their mixin config entries, and replace the "Hello Fabric world!" log.
- Add tests (e.g. pure logic of `HazardScanner` via GameTest) and a README.

## 8. Quick start for the next agent

```
git clone https://github.com/DaWenning/AutoShaftMine.git
cd AutoShaftMine
./gradlew build        # needs JDK 25
./gradlew runClient    # in-game: press G while holding a pickaxe and facing a wall
```
