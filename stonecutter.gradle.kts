// ─── Stonecutter controller (root project build script) ─────────────────────
// This is the Tree controller, NOT the per-node build. The actual mod builds live in the
// per-node scripts assigned in settings.gradle.kts: build.fabric-o.gradle.kts (Loom-remap)
// and build.neoforge.gradle.kts (ModDevGradle). Each runs once per version node.
//
// The loader plugins are declared here with `apply false` so their versions are known to
// every node subproject; each node applies its own (version-less) in its build script.
plugins {
    id("dev.kikugie.stonecutter")
    id("net.fabricmc.fabric-loom-remap") version "1.17.8" apply false
    // Modern CalVer lines (26.x) use PLAIN Loom (unobfuscated MC, no source remapping); same 1.17.x
    // toolchain family as the remap variant above. The 26.1.2-fabric node applies it version-less.
    id("net.fabricmc.fabric-loom") version "1.17.11" apply false
    id("net.neoforged.moddev") version "2.0.141" apply false
}

// The active node — what the IDE/runClient sees and what `compileJava` builds.
// Stonecutter's "Set active project to ..." task rewrites this line.
stonecutter active "1.21.1-fabric" /* [SC] DO NOT EDIT */

stonecutter parameters {
    // Per-node boolean constants for //? directives. The node project is named "<mc>-<loader>",
    // so the trailing segment is the loader: defines fabric/neoforge true for the matching node.
    constants.match(current.project.substringAfterLast('-'), "fabric", "neoforge")
    // REI ships compatible builds on the 1.21.1 line only (no 26.1.2 release), so its compat
    // layer is gated on the MC version rather than the loader.
    constants["rei"] = current.version == "1.21.1"
}

// ─── Production-test jar collection ──────────────────────────────────────────────────────────
// Build every node's PRODUCTION jar (Loom's remapped/intermediary jar for fabric; the MDG jar for
// neoforge — modern NeoForge runs Mojmaps so dev == prod) and gather them into testing/dist/ for the
// release matrix (testing/Invoke-ReleaseTests.ps1) to stage onto real servers + Prism clients.
// Excludes -sources and Loom's named-mapping -dev jar (which won't run in a production runtime).
// Named `buildAndCollect` for uniformity with BuildCraft's task of the same purpose (theirs is
// per-node and collects into build/libs/<version>/; this one is a single root task with an explicit
// node list, collecting into testing/dist/ where the release matrix stages from).
val releaseNodes = listOf("1.21.1-fabric", "1.21.1-neoforge", "26.1.2-neoforge", "26.1.2-fabric", "26.2-neoforge", "26.2-fabric")
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
            exclude("*-sources.jar", "*-dev.jar")
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
