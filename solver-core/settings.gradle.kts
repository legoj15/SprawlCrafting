// solver-core: the Minecraft-free crafting planner, published as the plain-Java library
// com.legoj15:solver. A STANDALONE build: every SprawlCrafting build (root Stonecutter tree,
// legacy-1.16.5/, forge-1.12.2/) pulls it in as a Gradle composite (includeBuild) so local dev needs
// no publish step, and JitPack publishes it on its own (jitpack.yml at the repo root).
// Build it alone with the root wrapper:  ./gradlew -p solver-core build publishToMavenLocal
plugins {
    // Auto-provisions the Java 8 toolchain below on machines (and JitPack) without a local JDK 8.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "solver"
