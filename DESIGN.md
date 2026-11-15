# SprawlCrafting — Design

Factorio-style deferred crafting for the vanilla inventory and crafting table: request a
final item, and the mod automatically crafts every intermediate from your raw resources,
one step every half second. Intermediates are crafted as **real items** into the
player's inventory and consumed by later steps, so a craft is crash-safe and visible to
other mods.

This document describes the current design across all shipped targets. The core model was
locked early (2026-06-09/06-10) and still holds; the architecture has since grown a shared
solver core, Stonecutter multi-versioning, JEI/REI integration, a config system, and a
separate 1.12.2 Forge port.

## Targets and toolchain

| Minecraft | Loaders | Build | JDK |
|---|---|---|---|
| 1.20.1 | Fabric, Forge | Stonecutter node | 21 host / Java 17 output |
| 1.21.1 | Fabric, NeoForge, Forge | Stonecutter node | 21 |
| 26.1.2 | Fabric, NeoForge, Forge | Stonecutter node | 25 |
| 26.2 | Fabric, NeoForge, Forge | Stonecutter node | 25 |
| 26.3 | Fabric, NeoForge, Forge | Stonecutter node | 25 |
| 1.12.2 | Forge | separate RFG build | 25 host / Java 8 output |
| 1.16.5 | Forge, Fabric | separate FG7 + Loom build (`legacy-1.16.5/`) | 21 host / Java 8 output |

The modern loaders (1.20.1 and up) share one source set via **Stonecutter**: `src/` is
preprocessed per node into `versions/<node>/`, one node per (Minecraft version, loader). Cross-version API
differences are handled inline with Stonecutter comment directives (e.g.
`//? if >=1.21.11 { … }`). The **1.12.2 Forge** target is a separate, self-contained Gradle
build (RetroFuturaGradle) because the legacy and modern toolchains can't coexist; see
[`forge-1.12.2/README.md`](forge-1.12.2/README.md). Both trees compile in the same
Minecraft-free planner (below).

## The Forge 1.21.1 node (`1.21.1-forge`)

MinecraftForge (not NeoForge) on 1.21.1, at feature parity with `1.21.1-neoforge`. The 1.20.1 and
26.x Forge nodes (below) reuse everything here except where noted:

- **Build:** `build.forge.gradle.kts` on ForgeGradle 7 (`net.minecraftforge.gradle`, plus
  `net.minecraftforge.jarjar`; both declared `apply false` in the controller and share the root
  classloader with Loom and ModDevGradle without conflict). Forge 1.21.1 runs Mojmap at runtime, so
  the shared source compiles as-is. Dev runs need `net.minecraftforge.gradle.merge-source-sets=true`
  in the ROOT `gradle.properties` (the plugin ignores it in a node's file), or FML sees the
  resources dir as a mod with no classes ("constructed 0 mods"). The shipped jar is the jar-in-jar
  output (`sprawlcrafting-<ver>+mc1.21.1-forge.jar`); the classes-only `-slim.jar` is skipped by
  `buildAndCollect`.
- **Source replacements** (the 26.x forward-port regexes) live once in the controller's
  `stonecutter parameters { replacements { … } }`, not in the per-node scripts.
- **Metadata:** `META-INF/mods.toml` (Forge) is a different file from `neoforge.mods.toml`; every
  node's script excludes the other loaders' metadata. Forge has no `[[mixins]]` block: the shared
  `sprawlcrafting.mixins.json` is named by the jar manifest `MixinConfigs` attribute (dev runs pass
  `--mixin.config=`). `displayTest = "IGNORE_ALL_VERSION"` keeps the server list free of red Xs.
- **MixinExtras:** Forge 1.21.1 does not bundle it (Fabric Loader and NeoForge do), and
  `RecipeButtonMixin` uses `@WrapOperation`, so the node jar-in-jars `mixinextras-forge`.
- **Networking:** one optional `ChannelBuilder.payloadChannel()` (`platform/forge/ForgeNetwork`) that
  accepts every protocol version, so it can never gate a connection. Payload type ids are the wire
  ids, identical to the other loaders. Forge's patched vanilla codec can only encode its own wrapper,
  so a raw `ClientboundCustomPayloadPacket(payload)` cannot be sent: all server-to-client sends go
  through `IPlatformHelper.sendToPlayer` (Fabric/NeoForge keep the plain vanilla packet there).
  `canReceive`/`canSendToServer` read the remote's registered channel list from `NetworkContext`.
  The payload channel registers every payload's type id as its own child channel
  (`PayloadChannel.Builder.add` -> `NetworkInstance.addChild`) and the peer advertises all of them in
  `forge:channel_registration`, so the check answers per payload: a peer built without
  `shortfall_v2` is sent the original `shortfall` payload.
- **Not on this node:** REI (no Forge 1.21.1 build) and Mod Menu (Fabric-only); excluded by leaving
  `rei_version` / `modmenu_version` unset. JEI works unchanged (`@JeiPlugin` is annotation-scanned).

## The Forge 1.20.1 node (`1.20.1-forge`)

MinecraftForge 47 on 1.20.1, at parity with `1.20.1-fabric` (minus REI and Mod Menu, as above; plus the Sophisticated
compat, which has no Fabric build). It is
the dev test bed for Forge-1.20.1-only mods. Same `build.forge.gradle.kts`; everything that differs is
behind its `srgRuntime` flag (`<1.20.6`), so the other Forge nodes' builds are untouched.

- **SRG at runtime.** Dev runs are Mojmap; production Forge 47 runs SRG (`m_`/`f_`). The script applies
  `net.minecraftforge.renamer` (1.1.8, `apply false` in the controller), runs the Mixin 0.8.5 AP and
  the MixinExtras AP (`@WrapOperation` entries; Mixin's AP does not know them) to write an SRG
  `sprawlcrafting.refmap.json`, and chains `jar` (`-slim`) -> `jarJar` (+ MixinExtras, `-dev`, Mojmap)
  -> `renameJarJar` (SRG, the shipped `sprawlcrafting-<ver>+mc1.20.1-forge.jar`). Renamer also renames
  the mixin classes' `@Shadow` members from the AP's generated mappings. Renamer adds a harmless
  `fernflower_abstract_parameter_names.txt` to the jar. The "Reference map could not be read" warning
  in dev runs is expected (dev uses the annotation names directly).
- **Mavenizer trap (first run per machine).** FG7's Mavenizer recompiles Forge with the first JDK 17 it
  finds (env `JAVA_HOME_17_X64`, then Gradle-provisioned JDKs, then install paths). JDK 17.0.1's javac
  rejects valid 1.20.1 records ("invalid accessor method in record"), so configuration fails; set
  `JAVA_HOME_17_X64` to a newer 17 (17.0.21 verified) for that one run. The output is cached in
  `~/.gradle/caches/minecraftforge`.
- **Dependencies.** JEI's Forge jars are SRG-published: `renamer.dependency(...)` renames them for
  compile and the dev runtime (with `accessTransformers` for JEI's AT). JEI 15.74 hard-depends on
  `mezz_config`, which it ships jar-in-jar; Renamer does not touch nested jars, so the dev runtime adds a
  renamed `mezz_config` (`mezz_config_version`), which Forge's JarJar selector prefers over the nested
  copy. Forge 47.4.29 does not bundle MixinExtras (installer library list), so it is jar-in-jar'd as on
  1.21.1. The Sophisticated Core / Storage / Backpacks jars (SRG too) go the same way, compile-only plus an
  opt-in dev runtime (`-Psc.sophisticated=true`); see "Sophisticated crafting upgrade".
- **Networking.** Forge 47 has no payload channels: one `SimpleChannel` `sprawlcrafting:main` (the same
  name as legacy 1.16.5), discriminators 0 start craft (C2S), 1 screen state (C2S), 2 progress (S2C),
  frozen (append only). Both version predicates accept every value, `ABSENT` and `ACCEPTVANILLA`
  included, so it is optional both ways. Presence is per channel on this line, so
  `canReceive`/`canSendToServer` are `isRemotePresent(connection)` and ignore the payload id.
- **Loader API branches** (`//? if >=1.20.5` in `platform/forge`): no-arg `@Mod` constructor with
  `FMLJavaModLoadingContext.get()`; `ModLoadingContext.get().registerExtensionPoint` for the config
  screen; tick events are single classes with `phase == END` instead of `.Post`;
  `player.connection.connection` instead of `getConnection()`.
- **Metadata.** `forge_version_range=[47.1.0,48)` (`displayTest` in mods.toml exists from 47.1.0, checked
  in fmlcore 47.1.0); the JEI dependency range is per node (`jei_version_range`, `[15,)` here).
- **Dev self-check.** `platform/forge/ForgeMixinSelfCheck` (every Forge node) force-loads every
  recipe-book mixin target at client start when `SC_MIXIN_SELFTEST=1`. `smoke.ps1 -Task
  :<node>:runClient` sets it and requires "mixin self-check passed"; `smoke.ps1 -Task :<node>:runServer`
  boots a dedicated server to `Done (`.

## The Forge 26.x nodes (`26.1.2-forge`, `26.2-forge`, `26.3-forge`)

MinecraftForge on the 26.x lines, at feature parity with the matching NeoForge nodes (server-driven
craftability sync, recipe-book mixins, config screen, toasts, gather screen) **minus JEI**: no Forge
build of JEI exists for 26.x. Same `build.forge.gradle.kts` and platform layer as 1.21.1; what differs
is decided by the node's `gradle.properties` plus `//? if >=1.21.11` branches in `platform/forge`:

- **Build:** ForgeGradle 7.0.40, Java 25 toolchain, Forge 64.1.6 / 65.1.6 / 66.1.5 (build pins; the
  shipped range starts at each line's X.1.0 "recommended" series). 26.x is unobfuscated, so
  the node runs plain Mojmap (`mappings("official", mc)`) and Parchment is not applied (no
  `parchment_*` props). The
  first Gradle configure of ANY invocation mavenizes (decompiles) every Forge MC version once, several
  minutes per new Minecraft version; afterwards "Minecraft Maven is up-to-date".
- **No JEI:** the node omits `jei_version`, which excludes `compat/jei` from compilation, adds no JEI to
  the dev runtime, and drops the optional `jei` dependency from `mods.toml` (`has_jei`).
- **No jar-in-jar:** Forge 26.x ships `mixinextras-forge` itself (it is a dependency of the Forge
  artifact), so these nodes omit `mixinextras_version`: no `jarJar` task, no `-slim` jar, the plain
  `jar` is the shipped jar.
- **Events (EventBus 7):** there is no `MinecraftForge.EVENT_BUS`; every event class carries its own
  static `BUS` (`TickEvent.ServerTickEvent.Post.BUS.addListener(...)`), and events are records
  (`event.server()`, not `getServer()`). `ModList` is a static facade (`ModList.isLoaded`).
- **Networking:** the `ChannelBuilder.named(..).payloadChannel()` flow builder is unchanged from 1.21.1
  (still `optional()`, accept-all version test, `canReceive`/`canSendToServer` via
  `NetworkContext.getRemoteChannels()`); `ForgeNetwork` additionally registers the 26.x
  server-driven recipe-book payloads (both shortfall versions included) behind a `//? if >=1.21.11`
  directive, mirroring `NeoForgeMod.onRegisterPayloads`.
- **Metadata:** `loaderVersion` is the Forge major (`[64,)` / `[65,)` / `[66,)`; the javafml provider
  ships with the Forge version). `displayTest = "IGNORE_ALL_VERSION"` is still honoured.
- **Mixin:** Forge bundles Mixin 0.8.7, whose newest compatibility level is `JAVA_21`, so loading our
  Java-25-compiled mixin classes logs "Class version 69 required is higher than ... JAVA_21 supports
  class version 65" per mixin class. It is only a warning; the mixins apply.
- **Dev-run caveat:** a Forge dev client connecting to a non-Forge server crashes in `Level.guardEntityTick`
  ("Cannot get config value before config is loaded", a dev-only strictness in `ForgeConfigSpec`, no mod
  frames). Quick-play a singleplayer world (`-Psc.world=<save>`) or connect to a Forge dev server.

## Core model

*(originally locked 2026-06-09/06-10, still in force)*

- **Intermediates are real items.** Each sub-craft's output goes into the player's inventory
  (dropped at their feet if full) and is consumed by later steps. Crash-safe, visible to
  other mods, and an item going missing mid-craft cancels the job.
- **Per-step consumption from the live inventory.** Nothing is reserved upfront; a step
  re-checks and consumes its ingredients when it comes due. If they're gone, the job fails
  gracefully — already-crafted items remain.
- **One job per player at a time.** Requesting a second craft while one runs is rejected.
- **Cadence: paced for ordinary steps, bursts for batched ones** — a step of at most
  `CraftJob.BULK_THRESHOLD` (16) crafts keeps the Factorio-style rhythm: one craft per
  `CraftJob.TICKS_PER_STEP` (10 ticks, 0.5 s), each with its own progress update and step sound. A
  bigger step (a batched compressed-block chain, e.g. 6,561 crafts) waits `TICKS_PER_STEP` once,
  then runs one burst per tick until it is done. A burst stops at
  `CraftJob.MAX_CRAFTS_PER_TICK` (64, one stack of a single-output recipe) or its time budget
  (below), whichever comes first, and always does at least one craft; it takes ~5-16 s instead of
  ~55 minutes at one craft per half second. Every craft in a burst is still validated and applied on its own
  (`CraftExecutor.craftOnce`), so per-craft ingredient checks and graceful cancellation are
  unchanged. **Measured** (1.21.1 NeoForge game-test server, mock player, 9-ingredient
  `iron_block` recipe with the ingots behind 27 filler stacks, i.e. the worst slot-scan layout): a
  64-craft burst took 15.4 ms cold, then 2.5-6.1 ms warm (~40-95 µs per craft). Uncapped, one
  player's burst could take a third of a 50 ms tick, hence the time budget: 2 ms is 4% of a tick,
  so a cheap recipe still runs the full 64 and a costly one ~20-50.
  **The time budget is shared across players.** `CraftJob.BURST_BUDGET_NANOS` (2 ms) caps one
  player's burst; `CraftJob.SERVER_TICK_BURST_BUDGET_NANOS` (10 ms, a fifth of a tick) is what ALL
  players' bursts together may spend in one server tick, so N players bursting at once cost about
  10 ms rather than N x 2 ms. `BurstBudget` (held by `CraftQueueManager`) splits it evenly: the first
  bulk burst of a server tick opens the window and counts the jobs that burst this tick
  (`CraftJob.burstsThisTick()`: a bulk step whose half-second wait is up, so a job paused for a
  crafting table or waiting out a step boundary does not shrink the others' slice), and every
  burst of that tick is granted `min(2 ms, 10 ms / jobs)` (up to five bulk jobs keep the full 2 ms; 20
  get 0.5 ms each), whatever their position in the player loop. An even slice rather than a shared
  first-come-first-served pool, because the loop order is fixed by the player list and the first few
  players would otherwise take the whole budget every tick; the same slice for everyone also means
  nothing has to rotate. Each burst is charged the time it used, clamped to its grant: the clock
  is checked after a craft, so a burst runs up to one craft past its grant, and charging that
  overshoot would shrink every later player's slice in the fixed loop order (the last pinned to
  one craft per tick); time a burst leaves unused (64-craft cap, step boundary) stays in the
  window for a burst the count missed, and the time charged to the window never exceeds 10 ms.
  The clock is checked after a craft, never before, so every job makes at least one craft per
  eligible tick even with the budget spent (no starvation); the bound is therefore soft (the budget plus at most one
  craft of overshoot per burst), and past roughly 100-200 simultaneous bulk jobs the one-craft floor,
  not the 10 ms, sets the cost. Paced steps do one craft and never draw on it. The window is keyed on
  the SERVER tick (`MinecraftServer.getTickCount()`; `getTickCounter()` on 1.12.2), never
  `player.tickCount`, which is per-entity and restarts at 0 on respawn (the wedge `DeferredCraftSync`
  hit); any change of key, including a clock that goes backwards, opens a fresh window. The three
  trees (`src/`, `legacy-1.16.5/common`, `forge-1.12.2`) each carry the same class and constants.
  Full-grid steps run the full
  crafting-table scan once per half second (the scan is the expensive part); the burst ticks between
  scans re-validate only the table that scan found (`CraftingTableReach.isStillInReach`: chunk
  loaded, block still a table, still within reach) and pause the job the moment that fails, so a
  player who walks away stops a burst within a tick. A player with a 3×3 crafting grid open skips
  both (see "Grid gating"). A bulk step's mid-step progress is synced at
  most once per half second, so the toast's step sound keeps its cadence (no 6,561 pops); a paced
  step syncs after every craft, and step completions and terminal states sync immediately.
- **Deterministic recipe ambiguity.** When several recipes make a needed intermediate, pick
  the first whose raw cost is currently satisfiable, preferring fewer total steps. Planning
  is depth-limited and cycle-guarded (e.g. iron ingot ⇄ block). No player prompt.
- **Crafting recipes only.** Smelting / smithing / stonecutting aren't planned as sub-recipes.

## The shared solver (`solver-core/`)

The planning brain is a pure, **Minecraft-free** Java library in `solver-core/`
(`com.legoj15:solver`, package `com.legoj15.solver`: `RecipeGraphSolver` + `PlannedStep`). It is
its own Gradle build (Java 8 bytecode, published to Maven Local / JitPack), and every build — the
modern Stonecutter nodes, legacy-1.16.5 and the 1.12.2 jar — depends on it through a composite
build and bundles it: jar-in-jar on Fabric, NeoForge and JarJar-era Forge, shaded on Forge 1.16.5
and 1.12.2. Its JUnit suite runs in its own build on a Java 8 runtime. A second mod can depend on
the same library; see [`docs/solver-library.md`](docs/solver-library.md).

Each loader wraps it with a `CraftPlanner` that adapts Minecraft recipes and a player
inventory onto the solver:

- **Server:** plans authoritatively from the real `ServerPlayer` state.
- **Client (1.21.1 / 1.12.2):** opens a planning `Session` over the client's synced recipe
  manager + inventory mirror, computing yellow-outline solvability and click previews with
  **no server round-trips**.

The solver is greedy with a bounded backtracking budget (`MAX_DEPTH`, `MAX_ATTEMPTS`) rather
than a full exponential search — see "Known limitations".

## Core flow

1. **Trigger (client).** In the recipe book / JEI / REI, a recipe whose direct ingredients
   are missing but whose *raw* cost is satisfiable gets a **yellow** outline (vanilla: white
   = craftable now, red = missing). Acting on it sends a "request deferred craft" packet
   instead of failing.
2. **Planning (server, `CraftPlanner` → `RecipeGraphSolver`).** Walk the recipe dependency
   tree against the player's inventory; emit a topologically ordered list of steps
   (recipe × count). Held items are used before any sub-craft is planned.
3. **Execution (server, `CraftQueueManager` / `CraftExecutor`).** Each step starts 10 ticks
   after the previous one; a step of ≤ 16 crafts then runs one craft per 10 ticks, a bigger one in
   bursts (≤ 64 crafts per tick, within a 10 ms per-tick time budget shared by all players); every craft re-checks and
   consumes its ingredients from the live inventory and inserts its output as real items.
   Shortfall → cancel + notify.
4. **Progress toast (client).** While a job runs with the inventory closed, a vanilla toast
   (persistent per job, progress bar, state colors) shows the current item and overall
   progress, alongside a per-step craft sound.

## Grid gating and the crafting table

- **Whole-chain gating at request time.** Every step — intermediates and final — must fit
  the grid the request came from (`GridContext`): the inventory recipe book plans **2×2**
  chains only; a crafting table plans the full **3×3**. A 3×3-only recipe simply isn't
  offered for a 2×2 request.
- **Grid-size detection.** `GridContext` decides 2×2 vs 3×3 from the open menu's
  crafting-grid size rather than a hardcoded table class, so a modded recipe-book menu that
  exposes a 3×3 grid plans correctly. On 1.12.2 (no generic grid-size accessor) this is done
  by inspecting the container's crafting matrix, which additionally recognizes modded
  stations like the Tinkers' Construct Crafting Station and its slab-mod variants.
- **Proximity pause at execution time.** Steps that need the full grid only run while a
  crafting table is within the player's *block-interaction reach* — the attribute-driven
  distance (`blockInteractionRange()` / vanilla `canInteractWithBlock`), so reach-extending
  gear and mods are honored. Out of range, the job pauses (a Factorio assembler waiting on
  input) and auto-resumes; 2×2 steps craft anywhere. The reach scan skips empty chunk
  sections via the block palette, so a 64-block reach doesn't cost millions of lookups.
- **An open 3×3 grid is the table.** A player whose open menu has a 3×3 (or larger) crafting grid
  passes the gate without the scan, and the per-tick burst re-check passes for as long as that menu
  stays open (`CraftQueueManager.tick`, via `CraftingGrids`). For the vanilla table the only
  change is the reach band: vanilla keeps a table's menu open out to `canInteractWithBlock(pos, 4.0)`
  (`stillValid`), wider than the scan's 1.0 padding, so with the table GUI open a job keeps crafting
  between roughly 5.5 and 8.5 blocks (default reach) where it used to pause; for a modded menu with a 3×3 grid
  and no `minecraft:crafting_table` block, and for the Sophisticated crafting upgrade, it makes the
  open screen a portable table. Close the screen and the normal scan applies again, so the job pauses
  unless a vanilla table is in reach.
- **`CraftingGrids` is the one place that resolves a menu's crafting grid** (width, height, the
  ordered input slots, the result slot, whether vanilla recipe-book placement applies, whether the
  grid is on screen). Grid-size detection (`GridContext.current`), the 26.x craftability sync gate,
  the client's open-grid report, clearing the grid, the final-step hand-off and the execution gate
  all ask it, instead of testing `RecipeBookMenu` / `AbstractCraftingMenu` and assuming the inputs are
  menu slots 1..w*h. Vanilla is the built-in resolver (inputs 1..w*h, result 0, 2×2 and up, so a
  furnace menu is not a grid); compat code registers a provider at mod init, only when its mod is
  loaded.
- **Stations count as a table.** A usable crafting station (below) is treated as an open 3x3
  grid by both `GridContext` and the proximity pause, wherever the player stands.

## Crafting stations: the AE2 Crafting Terminal (issue #2)

Rule: every item a craft draws from must be visible in the open screen. The AE2 Crafting
Terminal shows its ME network, so while one is open **and usable** (link connected / powered)
the network's plain items count as craft material. Nothing off-screen counts: no plain ME
terminal, no adjacent storage. Shipped on `1.21.1-neoforge` (AE2 19.x), `1.20.1-forge` (AE2 15.x,
#27) and 1.12.2 (rv6 and AE2-UEL, see the 1.12.2 section); other lines are follow-ups #27
(1.20.1-fabric), #28 (26.x; the `DeferredCraftSync` fold is in since #33, only the AE2 provider is
left), #29 (1.16.5). The Sophisticated crafting upgrade is a second provider (see "Sophisticated
crafting upgrade").

- **Seam.** Shared code talks only to `craft/StationStorage` (counts, extract, insert) through
  `craft/StationStorages`; the AE2 provider lives in `compat/ae2`, compiled only on nodes that
  set `ae2_version` and loaded reflectively after `isModLoaded("ae2")`.
- **Classification and planning.** The client reads the terminal's client repo (no energy
  view); the server counts what a simulated powered extraction would deliver. A plan uses the
  inventory alone first and only falls back to the network when that fails.
- **Execution.** Network items are drawn all-or-nothing per craft (`StationDraw`, unit-tested):
  a short or throwing draw gives everything back (network, then inventory, then feet). Results
  and remainders go to the inventory; overflow goes into the network before the feet. The
  terminal counts as a 3x3 grid while usable: its grid is a registered `CraftingGrids` provider
  for as long as the terminal is usable, so the final-step hand-off lays the last craft into it
  (through `Slot.set`; the terminal has no recipe book), drawing from the network what the
  inventory lacks, and job start clears the terminal grid into the inventory like any grid.
- **Lifecycle.** A job is station-dependent when its plan needed the network, or once a craft
  drew from it or stored overflow in it. Such a job pauses (`PAUSED_STATION`, "Paused — open
  station") when the station is closed or unusable; a genuine shortfall while usable cancels.
  `PAUSED_STATION` is sent only to clients registering the `progress_states_v2` marker; older
  clients get `PAUSED`.
- **Entry points.** The terminal has no recipe book. JEI (AE2 19.x has no JEI module): green and
  yellow start the engine, red opens the gather list. REI: only yellow and red-gather are ours;
  green falls through to AE2's own grid fill. An offline terminal classifies as the 2x2
  inventory (JEI) or is left to AE2 entirely (REI). AE2 15.x (1.20.1) ships its own JEI handler:
  ours registers after it (optional `AFTER ae2` in mods.toml; JEI keeps the last per menu class)
  and fronts it like REI does: yellow and red-gather are ours, green, an offline terminal and red
  with the needs helper off go to AE2's (`compat/ae2/jei/Ae2JeiStock`). The busy gate applies to
  green too. AE2 15 has no link status: "usable" is its power check (`updatePowerStatus` mirror;
  a wireless terminal without an actionable node is unusable), and "plain" is the exact key of a
  fresh stack (`canonical`: a new damageable item already carries `Damage:0`), since a tag-less key
  can still carry capability data.
- **Accepted limits.** A connected but power-starved network can still cancel a job (counts are
  simulated per item, not in aggregate; no item loss). Items in the terminal's own 3x3 grid are
  not counted. A paused job has no player cancel yet (#31). A newer 1.21.1 client on an older
  SprawlCrafting server counts the network client-side and offers crafts the server then refuses
  with a message (no client-visible server capability exists for it; harmless).

## Client craftability across versions

Whether the client can compute "is this yellow?" itself depends on the version:

- **1.21.1 & 1.12.2:** the client reads recipes, so solvability and previews are computed
  **client-side** with no round-trip.
- **26.x:** the client **can't read recipes**, so the server computes craftability and
  **syncs it** to the client (`DeferredCraftSync`) to keep feature parity. This path is
  gated so it doesn't regress 1.21.1's local solving. (The recipe book only repaints
  craftability during its `selectRecipes` pass, so the sync nudges `recipesUpdated()` to
  force a repaint.)

## Triggers and player-facing helpers

- **Recipe book.** Yellow outline on raws-craftable recipes; a first click previews the
  plan, a second confirms and sends the start packet (the server re-plans authoritatively).
  Yellow recipes are included in the book's "show craftable" filter and carry tooltip hints.
- **JEI / REI.** The "+" transfer button routes a deferred-craftable recipe into the engine;
  colored buttons (yellow = deferred, orange = unsolvable, white = plain move) and red
  missing-slot highlights mirror the recipe book. REI is 1.21.1 only. JEI also covers the
  Sophisticated crafting upgrade (below).
- **Needs / gather screen.** Right-clicking a red recipe or the orange JEI/REI button opens
  a list of the raw materials still needed to make it. Gated by the `needs_system` config.
- **Final-step grid hand-off.** If the player leaves the crafting GUI open, the last craft
  is laid into the open grid as a real, takeable result — matching what vanilla "+"/
  recipe-book placement would do — rather than auto-inserting to the inventory. A crafting-upgrade
  grid gets the same hand-off while its tab is open (laid out through `Slot.set`, since no
  `handlePlacement` exists there); behind a closed tab the last craft goes to the inventory. An
  AE2 Crafting Terminal's grid gets it too while the terminal is usable, drawing from its network
  what the inventory lacks (see "Crafting stations").
- **Config.** `sound_effects`, `jei_integration`, `rei_integration`, `needs_system`, all
  defaulting on; a client config file plus an in-game screen (Mod Menu on Fabric, the
  mod-config screen on NeoForge, Forge's mod-list Config button on 1.12.2 — which drops the
  REI toggle).

## Sophisticated crafting upgrade (Storage / Backpacks)

The crafting upgrade of Sophisticated Core (shown in Sophisticated Storage block screens and Sophisticated
Backpacks screens) is a 3×3 crafting grid, and SprawlCrafting treats it like a crafting table: JEI's "+"
on a deferred-craftable recipe there is the yellow deferred-craft button (the orange gather-list button
works too), the plan is the 3×3 one, a 3×3-only step runs while the screen is open with no crafting table
anywhere, and the last craft is handed to the upgrade's grid.

- **Optional and compile-time isolated.** Sophisticated is a soft dependency: SprawlCrafting loads, runs and
  connects without it, adds no network payload, and no class that names a Sophisticated type loads unless
  `sophisticatedcore` is. The code is the package `compat/sophisticated/`, compiled only on nodes whose
  `gradle.properties` pin `sophisticated_core_version` (+ `sophisticated_storage_version`,
  `sophisticated_backpacks_version`; Modrinth Maven, group `maven.modrinth`, artifact = project id, all
  compile-only). The shared call sites (`CommonClass.init`, `SprawlCraftingJeiPlugin`) sit behind the
  `sophisticated` Stonecutter constant, which the controller derives from the same rule (a node pinning all
  three; `build.neoforge.gradle.kts` and `build.forge.gradle.kts` read the same pins and exclude the package
  without them, the Fabric scripts always exclude it), and a `sophisticatedcore` loaded check (in
  `CommonClass`, and inside `SophisticatedJei` for the JEI plugin). `-Psc.sophisticated=true` puts Core,
  Storage and Backpacks on a node's dev runtime (on the NeoForge nodes with the JEI build in
  `sophisticated_jei_version`: Core wants a newer JEI than the compile pin). Not on `26.2-neoforge` as
  pinned: Core 26.2 needs a newer NeoForge and JEI than that node's build pins.
- **Nodes.** Official Sophisticated builds are NeoForge-only on 1.21.1 and 26.x, and one build per mod on
  1.20.1 that serves Forge and NeoForge alike, so the compat is on `1.21.1-neoforge`,
  `26.1.2` / `26.2` / `26.3-neoforge` and `1.20.1-forge`. Fabric has no official build; the Forge 1.21.1
  and 26.x lines have none either. The pins are build pins, like JEI's.
- **1.20.1 Forge.** The 1.20.1 jars (Core `1.20.1-1.5.33.2475`, Storage `1.20.1-1.5.17.2230`, Backpacks
  `1.20.1-3.26.20.2248`) are SRG-named (`m_`/`f_`), so `build.forge.gradle.kts` consumes them the way it
  consumes JEI there: one `renamer.dependency(...)` per jar (Mojmap names for compile and the dev runtime, with
  the jar's access transformer renamed too so the dev runtime applies it), and the shipped jar's references to Sophisticated and Minecraft members come out SRG from
  Renamer like the rest of the jar (Sophisticated's own members, such as `getOpenContainer`, carry no
  obfuscated name and stay as written). The mods' `mods.toml` declare nothing beyond Forge, Core and (optional)
  JEI 15.20+, which this node's JEI pin meets. The API differs from 1.21.1 in one way the compat sees: JEI 15's
  recipe type is the bare `CraftingRecipe` and Core's JEI handler base is typed on it, so the handler classes
  carry `//? if >=1.20.2` branches and wrap the recipe in the `RecipeHolder` shim for `DeferredTransfers`
  (the same split `DeferredCraftingTransferHandler` has); the upgrade has no size accessor (always 3×3), like
  1.21.1. Core's own mixins load in the dev runtime (Mixin remaps their SRG refmap to dev names; seen in
  the dev-client boot).
- **Grid.** `SophisticatedGrids` is the `CraftingGrids` provider: for a `StorageContainerMenuBase` it takes
  `getOpenOrFirstCraftingContainer(CRAFTING)` (the same call the mods' own JEI handler uses), whose
  `getRecipeSlots()` are the nine inputs and whose last slot is the result. Those slots are not in
  `menu.slots` (Sophisticated keeps upgrade slots in a separate list), which is why every executor access
  goes through the slot list, never `menu.getSlot(1 + i)`. The grid counts as 3×3 whenever a crafting
  upgrade is installed (the client reports it on screen open, like a table); it counts as *visible*, and
  so receives the final hand-off, only while the upgrade's tab is open (`StorageContainerMenuBase.getOpenContainer()`; `UpgradeContainerBase.isOpen()` is set once at menu construction on the server and goes stale).
  Planning, the 26.x sync and the execution gate ignore the tab (a closed tab does not change reach); only
  the hand-off checks it. Writes go through `Slot.set`, whose `setChanged` recomputes the result; `broadcastFullState` then
  pushes the grid and result in one packet.
- **JEI.** JEI keeps one transfer handler per (menu class, recipe type), the last registered wins, in
  mod-list order. Storage (`StorageContainerMenu`) and Backpacks (`BackpackContainer`) each register a
  handler built on Core's `JeiCraftingContainerRecipeTransferHandlerBase`; we register our own for the same
  classes after them (optional `AFTER` dependencies on `sophisticatedstorage` / `sophisticatedbackpacks` in
  `neoforge.mods.toml`, and in `mods.toml` where `has_sophisticated` is set, client side only), wrapping an
  identical instance of that Core handler. Direct transfers are therefore exactly the mods' own (their packet,
  their rules); a user-facing "missing items" error becomes the yellow / orange state through `DeferredTransfers`, the same decision the vanilla table
  handler makes. A menu with no crafting upgrade gets the mod's internal error back, passed through.
- **Source of ingredients.** The mods' direct JEI transfer draws from the open storage's contents, and since
  #33 so does a deferred craft (see "Ingredient sourcing" below).
- **Verification.** In-game on `1.21.1-neoforge` (Storage barrel and Backpack: yellow button, direct transfer
  untouched, gather list, 3×3 step with no table in reach and the pause when the screen is closed, hand-off
  into the grid, inventory fallback with the tab closed, no Sophisticated on the runtime) and `26.1.2-neoforge`
  (the server-synced yellow state, hand-off, gather list), and `1.20.1-forge` (dev client with JEI 15.74: barrel
  and backpack yellow button, direct transfer untouched, gather list, 3×3 step with no table in reach and the
  pause when the screen is closed, hand-off into the grid, inventory fallback with the tab closed, clearing a
  stray grid item, and a boot without Sophisticated). The shipped SRG jar was also checked without a bridge:
  `javap` on the compat classes, a static linkage pass of every reference in the jar against a Forge 47
  production classpath with the Sophisticated and JEI 15.56 jars, and an offline boot of a real Forge 47.4.29
  client with the player's mod list (init, JEI registration, no linkage error); clicking through the screens
  was dev-client only. `26.2` / `26.3-neoforge` are compile-checked only. The procedure and the traps are in
  [`docs/sophisticated-check.md`](docs/sophisticated-check.md).
- **Not done:** REI and EMI (Sophisticated ships handlers for both; neither is wrapped here); and a
  Sophisticated storage block within reach acting as a table while its screen is closed.
- **Shared storage, two viewers (unverified edge).** Clearing the grid on a deferred request and the hand-off
  act on the upgrade's slots; if two players have the same storage open and its upgrade inventory is shared,
  one player's request moves the other's grid items into the requester's inventory (moved once, never
  duplicated). Accepted; not exercised in game.
- **Ingredient sourcing (issue #33).** While a Sophisticated screen with a crafting upgrade is open, its
  storage is a crafting station (`compat/sophisticated/SophisticatedStation`, the #2 seam): the planner,
  executor, gather list and the 26.x server sync count its plain items, inventory first. Gate: a crafting
  upgrade must resolve (`CraftingGrids.isFullGrid`), else the storage does not count, since a usable
  station makes `GridContext.current` claim a 3×3. `isStationMenu` stays false: the upgrade is a
  `CraftingGrids` grid with the normal hand-off and its own JEI handler. Draws and inserts go slot by
  slot, because Core's whole-handler extract asks the slot tracker and `ISlotTracker.Noop` returns
  nothing. The final-grid hand-off draws from the storage too (since #27's station-aware
  `placeFromLedger`): inventory first, then the storage for what it lacks, all-or-nothing with give-back on
  failure, as for the AE2 terminal. Draws loop per slot (one
  1.20.1/1.21.1 `extractItem` call yields at most a vanilla stack, though a stack-upgraded slot holds
  more), and blocked slots (`isSlotAccessible`) neither count nor give. Overflow offered to the storage
  obeys its upgrades: a Void upgrade set to void overflow of that item voids it, as it would a
  shift-click; that is the player's setting, accepted. Not playtested in game yet.

## The 1.12.2 (Forge) port

A full port of the engine onto 1.12.2's APIs on top of the same `solver-core`, at feature
parity with the modern loaders. It lives in its own build for the toolchain reasons above.
Design points specific to it:

- **Item identity is `(Item, metadata)`** (`ItemKey`), not `Item`: 1.12.2 packs distinct
  craftables into one `Item` via metadata (all six planks are `minecraft:planks`).
- **Requires MixinBooter (client).** It provides the SpongePowered Mixin runtime and loads
  SprawlCrafting's recipe-book / JEI mixins from the jar's `MixinConfigs` manifest entry.
  Without it those mixins silently no-op, so the client hard-stops at a friendly error
  screen. A dedicated server doesn't need it.
- **Networking** is a `SimpleNetworkWrapper` channel; grid-hand-off and screen-state
  messages ride a **second** channel (`sprawlcrafting2`) behind a hello handshake, so mixed
  old/new client and server versions can never desync or kick. The main channel's message
  ids are frozen.
- **Chest sourcing.** Grid placement can also draw from a modded station's bound chest (e.g.
  a TConstruct Crafting Station), matching those stations' own JEI behavior.
- **AE2 Crafting Terminal (issue #2).** Rule: every item a craft draws from must be visible
  in the open screen. While an AE2 Crafting Terminal (`ContainerCraftingTerm` or a subclass;
  rv6 and the UEL fork alike) is open, its ME network is material after the player's 36
  slots: planning, the client highlight cache and execution count it through one core seam
  (`craft/NetworkTerminals`, provider in `compat/ae2`, loaded only when AE2 is). The
  terminal counts as an open 3x3 crafter but contributes no slot material (its output and
  view-cell slots would be harvested otherwise). Network picks are extracted all-or-nothing
  before a craft consumes anything; overflow that would drop goes into the network. The
  grid placer and final-step hand-off refuse at the terminal (the last craft lands in the
  inventory). JEI: direct recipes keep AE2's own "+", deferred go to the engine, unsolvable
  to the gather screen; a server without the network capability (hello capability byte)
  gets AE2's handler for everything. A job that uses the network (its plan needed it — planning is
  inventory-first — or a craft drew from or stored overflow in it) pauses ("open station") when a later step needs network items while the
  terminal is closed or unpowered; steps that can run from the inventory keep running. A
  chest-dependent and a network-dependent job are tracked separately, so one source being
  visible never cancels a job waiting on the other. On 1.12.2 wireless crafting terminals are out
  (different container families there); on 1.21.1 AE2's `WirelessCraftingTermMenu` subclasses
  the crafting-terminal menu and counts.
- **One deliberate cosmetic cut:** the recipe book's variant-overlay ("alternates") bubble
  isn't given the yellow deferred styling — a fragile SRG-named inner-class hook for a
  rarely-seen bit of UI.

## Multiplayer and compatibility

Install on both client and server. The mod **never gates server access on its own
presence**: payloads are registered optional and every send is channel-guarded (1.12.2 uses
`acceptableRemoteVersions = "*"`), so:

- a modless / vanilla client can join a SprawlCrafting server (it just gets no offers or
  toast), and
- a SprawlCrafting client on a vanilla server sees no yellow offers (its start packet is
  ignored).

The server only sends in response to a client-initiated craft, so a peer lacking the mod
never receives an unsolicited packet.

### Big counts: formatting and wire versioning

Batched plans make gather lists of 4e10 .. 2.7e14 items normal, and the top tiers of
compression mods saturate at `Long.MAX_VALUE`. Counts are therefore `long` from the solver
(`ShortfallResult.demandsLong()` + `saturated()`) through every tree's `ShortfallView` /
`ItemDemand` to the gather screen.

- **One formatter, every tree:** `solver-core`'s `CountFormat` (Minecraft-free, Java 8).
  `format`: `1,234` below 10,000, then three significant digits with a suffix (`12.3K`,
  `4.56M`, `7.89B`, `1.23T`, `4.56Q`), scientific from 10^18 (`9.22e18`). `compact` (≤ 4
  chars, item-slot overlays): `999`, `1.2K`, `12K`, `9e18`; a saturated count gets a fifth char,
  `9e18+`, so it never reads as an exact figure. It never uses `String.format` /
  `NumberFormat`, so output is identical in every JVM locale and a server-built string reads the
  same on every client. A saturated count reads "more than 9.22e18" via the
  `sprawlcrafting.gather.more_than` lang key (in every tree's lang file); the tooltip's item
  lines use `sprawlcrafting.gather.line_more_than` ("More than 9.22e18 Iron Ingot") instead of
  `gather.line` ("3× Iron Ingot"), since "more than 9.22e18× Iron Ingot" reads as a multiplier. The gather screen
  (slot overlay + hover tooltip), the JEI gather tooltip (REI's shows no counts), the plan
  preview, the "Crafting X - N component steps" chat line and the progress toast all use it.
- **26.x wire (`ShortfallPayload`):** its layout (VarInt counts, no saturated flag) is frozen —
  a mod-1.0.3 peer decodes it. Long counts travel on a **new payload id**,
  `sprawlcrafting:shortfall_v2` (`ShortfallV2Payload`: same fields + `saturated`, VarLong
  counts). The server sends V2 when `canReceive(player, ShortfallV2Payload.ID)`, else the old
  payload with counts clamped to `Integer.MAX_VALUE`, else nothing; a new client registers
  receivers for both, so it also works against a 1.0.3 server. Both are registered optional on
  Fabric and NeoForge, so neither gates a connection. (Forge 1.21.1 carries no shortfall payload:
  the gather list is client-side there. A future Forge 26.x node must register both ids.)
- **Progress (`CraftProgressPayload`, 1.12.2 `CraftProgressMessage`, 1.16.5 packet):** stays
  int on the wire. `CraftJob` counts in saturating `long`s and `wireDone()`/`wireTotal()`
  scale a job of more than `Integer.MAX_VALUE` crafts down proportionally, so the bar's ratio
  holds and nothing goes negative.
- **1.12.2 / 1.16.5 / 1.20.1 / 1.21.1:** the gather list is computed on the client, so no
  count crosses the wire and the frozen 1.12.2 main channel (ids 0-2) is untouched.

## Module layout

- `src/` — modern shared gameplay logic (`craft/` package), recipe-book / JEI / REI mixins,
  toast rendering, networking; preprocessed per node into `versions/<node>/` by Stonecutter
  (`stonecutter.gradle.kts`, `build.<loader>.gradle.kts`); loader code in `platform/{fabric,neoforge,forge}`;
  optional-mod compat in `compat/{jei,rei,sophisticated}`.
- `solver-core/` — the Minecraft-free planner library (`com.legoj15:solver`) + its JUnit suite, its
  own build; every other build depends on it via a composite build. See
  [`docs/solver-library.md`](docs/solver-library.md).
- `forge-1.12.2/` — the separate legacy Forge build (RFG); shades the solver library.
- `legacy-1.16.5/` — the separate 1.16.5 Forge + Fabric build (shared `common/` source, Java 8);
  shades (Forge) / nests (Fabric) the solver library. Agent notes: [`docs/legacy-1.16.5.md`](docs/legacy-1.16.5.md).
- `release/` — the publish pipeline (GitHub / Modrinth / CurseForge / `update.json`).
- `testing/` — the boot-and-connect verification matrix per node.
- `branding/` — mod logo and icon art.

## Known limitations (reviewed and accepted)

**Planning**

- **One slot-filling rule, no look-ahead.** Which stock fills which slot of a craft is decided by
  `SlotAssignment` (solver-core): most constrained slot first, non-scarce alternative first, then
  bipartite repair. Every executor (`CraftExecutor.assign` in all three trees, so also the grid
  hand-off's ledger path) prefers the stack that rule names, and `solve` only returns plans that
  `PlanSimulator` replays craft by craft under it (#10, #18), so a plan never depends on inventory
  layout. The rule sees one craft at a time: a sub-craft whose slot lists an item first that a later
  step needs (and holds the only copy of) takes it, and such a target is reported unsolvable instead
  of stalling mid-job. Carrying a per-step slot choice in the plan would lift that; not done.
  Full search is exponential — rejected.
- **Search budget instead of memoization.** The solver plans in batches: identical slots are
  merged and quantities carried, so a recipe is resolved once per batch size rather than once
  per unit, and a `PlannedStep` can hold thousands of crafts. `MAX_ATTEMPTS` counts *batched
  resolver visits* (one recipe resolved for one batch size) per planning pass. `solve` may run
  three passes — the batched pass, then the per-unit (slot-by-slot) one, then, only when a plan
  failed replay or a pass came up short on an item another slot group took, an exact pass that
  applies the replay as it plans; each pass gets its own budget. The replay is O(steps x slots)
  (a batch is applied in chunks over which the rule's choice cannot change). `shortfall` walks quantities with saturating `long` counts (pinned at
  `Long.MAX_VALUE`, reported via `saturated()`), gets `MAX_ATTEMPTS` search visits plus as many
  expansion visits, then verifies its own list against the planner (a `solve`-sized run per
  check, with a couple of top-up rounds if the planner rejects it). Pathological graphs (the
  16-color bed cycle with all dyes stocked but no wool/planks, or hostile datapacks) abort
  with a "too complex" message, or an "approximate" gather list, when the budget runs out
  rather than hanging the server thread.
- **Planned by item identity.** Components / NBT are ignored at plan time; execution
  re-checks with `Ingredient.test` and cancels gracefully on divergence. Damaged, enchanted,
  and renamed stacks are excluded from both planning and consumption (recipe-book parity),
  except that execution may consume such a stack up to the count the running job itself crafted
  of that item (`ProducedLedger`), because some mods alter an item right after it is inserted.
  Rejected copies the pool held before the job produced anything are the player's own: the
  ledger records that count and `LedgerTally` admits only rejected copies above it. The executor
  draws the count down on its own takes: every rejected take, plus plain takes beyond the job's
  rejected copies still held (the count kept back can only describe those). Altered copies that
  enter the pool only after the job's first production cannot be told apart from the job's and
  stay bounded by the produced count. The vanilla final hand-off is exempt because it never takes
  rejected stacks and ends the job.
- **Per-item crafting remainders.** Recipes overriding `getRemainingItems` (and NeoForge's
  stack-sensitive remainders on modded items) are approximated at the item level.
- **NeoForge empty-tag placeholder.** Ingredients with empty tags fail at execution
  (gracefully) rather than at planning.

**UI**

- **Variant-overlay popup** buttons render vanilla-white, not yellow (restyling
  JEI-internal / recipe-book overlay buttons isn't API-exposed). The main grid buttons color
  correctly.
- **Modded crafting-table reach.** Grid *size* detection is generic, and a player with any 3×3 grid
  open passes the execution gate (see "Grid gating"), but the proximity scan used when no such menu
  is open looks for a vanilla `minecraft:crafting_table` block. So a full-grid job pauses with the
  screen closed next to a *modded-only* table (or a Sophisticated storage), and resumes when the
  screen is reopened. Tracked for a future "blocks that open a 3×3 crafting menu" registry.

**Lifecycle**

- **Play→configuration re-entry** (server reconfiguration mid-session) cancels the job on
  NeoForge but not on Fabric; both are safe, just inconsistent.
