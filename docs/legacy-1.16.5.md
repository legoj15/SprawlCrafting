# legacy-1.16.5 — Forge + Fabric 1.16.5 build (agent notes)

Self-contained Gradle build in `legacy-1.16.5/`, separate from the Stonecutter tree (its toolchain
can't share a build with the modern nodes) and from `forge-1.12.2/`. One shared source set, two loaders,
Java 8 bytecode, same `solver-core` planner. Forge is the priority loader.

## Layout

| Path | What |
|---|---|
| `common/src/main/java` | all gameplay, client UI, the recipe-book mixins, packets, config. Compiled into BOTH loaders via `srcDir` (no common jar). |
| `common/src/test/java` | Minecraft-free JUnit 5 tests of shared logic (`StationDrawTest`); run by `:forge:test` only (the classes are identical on Fabric) |
| `common/src/main/resources` | shared mixin config, lang, textures, icon, `pack.mcmeta` (pack_format 6) |
| `forge/` | ForgeGradle 7 build, `platform/forge/*`, AE2 station provider (`compat/ae2/*`), JEI compat (`compat/jei/*`), the JEI button mixin (`mixin/jei/*`, own config `sprawlcrafting.jei.mixins.json`), `META-INF/mods.toml` |
| `fabric/` | Loom (remap variant) build, `platform/fabric/*`, Mod Menu entrypoint, `fabric.mod.json` |
| `../solver-core` | the solver library `com.legoj15:solver`, a composite build (`settings.gradle`); Forge shades its classes (Forge 36 has no jar-in-jar), Fabric nests it (`include`). Its JUnit suite runs in its own build. See `solver-library.md` |

Version comes from the root `gradle.properties` (`version=`), read by `legacy-1.16.5/build.gradle`.
Pins live in `legacy-1.16.5/gradle.properties`: Forge 36.2.42, Fabric Loader 0.16.14, Fabric API
0.42.0+1.16, JEI 7.8.1.1018 (Forge only; JEI 1.16 has no Fabric build), Mod Menu 1.16.23, AE2 8.4.7
(Forge only, compileOnly).

Outputs: `forge/build/libs/sprawlcrafting-<ver>+mc1.16.5-forge.jar` (SRG, the release jar; the `-dev`
jar next to it is named and not for players) and `fabric/build/libs/sprawlcrafting-<ver>+mc1.16.5-fabric.jar`.

## Running it (Windows, PowerShell tool)

- `legacy-1.16.5\gw.ps1 :forge:build :fabric:build` — wrapper on JDK 21 (FG7/Loom need a modern host
  JDK; compile and dev runs use an auto-provisioned Java 8 toolchain). `gw.sh` is the Git Bash twin.
- `legacy-1.16.5\smoke.ps1 -Task :forge:runServer` (or `:fabric:runServer`) — writes `eula.txt` and an
  offline `server.properties` (port 25599) into `run-server/`, waits for `Done (`, kills the tree.
- `legacy-1.16.5\smoke.ps1 -Task :forge:runClient -Gradle '-Pjei'` (or `:fabric:runClient`) — waits for
  `Sound engine started` (title screen) and REQUIRES the mixin self-check line (below). `-Pjei` puts the
  full JEI in the Forge dev runtime; add `-Pae2` (`-Gradle '-Pjei -Pae2'`) for AE2 too.
- `legacy-1.16.5\gw.ps1 :forge:test` — the shared JUnit tests (`common/src/test/java`).
- `legacy-1.16.5\verify-mixin-targets.ps1` — javaps every recipe-book mixin target member in both
  named MC jars (Forge mavenizer jar, Loom merged-named jar). Add members there when a mixin grows.

### Mixin self-check (dev only)

Recipe-book classes only load when a player opens a recipe book, so a title-screen boot never applies
those mixins. With `SC_MIXIN_SELFTEST=1` (smoke.ps1 sets it for client runs) `client/MixinSelfCheck`
force-loads every target class at client init, so any injection failure (`defaultRequire = 1`) is logged
as `SprawlCrafting mixin self-check FAILED for <class>`. It caught the JEI fill-colour constant being
wrong on the first run. Off for players.

## The naming trap (read before touching common code)

Forge 1.16.5's "official" mappings in FG7 keep **MCP/SRG class names** (`net.minecraft.item.crafting.IRecipe`,
`client.gui.recipebook.RecipeBookGui`, `PacketBuffer`, `ResourceLocation`) and take only Mojang's
**member** names. Plain Loom Mojmap would give Mojang class names (`world.item.crafting.Recipe`), so the
shared source couldn't compile on both. The Fabric build therefore layers a **class-only** tiny mapping
(obf -> MCP class name, generated at configuration time from MCPConfig `config/joined.tsrg`, downloaded
by plain HTTP into `fabric/build/forge-class-names/`) over `officialMojangMappings()` with
`mergeNamespace('official')`. Result: Fabric dev names == Forge dev names; write common code with the
Forge names. Production is unaffected (Fabric jar + refmap are intermediary; Forge jar + refmap are SRG).

## Toolchain traps hit

- **FG7 + 1.16.5 works**, including the Renamer plugin: `renamer.enableMixinRefmaps` + the Mixin AP
  (`org.spongepowered:mixin:0.8.5:processor`) write an SRG refmap, and `renamer.classes(jar)` with
  `mappings renamer.mixin.generatedMappings` renames shadows to `field_`/`func_`. `RenameJar` is not an
  archive task: set `output`, not `archiveVersion`.
- **Renamer adds a decompiler artifact to the shipped jar.** Unless told otherwise the Renamer tool writes
  `fernflower_abstract_parameter_names.txt` into the jar it renames. `forge/build.gradle` passes
  `--disable-abstract-param` through `renameJar`'s `additionalArgs`; after any Renamer bump re-check with
  `jar tf forge/build/libs/sprawlcrafting-<ver>+mc1.16.5-forge.jar | grep -i fernflower` (must print nothing).
  The Fabric jar never had it.
- **Dev runs need `-mixin.config=` args** (the manifest `MixinConfigs` is only read from jars). The
  "Reference map could not be read" warning in dev is expected.
- **Forge bundles Mixin 0.8.4, no MixinExtras**: use `@Redirect`/`@ModifyConstant`, not `@WrapOperation`.
- **Loom 1.17 disables the Mixin AP by default** (no refmap). `loom.mixin.useLegacyMixinAp = true` restores
  the intermediary refmap.
- **Never resolve a Gradle configuration at configuration time in the Fabric build** — Loom then fails
  with "Cannot mutate content repository descriptor ... after repository has been used". Hence the HTTP
  download for MCPConfig.
- 1.16.5 ships Gson 2.8.0 (`new JsonParser().parse`, no `parseReader`) and log4j, not slf4j.
- Fabric 1.16.5 networking receivers run on the netty thread: decode there, `server.execute` /
  `client.execute` the work.

## JEI 7 (Forge) specifics

- No API factory for JEI's basic transfer handler: `SprawlCraftingJeiPlugin` builds JEI's internal
  `mezz.jei.transfer.BasicRecipeTransferHandler` reflectively (3-arg constructor) from our own
  `IRecipeTransferInfo` (slots 1-9 grid, 10-45 inventory) and wraps it. If reflection fails it logs and
  registers nothing (JEI's own handler stays). Ours wins because JEI's handler table is last-write-wins
  and the vanilla plugin registers first.
- No per-error button colour API: a COSMETIC error is filled with a hardcoded `0x80FFA500`.
  `JeiTransferButtonMixin` (`@ModifyConstant`, `remap = false`, method names `render` dev /
  `func_230430_a_` prod) swaps it for yellow (deferred) / orange (gather list). Its config's plugin
  (`JeiMixinPlugin`) only adds the mixin when `LoadingModList` has `jei`.
- Tooltips for our errors are drawn in `showError` via `Screen#renderComponentTooltip`.

## AE2 Crafting Terminal station (issue #29, Forge only)

Port of the root tree's #2/#27 station (see DESIGN.md, "Crafting stations"). Target: AE2 **8.4.7** for
Forge 1.16.5, mod id **`appliedenergistics2`** (the short `ae2` id is 1.18+).

- **Seam.** Shared code (`common/.../craft/StationStorage`, `StationStorages`, `StationDraw`,
  `CraftingGrids`) never names AE2; `StationStorages.bootstrap()` (both loaders' mod init) reflectively
  calls `compat.ae2.Ae2Station.init()` only when `isModLoaded("appliedenergistics2")`. The compat class
  is in the Forge source set only, so on Fabric the lookup is a ClassNotFoundException and a no-op.
  compileOnly + reflective entry (not reflection throughout): the AE2 API is javap-verified and
  type-checked at compile time, and nothing of AE2 loads without AE2.
- **Dependency.** `appeng:appliedenergistics2:8.4.7` from `https://modmaven.dev/` (repo filtered to group
  `appeng`) is the Forge jar: every class and resource matches the CurseForge jar byte-for-byte (CRC);
  only the signature files differ. `compileOnly` through `renamer.dependency` (SRG -> dev names).
  `-Pae2` adds it to dev runs (combine with `-Pjei`). Nothing of AE2 ships in our jar.
- **AE2 8.4.7 API used** (verified with javap): `CraftingTermContainer` (only host:
  `CraftingTerminalPart`; no wireless crafting terminal on this line), `MEMonitorableContainer`'s
  `protected final` `monitor`/`powerSource` read reflectively, `getNetworkNode()`/`isPowered()`/
  `getClientRepo()`/`getViewCells()`/`getStorageChannel()`, `AEBaseContainer.getActionSource()`/
  `isRemote()`/`isValidContainer()`, `Platform.poweredExtraction/poweredInsert`,
  `ViewCellItem.createFilter`. Usable = AE2's own `canInteractWithGrid` with `updatePowerStatus` done
  live server side (node active, else energy grid powered, else a 1 AE simulated draw), the synced
  `isPowered()` flag client side. View cells are applied on both sides (AE2 filters them in the screen,
  not in the repo).
- **JEI.** `StationTransferHandler` (in `compat/jei`, never links AE2) is registered per
  `StationStorages.menus()` and fronts AE2's own `appeng.integration.modules.jei.CraftingRecipeTransferHandler`
  (package-private constructor, built reflectively; null means the engine takes every recipe): green,
  an unusable terminal and red-with-needs-off go to AE2's handler, behind the busy gate; yellow starts
  our engine; red opens the gather list (available mid-job).
- **Protocol.** `PAUSED_STATION` is appended to `CraftProgressPacket.State` (ordinal 5). A separate
  message-less marker channel `sprawlcrafting:progress_states_v2` (same lenient predicates as `main`)
  carries the capability both ways: the server downgrades to `PAUSED` unless the client has it, and the
  client counts a station only when the server has it (else the JEI handler hands everything to AE2).
  The `main` channel's ids 0-2 and protocol string are unchanged.

### Traps

- **Item identity: `Damage:0`.** As on 1.20.1, `new ItemStack(item)` of a damageable item runs
  `setDamageValue(getDamageValue())` in the constructor, so a pristine tool carries `{Damage:0}` and AE2
  (identity = `ItemStack.matches`: item, tag, Forge caps) stores it under that tagged key. "Plain" is
  therefore "AE2-equal to `channel.createStack(new ItemStack(item))`" (memoized per item), and extract /
  insert use that same key. Worn, enchanted, renamed or capability-carrying copies are never touched.
- **JEI handler order.** JEI 7 keys transfer handlers by (exact container class, category), last write
  wins, and visits plugins in mod order. Ours replaces AE2's only because mods.toml has an optional
  `appliedenergistics2` dependency with `ordering = "AFTER"` (side CLIENT, `versionRange = "[0,)"` so a
  version mismatch can never block loading).
- **Signed AE2 jar in dev runs.** Renamer rewrites the classes but keeps `META-INF/*.SF/*.RSA`, and
  ModLauncher then fails the dev run with "SHA-256 digest error for appeng/...". `forge/build.gradle`
  strips the signature files in a `doLast` on the rename task. Declare the renamed dependency once
  (`def ae2 = renamer.dependency(...)`) and reuse it: a second `renamer.dependency` of the same coordinate
  is a second task writing the same file, which Gradle rejects.
- **AE2's mixins in dev** load fine: FG7 already remaps their SRG refmap (`formatDevMixinMappings.srg`).
- **Never touch `CraftingTermContainer.TYPE`** from our code (`menus()` returns the class only): it is a
  registry object.

## Networking invariants (same as every other target)

- Forge: `SimpleChannel` `sprawlcrafting:main`, ids 0 start craft (C2S), 1 screen state (C2S), 2 progress
  (S2C); both version predicates accept `ABSENT` and `ACCEPTVANILLA`; `canReceive` = `isRemotePresent`;
  DISPLAYTEST = `IGNORESERVERONLY`.
- Fabric: one play channel per packet id (`sprawlcrafting:start_craft`, `crafting_screen_state`,
  `craft_progress`); `ServerPlayNetworking.canSend` / `ClientPlayNetworking.canSend` guards.
- No command. Client offers are gated on `serverHasMod()`.
- Forge marker channel `sprawlcrafting:progress_states_v2` (no messages, issue #29): presence-only
  capability for `PAUSED_STATION` and station counting. Fabric has no station, so it registers nothing
  and both capability checks return false.

## Parity status

Ported: recipe-book deferred crafting (yellow slot via `@Redirect` of `RecipeWidget#blit` swapping the
u=29 cell for our 25x25 PNGs, two-click preview + ghost, right-click red -> gather screen, overlay
right-click), grid-fill final step, JEI "+" (yellow/orange, gather list, R/U on the gather screen),
config file + screen (Forge mod-list Config button, Fabric Mod Menu), toasts + step sound, networking.

Not ported / differs:
- AE2 Crafting Terminal station: Forge only (no AE2 for Fabric 1.16.5 in scope).
- No REI on Fabric 1.16.5 (the brief scoped JEI on Forge only); the REI toggle is absent.
- Fabric 1.16.5 has no JEI, so the Fabric jar has recipe-book integration only.
- Variant-overlay popup buttons stay vanilla-white (same accepted limitation as the modern nodes).
- Fabric reach check uses vanilla's fixed 64.0 sq; Forge uses `(REACH_DISTANCE + 3)^2` like
  `ServerPlayNetHandler#handleUseItemOn`.

Verified (2026-11-08): both builds, solver-core suite (43 tests) on Java 8 in both, Forge + Fabric
dedicated servers to `Done (`, Forge (+JEI) and Fabric clients to the title screen with the self-check
passing, jar refmaps (SRG / intermediary), every mixin target javap-verified. NOT yet: an in-world
playtest, a modless-connect test, or the Infinity Matter Dominator Sword stress pack.

AE2 station (2026-11-19): both builds, `StationDrawTest` (6, Forge `:forge:test`, JUnit 5 on Java 8),
Forge server and Forge+JEI client smokes, and a Forge dev client and server with `-Pae2` (client also
`-Pjei`) to the title screen / `Done (` with "appliedenergistics2 crafting stations enabled" logged. NOT
yet: anything in-world (JEI registers transfer handlers at world join, so the handler order and the
reflective AE2 handler are unexercised), or the user's real Forge instance.
