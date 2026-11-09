# legacy-1.16.5 — Forge + Fabric 1.16.5 build (agent notes)

Self-contained Gradle build in `legacy-1.16.5/`, separate from the Stonecutter tree (its toolchain
can't share a build with the modern nodes) and from `forge-1.12.2/`. One shared source set, two loaders,
Java 8 bytecode, same `solver-core` planner. Forge is the priority loader.

## Layout

| Path | What |
|---|---|
| `common/src/main/java` | all gameplay, client UI, the recipe-book mixins, packets, config. Compiled into BOTH loaders via `srcDir` (no common jar). |
| `common/src/main/resources` | shared mixin config, lang, textures, icon, `pack.mcmeta` (pack_format 6) |
| `forge/` | ForgeGradle 7 build, `platform/forge/*`, JEI compat (`compat/jei/*`), the JEI button mixin (`mixin/jei/*`, own config `sprawlcrafting.jei.mixins.json`), `META-INF/mods.toml` |
| `fabric/` | Loom (remap variant) build, `platform/fabric/*`, Mod Menu entrypoint, `fabric.mod.json` |
| `../solver-core` | the solver library `com.legoj15:solver`, a composite build (`settings.gradle`); Forge shades its classes (Forge 36 has no jar-in-jar), Fabric nests it (`include`). Its JUnit suite runs in its own build. See `solver-library.md` |

Version comes from the root `gradle.properties` (`version=`), read by `legacy-1.16.5/build.gradle`.
Pins live in `legacy-1.16.5/gradle.properties`: Forge 36.2.42, Fabric Loader 0.16.14, Fabric API
0.42.0+1.16, JEI 7.8.1.1018 (Forge only; JEI 1.16 has no Fabric build), Mod Menu 1.16.23.

Outputs: `forge/build/libs/sprawlcrafting-<ver>+mc1.16.5-forge.jar` (SRG, the release jar; the `-dev`
jar next to it is named and not for players) and `fabric/build/libs/sprawlcrafting-<ver>+mc1.16.5-fabric.jar`.

## Running it (Windows, PowerShell tool)

- `legacy-1.16.5\gw.ps1 :forge:build :fabric:build` — wrapper on JDK 21 (FG7/Loom need a modern host
  JDK; compile and dev runs use an auto-provisioned Java 8 toolchain). `gw.sh` is the Git Bash twin.
- `legacy-1.16.5\smoke.ps1 -Task :forge:runServer` (or `:fabric:runServer`) — writes `eula.txt` and an
  offline `server.properties` (port 25599) into `run-server/`, waits for `Done (`, kills the tree.
- `legacy-1.16.5\smoke.ps1 -Task :forge:runClient -Gradle '-Pjei'` (or `:fabric:runClient`) — waits for
  `Sound engine started` (title screen) and REQUIRES the mixin self-check line (below). `-Pjei` puts the
  full JEI in the Forge dev runtime.
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

## Networking invariants (same as every other target)

- Forge: `SimpleChannel` `sprawlcrafting:main`, ids 0 start craft (C2S), 1 screen state (C2S), 2 progress
  (S2C); both version predicates accept `ABSENT` and `ACCEPTVANILLA`; `canReceive` = `isRemotePresent`;
  DISPLAYTEST = `IGNORESERVERONLY`.
- Fabric: one play channel per packet id (`sprawlcrafting:start_craft`, `crafting_screen_state`,
  `craft_progress`); `ServerPlayNetworking.canSend` / `ClientPlayNetworking.canSend` guards.
- No command. Client offers are gated on `serverHasMod()`.

## Parity status

Ported: recipe-book deferred crafting (yellow slot via `@Redirect` of `RecipeWidget#blit` swapping the
u=29 cell for our 25x25 PNGs, two-click preview + ghost, right-click red -> gather screen, overlay
right-click), grid-fill final step, JEI "+" (yellow/orange, gather list, R/U on the gather screen),
config file + screen (Forge mod-list Config button, Fabric Mod Menu), toasts + step sound, networking.

Not ported / differs:
- No REI on Fabric 1.16.5 (the brief scoped JEI on Forge only); the REI toggle is absent.
- Fabric 1.16.5 has no JEI, so the Fabric jar has recipe-book integration only.
- Variant-overlay popup buttons stay vanilla-white (same accepted limitation as the modern nodes).
- Fabric reach check uses vanilla's fixed 64.0 sq; Forge uses `(REACH_DISTANCE + 3)^2` like
  `ServerPlayNetHandler#handleUseItemOn`.

Verified (2026-11-08): both builds, solver-core suite (43 tests) on Java 8 in both, Forge + Fabric
dedicated servers to `Done (`, Forge (+JEI) and Fabric clients to the title screen with the self-check
passing, jar refmaps (SRG / intermediary), every mixin target javap-verified. NOT yet: an in-world
playtest, a modless-connect test, or the Infinity Matter Dominator Sword stress pack.
