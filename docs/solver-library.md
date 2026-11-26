# Solver library (`com.legoj15:solver`) — agent notes

The Minecraft-free crafting planner (`RecipeGraphSolver`, `CostModel`, `PlannedStep`, `SlotLayout`,
`SlotAssignment`, `PlanSimulator`, `ProducedLedger`, `LedgerTally`, `DepthFirstSchedule`, `CountFormat`;
package `com.legoj15.solver`) is a plain-Java library built from `solver-core/`. `UndoMap` and
`UnitCosts` are package-private helpers, not API. Every SprawlCrafting build depends on it and bundles
it; a second mod (MegaBlockBot) can do the same.

**Executor contract (since 1.1.0).** A plan carries its own per-slot item choice:
`Result.Success.layouts()`, aligned 1:1 with `steps()`, holds per step a list of `SlotLayout` runs —
`crafts` consecutive crafts that put `items().get(i)` into non-empty slot `i` of the recipe
(`RecipeInfo.ingredientSlots()` with blank cells dropped; every item one of that slot's alternatives).
`solve` accepts a plan only when it replays from the planned inventory under those fixed layouts, so
the choice the planner made (which alternative each slot group takes, keeping sub-crafts from eating
the only copy a later step needs — issue #23) is the one that executes. An executor takes a stack of
the item its step's layout names for each slot, falling back to `SlotAssignment.assign(slots, stock)`
only when the inventory no longer matches the plan: build `slots` exactly as its
`RecipeInfo.ingredientSlots()` (blank cells dropped) and `stock` from the same pool and filter the
planner snapshots, then take a stack of the item named for each slot (falling back to its own matching
only when none fits). SprawlCrafting's three `CraftExecutor`s pick via `CraftPlanner.slotAlternatives`.
`SlotAssignment.assignCells(cells, stock)` (since 1.1.0) is the same call over the grid as the game lists
it: blank cells are null in and null out, so the result lines up with the recipe's ingredient list.
An executor that picks by inventory order instead can stall mid-job on plans that share stock across
slots or steps (issues #10, #18); in 1.0.x, before layouts, that same limitation bound the solver
itself.

| | |
|---|---|
| Coordinates | `com.legoj15:solver:<solver_version>` |
| Version | `solver_version` in `solver-core/gradle.properties` (independent of the mod `version`) |
| Bytecode | Java 8 (compiled and tested on a Java 8 toolchain; foojay provisions one if missing) |
| JPMS name | `Automatic-Module-Name: com.legoj15.solver` |
| Dependencies | none at runtime |
| Artifacts | main jar + `-sources` jar + Gradle module metadata + POM |

## API: stations, slot quantities, a cost model (since 2.2.0)

Three additions, all backward compatible: a consumer that uses none of them is meant to get exactly
the plans it got from 2.1.0. That was measured outside the suite: two reviewers compared this code with a
build of `7401a19` (2.1.0) over thousands of random graphs and got byte-identical `solve` and
`shortfall` output. The suite cannot check it, having no 2.1.0 build to compare with:
`explicitInterfaceDefaultsAndANullCostModelPlanExactlyLikeTheInheritedDefaults` only shows that recipes
answering the defaults themselves through the cost-model constructor plan exactly like the inherited
defaults through the original constructor.

**Station: `RecipeInfo.station()`** (default null). The place a recipe runs (furnace, crafting table,
machine). It is needed while the recipe runs and is not consumed, however many crafts a step batches.
- `solve*`: before a recipe's slots are filled, one unit of the station item must be in stock, taken
  from stock or crafted through the station item's own producers like any ingredient, so the step
  that crafts it comes earlier in the plan. While the step is planned, that unit is RESERVED (every
  pass: batched, unitwise, exact): it stays in the stock and no ingredient draw at any depth below the
  step may take it (a draw sees `count - 1`), so a slot that also accepts the station item takes
  another alternative, and a recipe that also lists the station as an ingredient needs a second unit
  (crafted if it can be). A station check sees the unit, reserved or not, so a sub-craft at the same
  station runs on it (a hopper at a table from a chest made at the same table needs one table); nested
  steps at one station share the unit, so the reservation never withholds more than one. It ends when
  the step is emitted or abandoned. The unit is never consumed: a later step at the same station needs
  no second one, and a later recipe may consume it as an ingredient. The rule is stated in code on
  `Solve.reserve`. A station that is neither in stock nor craftable makes that recipe unusable on that
  branch like a missing ingredient (the next producer is tried; `Failure.missing()` may name it).
- `shortfall*`: the station's own leaves are demanded once (not per craft, not per recipe sharing the
  station), nothing when it is in stock; the target recipe's station counts too.
  Known limit: the walk fills a recipe's slots before it asks for the station and does not reserve it, so
  when a slot also accepts the station item and the stock holds only the station's own unit, the walk
  takes that unit for the slot and lists one station too many. The list is checked against the planner,
  so it is never short.
- `PlanSimulator.replay` and the solver's own acceptance replay hold one unit of the station out of
  the stock while a step runs and put it back, so a missing station fails the step (`Replay.missing()`
  is the station item) and a recipe that also lists the station as an ingredient needs one more unit.
- The caller represents a station standing in the world as stock of that item. Placing or reaching it
  is not the solver's job.
- `DepthFirstSchedule`: the constructor that takes a per-step `stations` list (one entry per step,
  null for none) models them: a step is not eligible until the model holds one unit of its station
  (the producing step is demanded first), and performing it does not consume that unit. The older
  five-argument constructor knows no stations and must not be given a plan that has them: it can run a
  station step before the step that crafts the station.

**Per-slot quantity: `RecipeInfo.ingredientSlotCount(int slot)`** (default 1, indexed like
`ingredientSlots()`). A slot with quantity q is exactly q consecutive copies of that slot: each copy
may be filled from a different alternative, layouts list the slot q times, and
`craftableIngredientSlots()` stays one list per slot. Below 1 is an `IllegalArgumentException` the first
time a solver pass or `PlanSimulator` reads the recipe. The expansion lives in one place,
`PlanSimulator.expand`, which the solver's slot groups, the exact pass and `PlanSimulator.nonEmptySlots`
all read; `SlotAssignment` still sees plain per-copy slot lists.

**Cost model: `CostModel<R, K>` and the `RecipeGraphSolver` constructor taking one as its last
parameter** (null means today's rule; every older constructor passes null). `gatherCost(item)` is the
cost of getting one unit from outside the recipe graph or `UNOBTAINABLE`; `recipeCost(recipe)` (default
1) is what one craft costs on top of its ingredients. The solver derives a unit cost per item, kept
for its lifetime and readable through `unitCost(item)` (`IllegalStateException` without a model): the
smaller of `gatherCost` and, over the producers, `ceil((recipeCost + sum over slots of quantity x the
cheapest alternative's unit cost + the station's unit cost) / resultCount)`. An alternative that cannot
be crafted in its slot (`craftableIngredientSlots()`) is worth its gather cost only. Sums saturate at
`UNOBTAINABLE`. The station counts in full, once per craft: stock is unknown when costs are derived, so
a station the caller already owns still makes the recipe look dearer than it is in practice (left out
on purpose).

The unit cost orders choices and never makes a target fail that the same stock can make without a
cost model; nothing is removed from consideration because of its cost.
- Plans (`solve*`): producers are tried cheapest first; stock is drawn first, across all alternatives
  in the caller's order, and what is missing is then crafted from the alternatives by ascending unit
  cost. Anything costing `UNOBTAINABLE` (gather or recipe cost) is tried last, not dropped; ties keep
  the caller's order. The leaf rule below never stops a plan crafting an item out of stock. The passes
  are a greedy search, so a cost order can walk into a dead end the caller's order avoids (a cheaper
  producer's first slot takes stock another branch needs): when the cost-ordered passes find no plan,
  the caller's order is tried before the failure stands.
- Shortfall (`shortfall*`): what stock can make is credited first, as without a cost model. Only the
  remainder is costed: an item whose `gatherCost` is obtainable and no more than its cheapest producer
  is a leaf and listed as demand; otherwise its cheapest producer is expanded; a slot's remainder goes
  to its cheapest alternative, raw or crafted, replacing "raw before craftable". An unobtainable
  alternative or producer is chosen only when every option is, then in the caller's order, as today.
- `solve*` results are still accepted only when they replay, so a cost model changes which valid plan
  is found, never whether a returned plan is valid.

`UNOBTAINABLE` is therefore safe for an item that may be held but not gathered: recipes that need it
sort last and are used when stock holds it. A consumer that wants a recipe gone leaves it out of
`producers`.

The unit cost is a pure function of the item, the producers, the model and `maxDepth`, the same in any
query order: `cost_0(i) = gatherCost(i)`, `cost_r(i) = min(gatherCost(i), min over producers p of
recipe_{r-1}(p))`, `recipe_{r-1}(p)` the formula above with every ingredient and the station at
`cost_{r-1}`, and `unitCost(i) = cost_maxDepth(i)`. `UnitCosts` relaxes a whole connected piece of the
graph one level per round and keeps every item's per-level history, so a later query reads an earlier
item at the level it needs (never the full-depth value it was priced at from its own root), and a
dense cycle (sixteen re-dyeable bed colours) costs depth x edges, not factorial. Deeper than
`maxDepth` is unobtainable, and a cycle never makes anything cheaper than its way in from outside:
ingot, block, ingot costs more than the ingot it started from, and a cycle with nothing to gather
stays unobtainable. (A recipe loop that yields more than it consumes is priced as the loop it is,
within the same depth bound.) Recipe shapes are read once per derivation and not cached across
queries, so a `producers` function that builds new recipe objects on every call grows nothing.

`RecipeGraphSolver`'s `remainder` argument may be null for a recipe set whose crafts leave nothing
behind; before 2.2.0 `solve` dereferenced it despite the documentation saying so.

## Build, test, publish locally

From the repo root, on a JDK 21 host (`export JAVA_HOME="/c/Program Files/Java/jdk-21.0.2"`):

    ./gradlew -p solver-core build publishToMavenLocal

`build` runs the JUnit 5 suite (`RecipeGraphSolverTest`, including fixed-seed random-graph checks that
every returned plan replays, with and without stations, quantities and a cost model; `ProducedLedgerTest`;
`DepthFirstScheduleTest`; `SlotAssignmentRepeatTest`). The
solver suite runs ONLY here now; the mod builds no longer compile or run it. It is still on the
release gate: the root `fullTestSuite` task depends on the included build's `:check`, and
`release/Invoke-Release.ps1`'s Test stage runs `./gradlew -p solver-core build` first.

JitPack: `jitpack.yml` at the repo root publishes just this library (`-p solver-core publishToMavenLocal`
on JDK 21). Verified 2026-11-14 on commit 610f536: the build finds `com.legoj15:solver:<solver_version>` but JitPack
serves it RENAMED as `com.github.legoj15:SprawlCrafting:<tag-or-commit>` (POM, jar and Gradle module all
carry that group/artifact; the jar holds `com/legoj15/solver/*`). Any tag or commit hash works as the
version; JitPack builds on first request.

## How the SprawlCrafting builds consume it

All three builds (root Stonecutter tree, `legacy-1.16.5/`, `forge-1.12.2/`) declare
`includeBuild("solver-core")` in their settings with a dependency substitution for
`com.legoj15:solver`, so the coordinate resolves to this checkout's source: no publish step for local
dev, and `runClient`/`runServer` get the library on the runtime classpath through `implementation`.

| Build | How it is bundled | Where to look in the jar |
|---|---|---|
| Fabric nodes (Loom, `build.fabric-{o,m}.gradle.kts`), legacy 1.16.5 Fabric | `include(implementation(...))`: jar-in-jar; Loom generates a `fabric.mod.json` (id `com_legoj15_solver`) for the nested jar | `META-INF/jars/solver-<v>.jar` + `"jars"` in `fabric.mod.json` |
| NeoForge nodes (ModDevGradle) | `implementation` + `jarJar` with `strictly("[v,nextMajor.0.0)")`, `prefer(v)` | `META-INF/jarjar/com.legoj15.solver-<v>.jar` + `META-INF/jarjar/metadata.json` |
| Forge nodes (ForgeGradle 7 + `net.minecraftforge.jarjar`), incl. 1.20.1's SRG path | `implementation` + `"jarJar"(...)` with `jarJar.configure(dep) { setRange(...) }`; jarJar is now registered on every Forge node, so the plain `jar` is always the `-slim` intermediate. On 1.20.1 Renamer renames the jarJar output and leaves the nested jar alone (it has no MC references) | `META-INF/jarjar/solver-<v>.jar` + `metadata.json` |
| legacy 1.16.5 Forge (Forge 36), forge-1.12.2 (RFG) | SHADED: a `shade` configuration (extended by `implementation`) whose classes the `jar` task copies in, before Renamer / reobfJar | `com/legoj15/solver/*.class` at the jar root |

### Dev runs (runServer / runClient) need the library on the GAME classpath

`implementation` alone is not enough on the Forge-family 1.x lines: their FML does not load a plain
(non-mod) jar from the JVM classpath, so the first solver class throws `NoClassDefFoundError`. Wired:

- NeoForge 1.21.1: also `"additionalRuntimeClasspath"(...)`. MDG REJECTS that configuration on 26.x
  ("no additional classpath anymore"), where plain `implementation` already works, so it is gated on
  `minecraftVersion.startsWith("1.")`.
- Forge 1.20.1 (FG7, SRG line): a `solverDevLibrary` configuration fed to each run's
  `extraLibraries` (FG7 puts those on Forge's `minecraft_classpath`). Forge 1.21.1/26.x need nothing:
  FG7's launch puts the whole JVM classpath in the boot module layer.
- Fabric (all lines), Forge 1.16.5, 1.12.2: `implementation` is enough.

Verified 2026-11-09 with a temporary probe that loaded `CountFormat` at mod init on each dev server:
1.21.1-neoforge and 1.20.1-forge (module `com.legoj15.solver`, game module layer), 1.21.1-forge and
26.3-forge (boot layer), 26.3-neoforge (app classloader), 1.21.1-fabric / 26.3-fabric / 1.16.5 Fabric
(Knot), 1.16.5 Forge (app classloader). A consumer mod's dev setup needs the same per-loader wiring.

`CommonClass.init` (every modern loader's entrypoint) now resolves `RecipeGraphSolver` and logs
`SprawlCrafting init on <loader> (<env>); solver library com.legoj15.solver.RecipeGraphSolver <version> loaded`
at mod init, so ANY boot (dev
smoke, the production release matrix) fails with `NoClassDefFoundError` if the library is missing from
that runtime instead of only failing later at the first craft.

Production loading of the NESTED copy, verified 2026-11-09 on real dedicated servers installed from the
official installers (`--installServer`, headless, built jar alone in `mods/`, nothing else on the
classpath): NeoForge 21.1.233, Forge 1.21.1-52.1.21, Forge 1.20.1-47.4.29 (JDK 21), NeoForge 26.3.0.99
and Forge 26.3-66.1.5 (JDK 25) each reached `Done (` and logged the init line above with the solver
version of that day (FML: `Found library file "com.legoj15.solver-<solver_version>.jar" [... locator: jarinjar]`). The same
NeoForge 21.1 and Forge 1.21.1 servers also booted with a SECOND jar (`FMLModType: LIBRARY`) nesting its
own copy of the library under the same identifier and range, the bot-mod situation: both copies were
scanned, one was loaded, no split-package error. Fabric was not production-tested (Knot loads nested
jars the same way in dev and production).

### Build output: one directory per including build

Three separate Gradle builds include `solver-core` (root, legacy-1.16.5, forge-1.12.2). Each writes
its own `solver-core/build/<including build's root project name>/`; only a standalone
`-p solver-core` run (release script, JitPack) uses plain `solver-core/build/`. So the builds can run
in parallel in one checkout and switching between them does not recompile the library (#22; verified
2026-11-20 with all three building the jar at once).

A build outside this repository that includes `solver-core` (MegaBlockBot) gets
`solver-core/build/<root project name>-<hash of the including checkout's path>/`: its git worktrees all
share one root project name, and they build at the same time.

Before that split they shared one directory, and a leftover daemon from one build held
`build/libs/solver-<v>.jar` open so the next failed with "Unable to delete file". That is why all four
`gradle.properties` files set `org.gradle.daemon=false`. The lock reason is gone; the setting was left
in place and turning the daemon back on is an unmeasured, separate decision (idle daemons at 3 GB
each across many worktrees).

Shading is only acceptable where no other mod nests the library: on Forge/NeoForge with JarJar, FML
loads a nested plain jar as a JPMS module, and two mods that SHADE the same package crash the game as
a split package. Two mods that NEST it should be deduplicated: JarJarSelector groups nested jars by
their metadata identifier (group + artifact), intersects every declaring mod's range and keeps one jar
(`VERSION_RESOLUTION_FAILED` if the ranges do not intersect). Boot-tested with two nesting jars of the
same version on NeoForge 21.1 and Forge 1.21.1 (above); mixed versions and non-intersecting ranges
were not tested. Fabric dedupes by mod id (Loom's generated id `com_legoj15_solver` comes from the
coordinates); not boot-tested with two mods.

## A consumer mod (e.g. the bot) depending on it

Add `mavenLocal()` (local dev) or `maven("https://jitpack.io")`, or include this repo's `solver-core` as
a composite build. Then, per loader:

- Fabric Loom: `include(implementation("com.legoj15:solver:2.2.0")!!)`.
- NeoForge MDG: `implementation("com.legoj15:solver:2.2.0")` +
  `jarJar("com.legoj15:solver") { version { strictly("[2.2.0,3.0.0)"); prefer("2.2.0") } }`.
- Forge FG7: apply `net.minecraftforge.jarjar`, `jarJar.register()`, then `implementation(...)` +
  `jarJar.configure("jarJar"("com.legoj15:solver:2.2.0")!!) { setRange("[2.2.0,3.0.0)") }`.
- Never shade it on a JarJar-era loader (see above). Use the same group/artifact (`com.legoj15:solver`)
  in jar-in-jar metadata as SprawlCrafting does, or the selector sees two different libraries and
  loads both (a split package again). A JitPack build carries `com.github.legoj15:SprawlCrafting` (verified,
  see above), so a consumer resolving from JitPack must not nest it under those coordinates: either
  use the composite or Maven Local coordinates, or map JitPack's module onto `com.legoj15:solver`
  with a Gradle `dependencySubstitution`/capability rule before jar-in-jar metadata is generated.

## Version-bump rule

Bump `solver_version` whenever `solver-core` changes in a way a consumer can see: public API,
planning results, formatting output. Patch/minor for compatible changes; MAJOR for a breaking API
change (consumers bundle with a `[current, nextMajor)` range, so a major bump makes FML refuse to mix
an old and a new consumer instead of crashing at runtime). The mod builds pick the new version up
automatically: the root controller, `legacy-1.16.5/build.gradle` and `forge-1.12.2/build.gradle` all
read `solver-core/gradle.properties`.
