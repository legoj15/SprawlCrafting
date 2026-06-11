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
