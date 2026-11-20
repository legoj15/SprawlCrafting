# Solver library (`com.legoj15:solver`) — agent notes

The Minecraft-free crafting planner (`RecipeGraphSolver`, `PlannedStep`, `SlotLayout`,
`SlotAssignment`, `PlanSimulator`, `ProducedLedger`, `LedgerTally`, `UndoMap`, `CountFormat`; package
`com.legoj15.solver`) is a plain-Java library built from `solver-core/`. Every SprawlCrafting build
depends on it and bundles it; a second mod (MegaBlockBot) can do the same.

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

## Build, test, publish locally

From the repo root, on a JDK 21 host (`export JAVA_HOME="/c/Program Files/Java/jdk-21.0.2"`):

    ./gradlew -p solver-core build publishToMavenLocal

`build` runs the JUnit 5 suite (`RecipeGraphSolverTest`, including a fixed-seed random-graph check
that every returned plan replays, and `ProducedLedgerTest`). The
solver suite runs ONLY here now; the mod builds no longer compile or run it. It is still on the
release gate: the root `fullTestSuite` task depends on the included build's `:check`, and
`release/Invoke-Release.ps1`'s Test stage runs `./gradlew -p solver-core build` first.

JitPack: `jitpack.yml` at the repo root publishes just this library (`-p solver-core publishToMavenLocal`
on JDK 21). Verified 2026-11-14 on commit 610f536: the build finds `com.legoj15:solver:1.0.1` but JitPack
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
and Forge 26.3-66.1.5 (JDK 25) each reached `Done (` and logged the init line above with version
`1.0.0` (FML: `Found library file "com.legoj15.solver-1.0.0.jar" [... locator: jarinjar]`). The same
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

- Fabric Loom: `include(implementation("com.legoj15:solver:1.0.1")!!)`.
- NeoForge MDG: `implementation("com.legoj15:solver:1.0.1")` +
  `jarJar("com.legoj15:solver") { version { strictly("[1.0.1,2.0.0)"); prefer("1.0.1") } }`.
- Forge FG7: apply `net.minecraftforge.jarjar`, `jarJar.register()`, then `implementation(...)` +
  `jarJar.configure("jarJar"("com.legoj15:solver:1.0.1")!!) { setRange("[1.0.1,2.0.0)") }`.
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
