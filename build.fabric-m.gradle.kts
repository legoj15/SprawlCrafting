// Per-node Fabric build for MODERN CalVer MC lines (26.x), using PLAIN Loom
// (net.fabricmc.fabric-loom) against the shared source. 26.x is the first unobfuscated MC line, so
// it is pure Mojmap with no source remapping and no Parchment layer (old 1.x lines use
// build.fabric-o.gradle.kts / fabric-loom-remap instead). The Loom version is declared once in the
// Tree controller (stonecutter.gradle.kts) and applied version-less here.
plugins {
    id("net.fabricmc.fabric-loom")
}

// Per-node values, resolved from versions/<node>/gradle.properties (inheriting root gradle.properties).
val minecraftVersion = property("minecraft_version") as String
val fabricLoaderVersion = property("fabric_loader_version") as String
// fabricloader floor for fabric.mod.json (see build.fabric-o.gradle.kts); defaults to the dev pin.
val fabricLoaderMin = (project.findProperty("fabric_loader_min") as String?) ?: fabricLoaderVersion
val fabricApiVersion = property("fabric_version") as String
val jeiVersion = property("jei_version") as String
val javaVersion = (property("java_version") as String).toInt()
val modId = property("mod_id") as String
val modVersion = property("version") as String
// Resolved at project scope: inside tasks.processResources {} `property()` resolves against
// the task, not the project.
val modName = property("mod_name") as String
val modAuthor = property("mod_author") as String
val modDescription = property("description") as String
val modLicense = property("license") as String
val hasRei = project.findProperty("rei_version") != null
// Mod Menu (Fabric config-screen button) is present only on nodes that pin a modmenu_version. The
// 26.1.x line pins 18.x (a pre-release; see gradle.properties), so hasModMenu is true here.
val hasModMenu = project.findProperty("modmenu_version") != null

// ─── Stonecutter source replacements ─────────────────────────────────────────────────────────
// The forward-port regex tiers (ResourceLocation -> Identifier, Minecraft.screen -> gui.screen(),
// RenderPipeline -> renderpearl, ...) are defined ONCE for every node in the controller,
// stonecutter.gradle.kts, inside `stonecutter parameters { replacements { ... } }`.

java {
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
}

// ─── Solver library ──────────────────────────────────────────────────────────────────────────
// The Minecraft-free planner is the separate library com.legoj15:solver (solver-core/, its own build and
// its own JUnit suite). settings.gradle.kts substitutes it from this checkout (composite build), and the
// dependency below bundles it jar-in-jar (Loom `include`) so the shipped jar carries it nested.
val solverVersion = property("solver_version") as String

repositories {
    mavenLocal()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.blamejared.com")   // JEI
    maven("https://maven.shedaniel.me/")     // REI (unused on 26.x; kept for parity)
    maven("https://maven.terraformersmc.com/releases") // Mod Menu
    // Modrinth's maven (AE2, for nodes that pin ae2_version), scoped to its own group so no other
    // dependency is ever looked up there.
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") }
        filter { includeGroup("maven.modrinth") }
    }
    mavenCentral()
}

dependencies {
    // 26.1 is the first UNOBFUSCATED Minecraft: it already ships its final (Mojmap) names, so Loom
    // takes NO mappings dependency and does NOT create the mod*/mappings configurations (there is
    // nothing to remap). Dependencies are consumed via plain Gradle configurations — exactly as
    // FabricMC's fabric-example-mod 26.1.2 does (implementation, no modImplementation, no mappings).
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Which recipe viewer loads in the dev runtime (runClient/runServer) is selectable via
    // -Pviewer=jei|rei|both|none (default jei). The API stays compileOnly regardless, so `build`/
    // `test` are unaffected. REI is 1.21.1-only, so -Pviewer=rei is a no-op on 26.x.
    val viewer = (project.findProperty("viewer") as String?) ?: "jei"

    // JEI: common-api is the cross-loader API; the fabric jar is the dev runtime impl.
    compileOnly("mezz.jei:jei-$minecraftVersion-common-api:$jeiVersion")
    compileOnly("mezz.jei:jei-$minecraftVersion-fabric-api:$jeiVersion")
    if (viewer == "jei" || viewer == "both") {
        runtimeOnly("mezz.jei:jei-$minecraftVersion-fabric:$jeiVersion")
    }

    if (hasRei) {
        // No REI build exists for 26.x, so rei_version is unset there and this block is skipped.
        val reiVersion = property("rei_version") as String
        compileOnly("me.shedaniel:RoughlyEnoughItems-api:$reiVersion")
        if (viewer == "rei" || viewer == "both") {
            // Full Fabric jar (+ architectury/cloth transitively) so runClient -Pviewer=rei loads REI.
            runtimeOnly("me.shedaniel:RoughlyEnoughItems-fabric:$reiVersion")
        }
    }

    // Mod Menu API (plain compileOnly on this unobfuscated node). The 26.1.x line pins modmenu_version
    // (18.x), so hasModMenu is true and SprawlModMenuIntegration is compiled in.
    if (hasModMenu) {
        compileOnly("com.terraformersmc:modmenu:${property("modmenu_version")}")
    }

    // Applied Energistics 2 (optional; issue #2): compile only, for nodes that pin ae2_version, so
    // compat/ae2 (the Crafting Terminal as a crafting station) compiles. Plain compileOnly on this
    // unobfuscated node. The shipped jar never requires AE2 (the compat is reached reflectively once the
    // loader reports "ae2"). Compiling needs no GuideME: AE2 references it only from its guide classes,
    // which the compat never touches (build.neoforge.gradle.kts adds it on its dev runtime alone).
    (project.findProperty("ae2_version") as String?)?.let { compileOnly("maven.modrinth:ae2:$it") }

    // The solver library: compiled against, on the dev runtime, and nested in the shipped jar. Loom
    // generates a fabric.mod.json for the nested plain jar so Fabric Loader loads it as a library mod.
    include(implementation("com.legoj15:solver:$solverVersion")!!)

    // Unit tests for the MC-touching wire contracts (the solver's own suite runs in solver-core/).
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
            // Dev diagnostic: -Psc.gfx=vulkan|opengl|default forces MC 26.2's graphics backend.
            (project.findProperty("sc.gfx") as String?)?.let { programArgs("--graphicsBackend", it) }
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
    exclude("**/compat/sophisticated/**") // Sophisticated Core/Storage/Backpacks have no official Fabric build
    exclude("**/gametest/neoforge/**") // NeoForge-specific game-test registration (src/test)
    if (!hasRei) {
        exclude("**/compat/rei/**")
        exclude("**/*ReiPlugin*")
    }
    if (!hasModMenu) {
        exclude("**/*ModMenu*") // no Mod Menu build for this node → drop the integration class
    }
    // AE2 Crafting Terminal station (issue #2): compiled only where a node pins ae2_version (none on
    // Fabric yet; the compileOnly AE2 dependency above is wired). Opting in also needs a new
    // "jei_mod_plugin" entrypoint in fabric.mod.json for compat.ae2.jei.StationJeiPlugin (NeoForge finds
    // it by annotation scan; Fabric does not), and an AE2 build for that MC version (#27).
    if (project.findProperty("ae2_version") == null) {
        exclude("**/compat/ae2/**")
    }
    if (project.findProperty("jei_version") == null) {
        exclude("**/compat/ae2/jei/**") // AE2 without JEI: the server-side station still compiles
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

// 26.1 unobfuscated: plain Loom does NOT add a remapJar step, so the standard `jar` task IS the
// production jar (named to match the other nodes: sprawlcrafting-<ver>+mc26.1.2-fabric.jar).
tasks.named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("jar") {
    archiveBaseName.set("sprawlcrafting")
    archiveVersion.set("$modVersion+mc$minecraftVersion-fabric")
}
