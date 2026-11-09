# Solver library (`com.legoj15:solver`) — agent notes

The Minecraft-free crafting planner (`RecipeGraphSolver`, `PlannedStep`, `ProducedLedger`, `UndoMap`,
`CountFormat`; package `com.legoj15.solver`) is a plain-Java library built from `solver-core/`. Every
SprawlCrafting build depends on it and bundles it; a second mod (MegaBlockBot) can do the same.

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

`build` runs the JUnit 5 suite (52 tests: `RecipeGraphSolverTest` 49, `ProducedLedgerTest` 3). The
solver suite runs ONLY here now; the mod builds no longer compile or run it.

JitPack: `jitpack.yml` at the repo root publishes just this library (`-p solver-core publishToMavenLocal`
on JDK 21). JitPack serves it from `https://jitpack.io` under its own `com.github.legoj15...`
coordinates (not yet verified on a real tag; read the tag's build log on jitpack.io for the exact
group/artifact before pointing a consumer at it).

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
Production loading of the NESTED copy (FML's jar-in-jar path) has not been boot-tested yet.

### Windows trap: a lingering daemon locks the library jar

Every build that includes `solver-core` must run with `org.gradle.daemon=false` (root, legacy-1.16.5
and forge-1.12.2 all do). A leftover daemon keeps `solver-core/build/libs/solver-<v>.jar` open and the
next build fails with "Unable to delete file". forge-1.12.2 runs Gradle 9.5.1 while the others run
9.5.0, so switching between them recompiles solver-core (task history is per Gradle version).

Shading is only acceptable where no other mod nests the library: on Forge/NeoForge with JarJar, FML
loads a nested plain jar as a JPMS module, and two mods that SHADE the same package crash the game as
a split package. Two mods that NEST it are deduplicated by FML (highest version inside every range).

## A consumer mod (e.g. the bot) depending on it

Add `mavenLocal()` (local dev) or `maven("https://jitpack.io")`, or include this repo's `solver-core` as
a composite build. Then, per loader:

- Fabric Loom: `include(implementation("com.legoj15:solver:1.0.0")!!)`.
- NeoForge MDG: `implementation("com.legoj15:solver:1.0.0")` +
  `jarJar("com.legoj15:solver") { version { strictly("[1.0.0,2.0.0)"); prefer("1.0.0") } }`.
- Forge FG7: apply `net.minecraftforge.jarjar`, `jarJar.register()`, then `implementation(...)` +
  `jarJar.configure("jarJar"("com.legoj15:solver:1.0.0")!!) { setRange("[1.0.0,2.0.0)") }`.
- Never shade it on a JarJar-era loader (see above). Use the same group/artifact (`com.legoj15:solver`)
  in jar-in-jar metadata as SprawlCrafting does, or FML cannot deduplicate the two copies; a JitPack
  build's metadata would carry `com.github.legoj15` instead, so prefer the composite or Maven Local
  coordinates for anything that ships alongside SprawlCrafting.

## Version-bump rule

Bump `solver_version` whenever `solver-core` changes in a way a consumer can see: public API,
planning results, formatting output. Patch/minor for compatible changes; MAJOR for a breaking API
change (consumers bundle with a `[current, nextMajor)` range, so a major bump makes FML refuse to mix
an old and a new consumer instead of crashing at runtime). The mod builds pick the new version up
automatically: the root controller, `legacy-1.16.5/build.gradle` and `forge-1.12.2/build.gradle` all
read `solver-core/gradle.properties`.
