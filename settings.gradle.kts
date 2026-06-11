pluginManagement {
    repositories {
        mavenLocal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        maven("https://maven.parchmentmc.org") { name = "ParchmentMC" }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.4"
}

stonecutter {
    create(rootProject) {
        // One node per (MC version, loader). Phase 2a ships the modernised 1.21.1 pair;
        // the 26.1.2 pair is added in Phase 2b once the API port lands.
        fun match(version: String, vararg loaders: String) =
            loaders.forEach { version("$version-$it", version).buildscript = getBuildscript(it, version) }

        match("1.21.1", "fabric", "neoforge")
        // Phase 2b: NeoForge-26 first (proven MDG path + BuildCraft ground truth) to surface the
        // code API cliffs; 26.1.2-fabric (modern Loom) is added once the code port compiles.
        match("26.1.2", "neoforge")

        vcsVersion = "1.21.1-fabric"
    }
}

// Old MC lines (1.x) use Loom's remapping variant against the shared Mojmap source; modern
// CalVer lines (26.x) use plain Loom. NeoForge uses ModDevGradle on every line.
fun getBuildscript(loader: String, version: String): String {
    if (loader == "fabric") {
        return if (version.startsWith("1.")) "build.fabric-o.gradle.kts" else "build.fabric-m.gradle.kts"
    }
    return "build.$loader.gradle.kts"
}

rootProject.name = "SprawlCrafting"
