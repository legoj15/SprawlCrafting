// Per-node Fabric build for OLD MC lines (1.x), using Loom's remapping variant against the
// shared Mojmap source. Modern CalVer lines (26.x) use build.fabric-m.gradle.kts (plain Loom).
plugins {
    id("net.fabricmc.fabric-loom-remap")
}

// Per-node values, resolved from versions/<node>/gradle.properties (inheriting root gradle.properties).
val minecraftVersion = property("minecraft_version") as String
val fabricLoaderVersion = property("fabric_loader_version") as String
// The fabricloader floor written into fabric.mod.json. Defaults to the dev-runtime pin; a node sets
// fabric_loader_min when its dev runtime needs a newer loader than players do (1.20.1: JEI 15.74 forces
// 0.19.4 in dev, while fabric-api 0.92.12 itself only needs 0.16.10).
val fabricLoaderMin = (project.findProperty("fabric_loader_min") as String?) ?: fabricLoaderVersion
val fabricApiVersion = property("fabric_version") as String
val jeiVersion = property("jei_version") as String
val javaVersion = (property("java_version") as String).toInt()
val parchmentMc = property("parchment_minecraft") as String
val parchmentVersion = property("parchment_version") as String
val modId = property("mod_id") as String
val modVersion = property("version") as String
// Resolved at project scope: inside tasks.processResources {} `property()` resolves against
// the task, not the project.
val modName = property("mod_name") as String
val modAuthor = property("mod_author") as String
val modDescription = property("description") as String
val modLicense = property("license") as String
val hasRei = project.findProperty("rei_version") != null
// Mod Menu provides the in-game config-screen button on Fabric; present only on nodes that pin a
// modmenu_version (1.21.1). Where absent, the integration class is excluded (same gating as REI).
val hasModMenu = project.findProperty("modmenu_version") != null
java {
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
}

// Old-line adjustments shared with build.forge.gradle.kts (mixin compatibilityLevel below Java 21,
// PayloadCodecTest exclusion below 1.20.5). No-ops on the 1.21.1 node.
apply(from = rootProject.file("gradle/legacy-node.gradle.kts"))

// ─── Shared solver-core module ───────────────────────────────────────────────────────────────
// The loader/version-agnostic craft solver (RecipeGraphSolver + PlannedStep + its test suite) lives
// in its own pure-Java-8 module at solver-core/, so the planned legacy-Forge 1.12.2 build can compile
// the EXACT same source (Java 8 has no records/sealed types). It carries no Stonecutter directives and
// no Minecraft imports, so it just folds into this node's main+test source sets and ships inside the
// mod jar like any other source — single source of truth, zero duplication.
sourceSets["main"].java.srcDir(rootProject.file("solver-core/src/main/java"))
sourceSets["test"].java.srcDir(rootProject.file("solver-core/src/test/java"))

repositories {
    mavenLocal()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.blamejared.com")   // JEI
    maven("https://maven.shedaniel.me/")     // REI
    maven("https://maven.terraformersmc.com/releases") // Mod Menu
    maven("https://maven.parchmentmc.org")
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-$parchmentMc:$parchmentVersion@zip")
    })
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Which recipe viewer loads in the dev runtime (runClient/runServer) is selectable via
    // -Pviewer=jei|rei|both|none (default jei). The APIs stay (mod)compileOnly regardless, so
    // `build`/`test` are unaffected. On Loom the runtime mod MUST go through a mod* configuration
    // (modLocalRuntime) so it's remapped intermediary→Mojmap and the loader actually loads it as a
    // mod; a plain runtimeOnly jar would be on the classpath but never loaded.
    val viewer = (project.findProperty("viewer") as String?) ?: "jei"

    // JEI: common-api is Mojmap (matches our mappings); the runtime fabric jar is Loom-remapped.
    compileOnly("mezz.jei:jei-$minecraftVersion-common-api:$jeiVersion")
    modCompileOnly("mezz.jei:jei-$minecraftVersion-fabric-api:$jeiVersion")
    if (viewer == "jei" || viewer == "both") {
        modLocalRuntime("mezz.jei:jei-$minecraftVersion-fabric:$jeiVersion")
        // Newer JEI builds (15.74 on 1.20.1) hard-depend on mezz_config. Production players get it
        // jar-in-jar inside JEI, but Loom's dev runtime doesn't load nested jars, so add it explicitly
        // on nodes that pin a mezz_config_version.
        (project.findProperty("mezz_config_version") as String?)?.let {
            modLocalRuntime("net.mezzdev.config:mezz_config-$minecraftVersion-fabric:$it")
        }
    }

    if (hasRei) {
        val reiVersion = property("rei_version") as String
        modCompileOnly("me.shedaniel:RoughlyEnoughItems-api:$reiVersion")
        if (viewer == "rei" || viewer == "both") {
            // Full Fabric jar (+ architectury/cloth transitively), Loom-remapped, so
            // runClient -Pviewer=rei actually loads REI in the dev environment.
            modLocalRuntime("me.shedaniel:RoughlyEnoughItems-fabric:$reiVersion")
        }
    }

    // Mod Menu API: compile-only, Loom-remapped (intermediary→Mojmap) like the JEI/REI mod APIs, so
    // SprawlModMenuIntegration sees Mojmap Screen. The player supplies the Mod Menu mod at runtime.
    if (hasModMenu) {
        modCompileOnly("com.terraformersmc:modmenu:${property("modmenu_version")}")
    }

    // The craft solver core is pure Java and unit-tested without Minecraft.
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

loom {
    val aw = rootProject.file("src/main/resources/$modId.accesswidener")
    if (aw.exists()) accessWidenerPath = aw
    mixin {
        defaultRefmapName = "$modId.refmap.json"
    }
    runs {
        named("client") {
            client()
            ideConfigGenerated(true)
            runDir("run")
            // Test harness hook: -Psc.connect=host:port auto-connects the dev client (quick-play).
            (project.findProperty("sc.connect") as String?)?.let { programArgs("--quickPlayMultiplayer", it) }
            // Test harness hook: -Psc.world="<save folder>" auto-loads a singleplayer world (quick-play).
            (project.findProperty("sc.world") as String?)?.let { programArgs("--quickPlaySingleplayer", it) }
        }
        named("server") {
            server()
            ideConfigGenerated(true)
            runDir("run-server")
        }
    }
}

// Keep the other loader's source — and, where REI is absent, the REI compat — out of this compile.
tasks.withType<JavaCompile>().configureEach {
    exclude("**/platform/neoforge/**")
    exclude("**/platform/forge/**")    // MinecraftForge platform layer
    exclude("**/gametest/neoforge/**") // NeoForge-specific game-test registration (src/test)
    if (!hasRei) {
        exclude("**/compat/rei/**")
        exclude("**/*ReiPlugin*")
    }
    if (!hasModMenu) {
        exclude("**/*ModMenu*") // no Mod Menu build for this node → drop the integration class
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val replaceProperties = mapOf(
        "mod_id" to modId,
        "version" to modVersion,
        "mod_name" to modName,
        "mod_author" to modAuthor,
        "description" to modDescription,
        "license" to modLicense,
        "fabric_loader_min" to fabricLoaderMin,
        "minecraft_version" to minecraftVersion,
        "java_version" to javaVersion.toString()
    )
    inputs.properties(replaceProperties)
    filesMatching("fabric.mod.json") { expand(replaceProperties) }
    // Don't ship the NeoForge or Forge metadata in the Fabric jar.
    exclude("META-INF/neoforge.mods.toml")
    exclude("META-INF/mods.toml")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Stonecutter must emit this node's preprocessed sources before Loom compiles them.
tasks.named("compileJava") {
    dependsOn("stonecutterGenerate")
}

tasks.named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("remapJar") {
    archiveBaseName.set("sprawlcrafting")
    archiveVersion.set("$modVersion+mc$minecraftVersion-fabric")
}
