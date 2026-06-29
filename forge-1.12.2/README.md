# SprawlCrafting — Minecraft 1.12.2 (Forge) module

This is a **separate, self-contained Gradle build** for the legacy 1.12.2 Forge target. It is
deliberately **not** part of the modern Stonecutter / Gradle-9.5 multi-loader tree one directory
up — it has its own wrapper, its own `settings.gradle`, and its own plugin (RetroFuturaGradle),
because 1.12.2 tooling and the modern Fabric/NeoForge tooling cannot share one build.

## What's implemented (v1 port)

The full Factorio-style deferred-craft engine, ported onto 1.12.2's APIs on top of the shared
`solver-core`. The whole engine compiles against the deobfuscated 1.12.2 Forge classpath, builds a
reobfuscated jar, and the 39-case solver suite passes under the Java 8 toolchain. **Runtime
behaviour is not yet playtested** (a 1.12.2 client launch is needed — see "Verification" below).

- **Engine** (`forge.craft`): `CraftPlanner` (adapts `CraftingManager` recipes + the player
  inventory onto the solver), `CraftExecutor` (per-step craft against the live inventory, with
  crafting remainders and overflow-drop), `CraftJob`/`CraftStep`/`CraftQueueManager` (single job per
  player, one component every 10 ticks), `CraftingTableReach` (3×3 steps pause unless a table is
  within vanilla's 8-block reach), `GridContext` (2×2 inventory vs 3×3 table whole-chain gating).
- **Item identity is `(Item, metadata)`** (`ItemKey`), not `Item` — 1.12.2 packs distinct
  craftables into one `Item` via metadata (all six planks are `minecraft:planks`).
- **Triggers:**
  - **JEI "+" button** (`forge.compat.jei`): a transfer handler on the crafting category for the
    inventory (2×2) and table (3×3) containers routes "+" into a deferred craft. It overwrites
    JEI's built-in crafting transfer (JEI's registry is last-write-wins). The button greys out for
    items that can't be made from current stock.
  - **`/sprawlcrafting craft <recipe> | cancel | status`** command (aliases `/sprawlcraft`, `/sc`).
  - **Recipe book** (`forge.mixin`): two client mixins paint a yellow outline on recipes craftable
    only via intermediates and divert a click on them into a deferred craft. **These need a playtest
    to confirm they apply** (see "Mixin status").
- **Networking** (`forge.network`): a `SimpleNetworkWrapper` channel — S2C progress + two C2S start
  paths (by result item for JEI, by recipe id for the recipe book).
- **HUD** (`forge.client.HudOverlay`): a top-right progress flyout (the 1.12.2 analog of the modern
  toast), driven by the S2C progress message.

### Deliberate simplifications vs the modern tree

- **No final-grid hand-off.** The modern tree lays the last craft into an open grid for the player
  to grab; this port always auto-crafts the result into the inventory. The hand-off was a later
  modern refinement; the core "intermediates + final become real items" behaviour is unchanged.
- **JEI "+" runs everything through the engine**, including directly-craftable recipes — so the
  result lands in the inventory after the engine's half-second cadence rather than being laid into
  the grid like vanilla "+".
- **Modded 3×3 tables** that aren't a `ContainerWorkbench` are seen as 2×2 (no generic grid-size
  accessor in 1.12.2). The recipe-book "craftable-only" filter is not yet taught to include yellow
  (deferred) recipes — they show yellow when the filter is off.

## Why RetroFuturaGradle (and not ForgeGradle 2.3)

1.12.2 originally built with `net.minecraftforge.gradle:ForgeGradle:2.3-SNAPSHOT` on Gradle 3.x —
fragile, Java-8-only, and a `-SNAPSHOT` that can break without notice.

**[RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle) 2.0.2** is the
actively-maintained (2025/2026) replacement: it runs on **Gradle 9.5.1** and **JDK 25**
while compiling the mod against an auto-provisioned **Java 8** toolchain. This is the same plugin
the current [CleanroomMC ForgeDevEnv](https://github.com/CleanroomMC/ForgeDevEnv) 1.12.2 template
uses.

> **JDK requirement (verified):** RFG 2.0.2's plugin classes are compiled for Java 25 bytecode, so
> the Gradle build host **must run on JDK 25** (a JDK-21 run fails with `UnsupportedClassVersionError`).
> This **differs from the modern Stonecutter tree one level up, which pins JDK 21** (Gradle 9.5.0
> rejects newer JDKs). So the two builds need different host JDKs — any script that drives both must
> set `JAVA_HOME` per build. RFG still emits Java 8 *mod* bytecode via the auto-provisioned toolchain;
> only the Gradle host JDK differs. The legacy fallback, if RFG ever became unviable, is
[anatawa12's ForgeGradle-2.3 fork](https://github.com/anatawa12/ForgeGradle-2.3)
(`com.anatawa12.forge:ForgeGradle:2.3-1.0.8`), which keeps classic FG 2.3 alive but tops out at
older Gradle and is no longer updated (last release 2023).

## Versions (verified June 2026)

| Component        | Value                                          |
|------------------|------------------------------------------------|
| Build plugin     | `com.gtnewhorizons.retrofuturagradle` **2.0.2** |
| Gradle wrapper   | **9.5.1** (`gradle-9.5.1-bin.zip`)             |
| JDK to run build | **25** (RFG 2.0.2 plugin is Java 25 bytecode; mod compiles via auto-provisioned **JDK 8** toolchain) |
| Minecraft        | 1.12.2                                          |
| Forge            | 14.23.5.2847 (recommended; RFG pulls it for 1.12.2) |
| MCP mappings     | `stable_39`                                    |
| JEI              | `mezz.jei:jei_1.12.2:4.16.1.1013` (`compileOnly` — soft dep) |
| Mixin loader     | `zone.rong:mixinbooter:11.0` (ships the Mixin runtime) |
| Mixin AP         | `org.spongepowered:mixin:0.8.5:processor` (refmap generation) |

## Shared solver core

The pure-Java-8 planning core in `../solver-core/src/main/java` is compiled directly into this mod
via `sourceSets.main.java.srcDir '../solver-core/src/main/java'` in `build.gradle`. No separate
artifact — one source of truth shared with the modern loaders.

## Build

Run the wrapper on **JDK 25** (`JAVA_HOME` must point at a 25.x JDK; the machine's default
`JAVA_HOME` already does):

```sh
./gradlew setupDecompWorkspace   # one-time: decompile/deobf the 1.12.2 client (~2 min, large download)
./gradlew build                  # compile the mod (+ shared solver-core) and reobf the jar
./gradlew runClient              # launch a dev client with the mod
```

`./gradlew tasks` works without any decompile and is enough to confirm the build configures.

**Verified (June 2026):** `setupDecompWorkspace build` is green — `solver-core` and the `@Mod`
entrypoint compile against the deobfuscated 1.12.2 Forge classpath and the reobf jar is produced
with `MixinConfigs` wired in its manifest. (First run needed a one-time clear of a stale 2020-era
`~/.gradle/caches/minecraft/de/oceanlabs/mcp/mcp_stable/39` dir whose old ForgeGradle-2.x layout
made RFG skip SRG generation.)

## Mixin status

Two **client** mixins drive the recipe-book trigger (`com.legoj15.sprawlcrafting.forge.mixin`):

- `GuiButtonRecipeMixin` — `@Inject` at the tail of `GuiButtonRecipe.drawButton`; paints a yellow
  outline on a recipe the player can make only via intermediates (client-solver `DEFERRED`).
- `GuiRecipeBookMixin` — `@Redirect` on the `playerController.func_194338_a(...)` call inside
  `GuiRecipeBook.mouseClicked`; a click on a deferred recipe sends SprawlCrafting's start packet
  instead of vanilla's recipe placement.

Both carry `require = 0` and wrap the solver call defensively, so a mapping mismatch degrades to a
no-op (no yellow / falls back to vanilla) rather than crashing the client.

**Refmap.** MixinBooter ships the Mixin *runtime* but not the dev-time annotation processor, so the
AP comes from `org.spongepowered:mixin:0.8.5:processor`. The `build.gradle` `JavaCompile` block
feeds it RFG's MCP→SRG srg (`-AreobfSrgFile`, from `mcp_stable/39/rfg_srgs/mcp-srg.srg`) and writes
`build/mixinRefmap/mixins.sprawlcrafting.refmap.json`, which the `jar` task bundles into the jar
root. Verified the refmap is generated and maps `drawButton`→`func_191745_a` and
`mouseClicked`→`func_191862_a` (the `func_194338_a` redirect target is already an SRG name and needs
no entry). Without this, the mixins would compile but silently fail to apply in an obfuscated
install. The jar manifest still carries `MixinConfigs: mixins.sprawlcrafting.json` so MixinBooter
loads the config (no coremod / TweakClass needed).

## Verification

`setupDecompWorkspace build` is green: `solver-core`, the engine, networking, the command, the HUD,
the JEI plugin, and the two mixins all compile against the deobfuscated 1.12.2 Forge + JEI classpath;
the reobf jar is produced with the refmap and `MixinConfigs` in place; and `:test` runs the 39-case
solver suite under the real Java 8 toolchain.

**Not yet done — runtime playtest.** No part of the runtime has been exercised in a live client (the
engine, the JEI "+", the HUD, and especially the mixin application/visuals). Run `./gradlew runClient`
on JDK 25 with JEI in `run/mods/` and confirm: a deferred recipe yellows in the book and crafts on
click; the JEI "+" starts a craft and greys for unmakeable items; `/sprawlcrafting craft …` works;
the HUD flyout tracks progress; and a 3×3 chain pauses away from a table.
