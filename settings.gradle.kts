pluginManagement {
    repositories {
        mavenLocal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" } // ForgeGradle 7 (1.21.1-forge node)
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        maven("https://maven.parchmentmc.org") { name = "ParchmentMC" }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.6"
}

stonecutter {
    create(rootProject) {
        // One node per (MC version, loader). Phase 2a ships the modernised 1.21.1 pair;
        // the 26.1.2 pair is added in Phase 2b once the API port lands.
        fun match(version: String, vararg loaders: String) =
            loaders.forEach { version("$version-$it", version).buildscript = getBuildscript(it, version) }

        // 1.20.1 backport. Same Loom-remap build path as 1.21.1 for Fabric; the pre-1.20.2/1.20.5/1.21 API
        // cliffs are handled by `//? if <1.20.x` source branches plus the back-port regex tiers in the controller.
        match("1.20.1", "fabric")
        match("1.21.1", "fabric", "neoforge", "forge")
        // Phase 2b complete: NeoForge-26 surfaced the API cliffs; the code port now compiles, so the
        // 26.1.2-fabric node (modern plain Loom, build.fabric-m.gradle.kts) brings Fabric to parity.
        match("26.1.2", "neoforge", "fabric", "forge")
        // 26.2 and 26.3 reuse the 26.x build path (modern Loom / MDG, the >=1.21.11 source branch).
        // Their API breaks are handled by the >=26.2 / >=26.3 forward-port tiers in the per-node build
        // scripts plus a few `//? if >=26.3` source branches. Mixin targets were javap-checked against each
        // node's PINNED MC build only; see versions/26.2-neoforge/gradle.properties for the range caveat.
        match("26.2", "neoforge", "fabric", "forge")
        match("26.3", "neoforge", "fabric", "forge")

        vcsVersion = "1.21.1-fabric"
    }
}

// Old MC lines (1.x) use Loom's remapping variant against the shared Mojmap source; modern
// CalVer lines (26.x) use plain Loom. NeoForge uses ModDevGradle on every line; MinecraftForge
// (only the 1.21.1 line so far) uses ForgeGradle 7 (build.forge.gradle.kts).
fun getBuildscript(loader: String, version: String): String {
    if (loader == "fabric") {
        return if (version.startsWith("1.")) "build.fabric-o.gradle.kts" else "build.fabric-m.gradle.kts"
    }
    return "build.$loader.gradle.kts"
}

rootProject.name = "SprawlCrafting"
