# SprawlCrafting — Minecraft 1.12.2 (Forge) module

This is a **separate, self-contained Gradle build** for the legacy 1.12.2 Forge target. It is
deliberately **not** part of the modern Stonecutter / Gradle-9.5 multi-loader tree one directory
up — it has its own wrapper, its own `settings.gradle`, and its own plugin (RetroFuturaGradle),
because 1.12.2 tooling and the modern Fabric/NeoForge tooling cannot share one build.

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
| JEI              | `mezz.jei:jei_1.12.2:4.16.1.1013`              |
| Mixin loader     | `zone.rong:mixinbooter:11.0` (ships SpongePowered Mixin) |

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

Mixin tooling is **wired but unused**: MixinBooter (`zone.rong:mixinbooter:11.0`, resolved from
`https://repo.cleanroommc.com/releases`) is a dependency, the annotation processors that generate
the refmap are configured, the empty config `src/main/resources/mixins.sprawlcrafting.json` exists
(package `com.legoj15.sprawlcrafting.forge.mixin`), and the jar manifest carries
`MixinConfigs: mixins.sprawlcrafting.json` so MixinBooter loads it at runtime (no coremod / TweakClass
needed — MixinBooter removes that). Adding the first mixin class needs **no build-script change**:
drop the class in that package and list it in the JSON.
