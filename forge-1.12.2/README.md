# SprawlCrafting — Minecraft 1.12.2 (Forge) module

This is a **separate, self-contained Gradle build** for the legacy 1.12.2 Forge target. It is
deliberately **not** part of the modern Stonecutter / Gradle-9.5 multi-loader tree one directory
up — it has its own wrapper, its own `settings.gradle`, and its own plugin (RetroFuturaGradle),
because 1.12.2 tooling and the modern Fabric/NeoForge tooling cannot share one build.

## What's implemented

The full Factorio-style deferred-craft engine, ported onto 1.12.2's APIs on top of the shared
`solver-core`, at feature parity with the modern loaders. The whole engine compiles against the
deobfuscated 1.12.2 Forge classpath, builds a reobfuscated jar, and the 39-case solver suite passes
under the Java 8 toolchain. The runtime has been **playtested in a live client and merged to `main`**.

- **Engine** (`forge.craft`): `CraftPlanner` (adapts `CraftingManager` recipes + the player
  inventory onto the solver), `CraftExecutor` (per-step craft against the live inventory, with
  crafting remainders and overflow-drop), `CraftJob`/`CraftStep`/`CraftQueueManager` (single job per
  player, one component every 10 ticks), `CraftingTableReach` (3×3 steps pause unless a table is
  within vanilla's 8-block reach), `GridContext` (2×2 inventory vs 3×3 table whole-chain gating,
  with structural 3×3 detection so any modded station counts), `ServerGridPlacer` (atomic
  vanilla-style placement for the JEI "+" and the final-step grid hand-off), `ExternalSlots`
  (sources a modded station's bound chest alongside the player inventory).
- **Item identity is `(Item, metadata)`** (`ItemKey`), not `Item` — 1.12.2 packs distinct
  craftables into one `Item` via metadata (all six planks are `minecraft:planks`).
- **Triggers:**
  - **JEI "+" button** (`forge.compat.jei`): a transfer handler on the crafting category for the
    inventory (2×2) and table (3×3) containers. Directly-craftable recipes place into the grid like
    vanilla; recipes craftable only via intermediates route into a deferred craft. Mixins color the
    "+" (yellow = deferred, orange = unsolvable → opens the gather screen, white = plain move) and
    paint red highlights on the genuinely-missing slots. Overwrites JEI's built-in transfer (its
    registry is last-write-wins).
  - **Recipe book** (`forge.mixin`): client mixins paint a yellow outline on recipes craftable only
    via intermediates, keep those recipes visible under the craftable-only filter, add tooltip
    hints, ghost the recipe into the grid on first click, and divert a confirming click into a
    deferred craft (see "Mixin status").
- **"What am I missing" surfaces** (gather screen): right-clicking a red recipe or the orange JEI
  button opens a list of the raw materials still needed — the 1.12.2 port of modern's needs system,
  gated by the `needsSystem` config.
- **Networking** (`forge.network`): a `SimpleNetworkWrapper` channel — S2C progress + C2S start
  paths (by result item for JEI, by recipe id for the recipe book). Grid-hand-off and screen-state
  messages ride a **second** channel (`sprawlcrafting2`) behind a hello handshake, so a mixed
  old/new client and server never desync or kick.
- **Progress toast** (`forge.client`): a real vanilla toast — persistent per job, progress bar,
  state colors — plus the per-step craft sound, driven by the S2C progress message. (The first port
  used a custom top-right HUD overlay; it's been replaced by the vanilla toast to match modern.)
- **Config** (`forge.SprawlConfig`): Forge `@Config` → `config/sprawlcrafting.cfg` and a free
  mod-list "Config" screen; `soundEffects` / `jeiIntegration` / `needsSystem` (no REI on 1.12.2),
  read live with modern's gate composition.
- **Requires MixinBooter** (client): it provides the SpongePowered Mixin runtime and reads the jar's
  `MixinConfigs` manifest entry. Without it the recipe-book and JEI mixins silently no-op, so the
  client proxy hard-stops at a friendly error screen (`MissingMixinBooterException`) telling the
  player to install it. A dedicated server doesn't need it.

### Parity with the modern tree

Later work closed the gaps the first port shipped with, so 1.12.2 now matches the modern loaders on
the behaviours that matter:

- **Final-step grid hand-off.** The last craft of a job is laid into the open crafting grid as a
  real, takeable result (`READY_IN_GRID`), on vanilla and modded tables alike; the 2×2 inventory
  screen is covered by a client screen-state signal.
- **JEI "+" matches vanilla** for directly-craftable recipes — one C2S packet and atomic
  server-side placement into the grid (a port of vanilla's own recipe-book placer, generic over
  modded stations), instead of always routing through the engine into the inventory. Only genuinely
  deferred recipes go through the engine.
- **Any 3×3 crafter is recognised structurally.** `GridContext` treats any container exposing an
  `InventoryCrafting` ≥ 3×3 as a 3×3 grid (Tinkers' Construct Crafting Station, its slab-mod
  variants, FastWorkbench, …), not just `ContainerWorkbench`; placement can also source from a
  station's bound chest, matching those stations' own JEI behaviour.
- **The craftable-only filter keeps deferred (yellow) recipes visible**, with tooltip hints and a
  first-click ghost of the recipe into the grid.

**One deliberate cosmetic cut:** the recipe book's variant-overlay ("alternates") bubble isn't given
the deferred (yellow) styling — its craftable flag is a final ctor arg on an SRG-named inner class,
too fragile a hook for a rarely-seen bit of UI.

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

The recipe-book trigger is driven by two **client** mixins (`com.legoj15.sprawlcrafting.forge.mixin`):

- `GuiButtonRecipeMixin` — `@Inject` at the tail of `GuiButtonRecipe.drawButton`; paints a yellow
  outline on a recipe the player can make only via intermediates (client-solver `DEFERRED`).
- `GuiRecipeBookMixin` — `@Redirect` on the `playerController.func_194338_a(...)` call inside
  `GuiRecipeBook.mouseClicked`; a click on a deferred recipe sends SprawlCrafting's start packet
  instead of vanilla's recipe placement.

Both carry `require = 0` and wrap the solver call defensively, so a mapping mismatch degrades to a
no-op (no yellow / falls back to vanilla) rather than crashing the client. Alongside them,
`RecipeListMixin` keeps deferred recipes visible past the craftable-only filter, and the `.mixin.jei`
package carries the "+" tint and transfer-button mixins behind the JEI integration — all `require = 0`
too.

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

`setupDecompWorkspace build` is green: `solver-core`, the engine, networking, the toast client,
the JEI plugin, and the mixins all compile against the deobfuscated 1.12.2 Forge + JEI classpath;
the reobf jar is produced with the refmap and `MixinConfigs` in place; and `:test` runs the 39-case
solver suite under the real Java 8 toolchain.

**Runtime playtested and merged.** The engine, the JEI "+" (colored buttons, red missing-slot
highlights, orange → gather screen), the recipe-book trigger/filter/tooltips/ghost, the progress
toast and step sound, the config screen, the final-step grid hand-off, and chest sourcing at modded
stations have all been exercised in a live 1.12.2 client and merged to `main`. To re-run a dev
client, `./gradlew runClient` on JDK 25 (MixinBooter and JEI are already on the dev classpath).
