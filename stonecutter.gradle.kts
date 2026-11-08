// ─── Stonecutter controller (root project build script) ─────────────────────
// This is the Tree controller, NOT the per-node build. The actual mod builds live in the
// per-node scripts assigned in settings.gradle.kts: build.fabric-o.gradle.kts (Loom-remap)
// and build.neoforge.gradle.kts (ModDevGradle), plus build.forge.gradle.kts (ForgeGradle 7) for the
// MinecraftForge node. Each runs once per version node.
//
// The loader plugins are declared here with `apply false` so their versions are known to
// every node subproject; each node applies its own (version-less) in its build script.
plugins {
    id("dev.kikugie.stonecutter")
    id("net.fabricmc.fabric-loom-remap") version "1.17.8" apply false
    // Modern CalVer lines (26.x) use PLAIN Loom (unobfuscated MC, no source remapping); same 1.17.x
    // toolchain family as the remap variant above. The 26.1.2-fabric node applies it version-less.
    id("net.fabricmc.fabric-loom") version "1.17.11" apply false
    // 2.0.148 (from 2.0.141): the older NeoFormRuntime failed to recompile MC 26.3 ("contents() ...
    // weaker access" in HolderSet). Applies to every NeoForge node; all four rebuilt + gametests green.
    id("net.neoforged.moddev") version "2.0.148" apply false
    // MinecraftForge (NOT NeoForge) for the 1.21.1-forge node. ForgeGradle 7 needs Gradle >= 9.3.
    id("net.minecraftforge.gradle") version "7.0.40" apply false
    // Jar-in-jar for the Forge node (bundles MixinExtras, which Forge 1.21.1 does not ship).
    id("net.minecraftforge.jarjar") version "0.2.3" apply false
    // SRG reobf for Forge nodes whose runtime still uses SRG names (1.20.1-forge): renames the jar
    // named -> SRG and rewrites the Mixin refmap. Same version the legacy-1.16.5 Forge build uses.
    id("net.minecraftforge.renamer") version "1.1.8" apply false
}

// The active node — what the IDE/runClient sees and what `compileJava` builds.
// Stonecutter's "Set active project to ..." task rewrites this line.
stonecutter active "1.21.1-fabric" /* [SC] DO NOT EDIT */

stonecutter parameters {
    // Per-node boolean constants for //? directives. The node project is named "<mc>-<loader>",
    // so the trailing segment is the loader: defines fabric/neoforge/forge true for the matching node.
    constants.match(current.project.substringAfterLast('-'), "fabric", "neoforge", "forge")
    // REI ships compatible builds on the 1.20.1 and 1.21.1 lines only (no 26.x release, no Forge build),
    // so its compat layer is gated on the MC version here and excluded by the Forge scripts.
    constants["rei"] = current.version == "1.21.1" || current.version == "1.20.1"

    // ─── Source replacements (forward-port the 1.21.1-canonical source to 26.x) ──────────────────
    // ONE copy for every node and loader (it used to be duplicated in each per-node script). The shared
    // source is authored in the 1.21.1 form. `direction` is true only on the node versions named below, so
    // the forward rename runs there; on older nodes the reverse pair runs but matches nothing in the
    // canonical source (no-op), keeping the green 1.21.1 nodes untouched. Word boundaries (\b) avoid
    // clobbering substrings (e.g. ResourceLocationArgument, IdentifierException).
    val is1_21_11Plus = eval(current.version, ">=1.21.11")
    // 26.2 is the first MC line that diverges from the 26.1.x API (not just from 1.21.1), so it needs its
    // own forward-port tier layered on top of the >=1.21.11 one.
    val is26_2Plus = eval(current.version, ">=26.2")
    // 26.3 moved the render-pipeline API out of blaze3d into the new renderpearl package.
    val is26_3Plus = eval(current.version, ">=26.3")
    // ─── Back-port replacements (1.21.1-canonical source down to 1.20.x) ─────────────────────
    // Declared here, not in a loader script, so every loader on these lines (1.20.1-fabric and
    // 1.20.1-forge) gets the identical source rewrite. Each pair runs forward only on the
    // older nodes; on 1.21.1+ the reverse pattern never matches canonical source, so it is a no-op.
    // The canonical (1.21.1) source must therefore never contain the reverse-side spellings:
    // `new ResourceLocation(`, `RecipeBookMenu<?>`, `isSameItemSameTags`,
    // `player.level().registryAccess()`, or the shim's FQN.
    val pre1_21 = eval(current.version, "<1.21")
    val pre1_20_5 = eval(current.version, "<1.20.5")
    val pre1_20_2 = eval(current.version, "<1.20.2")
    replacements {
        // MC 1.21.11 renamed net.minecraft.resources.ResourceLocation -> Identifier (same package).
        regex {
            direction.set(is1_21_11Plus)
            replace("\\bResourceLocation\\b", "Identifier", "\\bIdentifier\\b", "ResourceLocation")
        }
        regex {
            direction.set(is1_21_11Plus)
            replace("\\bResourceLocationArgument\\b", "IdentifierArgument", "\\bIdentifierArgument\\b", "ResourceLocationArgument")
        }
        // net.minecraft.Util moved to net.minecraft.util.Util.
        regex {
            direction.set(is1_21_11Plus)
            replace("\\bnet\\.minecraft\\.Util\\b", "net.minecraft.util.Util", "\\bnet\\.minecraft\\.util\\.Util\\b", "net.minecraft.Util")
        }
        // GameRules moved into the net.minecraft.world.level.gamerules subpackage.
        regex {
            direction.set(is1_21_11Plus)
            replace("net\\.minecraft\\.world\\.level\\.GameRules\\b", "net.minecraft.world.level.gamerules.GameRules", "net\\.minecraft\\.world\\.level\\.gamerules\\.GameRules\\b", "net.minecraft.world.level.GameRules")
        }
        // MC 26.2 moved screen/overlay/toast management off Minecraft and onto the Minecraft.gui (Gui)
        // instance: minecraft.screen -> minecraft.gui.screen(); setScreen(..) -> gui.setScreen(..);
        // getToastManager() -> gui.toastManager(). These run only on >=26.2 nodes (26.1.x keeps the
        // Minecraft-level members), layering on top of the >=1.21.11 forward-port above.
        regex {
            direction.set(is26_2Plus)
            replace("\\.getToastManager\\(\\)", ".gui.toastManager()", "\\.gui\\.toastManager\\(\\)", ".getToastManager()")
        }
        regex {
            direction.set(is26_2Plus)
            replace("\\.setScreen\\(", ".gui.setScreen(", "\\.gui\\.setScreen\\(", ".setScreen(")
        }
        regex {
            direction.set(is26_2Plus)
            replace("\\bMinecraft\\.getInstance\\(\\)\\.screen\\b", "Minecraft.getInstance().gui.screen()", "\\bMinecraft\\.getInstance\\(\\)\\.gui\\.screen\\(\\)", "Minecraft.getInstance().screen")
        }
        regex {
            direction.set(is26_2Plus)
            replace("\\bminecraft\\.screen\\b", "minecraft.gui.screen()", "\\bminecraft\\.gui\\.screen\\(\\)", "minecraft.screen")
        }
        regex {
            direction.set(is26_2Plus)
            replace("\\bmc\\.screen\\b", "mc.gui.screen()", "\\bmc\\.gui\\.screen\\(\\)", "mc.screen")
        }
        // MC 26.3 moved RenderPipeline from com.mojang.blaze3d.pipeline to com.mojang.renderpearl.api.pipeline.
        // Both spellings are rewritten: the Java import AND the JVM descriptor in the RecipeButtonMixin @At
        // target string, which a type-only rename would leave pointing at a class that no longer exists
        // (the injection would then fail at runtime, not at compile time).
        regex {
            direction.set(is26_3Plus)
            replace("\\bcom\\.mojang\\.blaze3d\\.pipeline\\.RenderPipeline\\b", "com.mojang.renderpearl.api.pipeline.RenderPipeline", "\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.RenderPipeline\\b", "com.mojang.blaze3d.pipeline.RenderPipeline")
        }
        regex {
            direction.set(is26_3Plus)
            // No leading \b: in a descriptor the class name follows the `L` type tag, a word character.
            replace("Lcom/mojang/blaze3d/pipeline/RenderPipeline;", "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;", "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;", "Lcom/mojang/blaze3d/pipeline/RenderPipeline;")
        }
        // ResourceLocation's factory methods arrived in 1.21; 1.20.x only has the public constructor.
        regex {
            direction.set(pre1_21)
            replace("""\bResourceLocation\.fromNamespaceAndPath\(""", "new ResourceLocation(",
                """\bnew ResourceLocation\((?!"minecraft", )""", "ResourceLocation.fromNamespaceAndPath(")
        }
        regex {
            direction.set(pre1_21)
            replace("""\bResourceLocation\.withDefaultNamespace\(""", """new ResourceLocation("minecraft", """,
                """\bnew ResourceLocation\("minecraft", """, "ResourceLocation.withDefaultNamespace(")
        }
        // RecipeBookMenu gained a second type parameter in 1.21 (<Container> -> <RecipeInput, Recipe>).
        regex {
            direction.set(pre1_21)
            replace("""\bRecipeBookMenu<\?, \?>""", "RecipeBookMenu<?>", """\bRecipeBookMenu<\?>""", "RecipeBookMenu<?, ?>")
        }
        // Data components (1.20.5) renamed the NBT-aware stack comparison.
        regex {
            direction.set(pre1_20_5)
            replace("""\bisSameItemSameComponents\b""", "isSameItemSameTags", """\bisSameItemSameTags\b""", "isSameItemSameComponents")
        }
        // Entity.registryAccess() is absent on 1.20.1 (javap-verified); it is the level's registry access.
        regex {
            direction.set(pre1_20_5)
            replace("""\bplayer\.registryAccess\(\)""", "player.level().registryAccess()",
                """\bplayer\.level\(\)\.registryAccess\(\)""", "player.registryAccess()")
        }
        // RecipeHolder (1.20.2) did not exist yet: point every import/FQN at the id+recipe shim in
        // craft/legacy, which mirrors the record API our code uses (id(), value()).
        regex {
            direction.set(pre1_20_2)
            replace("""\bnet\.minecraft\.world\.item\.crafting\.RecipeHolder\b""",
                "com.legoj15.sprawlcrafting.craft.legacy.RecipeHolder",
                """\bcom\.legoj15\.sprawlcrafting\.craft\.legacy\.RecipeHolder\b""",
                "net.minecraft.world.item.crafting.RecipeHolder")
        }
    }
}

// ─── Production-test jar collection ──────────────────────────────────────────────────────────
// Build every node's PRODUCTION jar (Loom's remapped/intermediary jar for fabric; the MDG jar for
// neoforge and forge — modern NeoForge and Forge 1.21.1 run Mojmaps so dev == prod) and gather them into testing/dist/ for the
// release matrix (testing/Invoke-ReleaseTests.ps1) to stage onto real servers + Prism clients.
// Excludes -sources, Loom's named-mapping -dev jar (which won't run in a production runtime) and
// Forge's -slim jar (the classes-only intermediate; the shipped jar is the jar-in-jar one). On
// 1.20.1-forge (SRG runtime) the shipped jar is Renamer's SRG output; its Mojmap jar-in-jar is the -dev jar.
// Named `buildAndCollect` for uniformity with BuildCraft's task of the same purpose (theirs is
// per-node and collects into build/libs/<version>/; this one is a single root task with an explicit
// node list, collecting into testing/dist/ where the release matrix stages from).
val releaseNodes = listOf("1.20.1-fabric", "1.20.1-forge", "1.21.1-fabric", "1.21.1-neoforge", "1.21.1-forge", "26.1.2-neoforge", "26.1.2-fabric", "26.2-neoforge", "26.2-fabric", "26.3-neoforge", "26.3-fabric")
// Project-scope reads: inside a task block `property()` resolves against the TASK, not the project.
val modVersion = property("version") as String
val distDir = layout.projectDirectory.dir("testing/dist").asFile.toPath()
val mirrorLink = layout.buildDirectory.dir("libs/$modVersion").map { it.asFile.toPath() }
tasks.register<Copy>("buildAndCollect") {
    group = "verification"
    description = "Build every node's production jar and collect them into testing/dist/."
    dependsOn(releaseNodes.map { ":$it:build" })
    releaseNodes.forEach { node ->
        from("versions/$node/build/libs") {
            include("sprawlcrafting-*.jar")
            exclude("*-sources.jar", "*-dev.jar", "*-slim.jar") // -slim: Forge's classes-only jar, pre jar-in-jar
        }
    }
    into(layout.projectDirectory.dir("testing/dist"))
    // A missing mirror link must defeat UP-TO-DATE, else the doLast below never runs to
    // recreate it after a build/-dir wipe (the copy itself is cheap and idempotent).
    outputs.upToDateWhen { java.nio.file.Files.exists(mirrorLink.get(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
    doLast {
        println("Collected production jars into testing/dist/")
        // BuildCraft-parity view: build/libs/<version> is a directory LINK onto testing/dist, so both
        // repos expose the collected jars at the same path without duplicating files. testing/dist
        // stays the real directory (the release matrix stages from it); a clean only removes the
        // link, and this task recreates it on the next run.
        val link = mirrorLink.get()
        if (java.nio.file.Files.notExists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            java.nio.file.Files.createDirectories(link.parent)
            try {
                java.nio.file.Files.createSymbolicLink(link, distDir)
            } catch (e: java.io.IOException) {
                // Windows without the symlink privilege (no Developer Mode): a directory
                // junction needs no privilege and behaves identically for local paths.
                ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), distDir.toString())
                    .inheritIO().start().waitFor()
            }
        }
        println("Mirrored at build/libs/$modVersion -> testing/dist")
    }
}

// ─── Production boot+connect matrix (PowerShell) ─────────────────────────────
// Registered on the ROOT so it runs the cross-version matrix exactly ONCE. The harness under testing/
// is machine-specific and gitignored, so this stage SELF-SKIPS when the script is absent (e.g. a
// third-party clone) instead of failing.
val releaseMatrixScript = rootProject.file("testing/Invoke-ReleaseTests.ps1")
tasks.register<Exec>("runReleaseMatrix") {
    group = "verification"
    description = "Production boot+connect matrix (testing/Invoke-ReleaseTests.ps1, PowerShell 7). Skipped if the harness isn't checked out."
    workingDir = rootProject.projectDir
    // -SkipBuild: fullTestSuite already ran buildAndCollect (fresh +mc jars), so the harness must NOT
    // start a nested `gradlew buildAndCollect` inside this running build — it would deadlock on the
    // project lock. The matrix still wipes each server's mods/ and copies the fresh jar every run.
    // NOTE: the matrix's 1.12.2-forge leg stages the reobf Forge jar, which is built by the SEPARATE
    // forge-1.12.2 build (host JDK 25) that Gradle here can't reach — the release script's Test stage
    // builds it first. Run standalone: build it yourself (forge-1.12.2> gradlew reobfJar) beforehand.
    commandLine("pwsh", "-NoProfile", "-File", releaseMatrixScript.absolutePath, "-SkipBuild")
    // Build fresh jars before the matrix stages them (only orders when both are in the graph).
    mustRunAfter("buildAndCollect")
    onlyIf {
        val present = releaseMatrixScript.exists()
        if (!present) logger.lifecycle("runReleaseMatrix: testing/Invoke-ReleaseTests.ps1 not present — skipping the production boot+connect stage.")
        present
    }
}

// ─── Full pre-release verification (modern tree) ─────────────────────────────
// One command for the modern tree: every node's unit tests (run by buildAndCollect's per-node build)
// + the production boot+connect matrix. buildAndCollect is listed here (not left to the script) so the
// OUTER Gradle builds the fresh jars — the matrix then runs with -SkipBuild and never starts a nested
// buildAndCollect that would deadlock on this build's project lock.
// NOTE: the legacy 1.12.2 Forge jar + its solver tests live in the SEPARATE forge-1.12.2 build (host
// JDK 25); Gradle can't span both JDKs in one invocation, so the release script's Test stage builds +
// tests that tree FIRST, then runs this. Standalone, build the Forge jar yourself first if you want
// the 1.12.2-forge matrix leg to find it.
tasks.register("fullTestSuite") {
    group = "verification"
    description = "Modern-tree unit tests (via buildAndCollect) + production boot/connect matrix (auto-skips if the testing/ harness is absent)."
    dependsOn("buildAndCollect", "runReleaseMatrix")
}
