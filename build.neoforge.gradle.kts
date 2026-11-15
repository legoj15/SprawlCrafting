// Per-node NeoForge build (ModDevGradle). Runs once per <mc>-neoforge version node.
// moddev's version is declared `apply false` in stonecutter.gradle.kts (the controller).
plugins {
    id("net.neoforged.moddev")
}

// Per-node values, resolved from versions/<node>/gradle.properties (inheriting root gradle.properties).
val minecraftVersion = property("minecraft_version") as String
val neoVersion = property("neoforge_version") as String
val jeiVersion = property("jei_version") as String
val javaVersion = (property("java_version") as String).toInt()
// Parchment is 1.x-only (no post-cutoff 26.x release); null on nodes that omit the props.
val parchmentMc = project.findProperty("parchment_minecraft") as String?
val parchmentVersion = project.findProperty("parchment_version") as String?
val modId = property("mod_id") as String
val modVersion = property("version") as String
// Resolved at project scope: inside tasks.processResources {} `property()` resolves against
// the task, not the project.
val modName = property("mod_name") as String
val modAuthor = property("mod_author") as String
val modDescription = property("description") as String
val modLicense = property("license") as String
val modCredits = property("credits") as String
val neoLoaderRange = property("neoforge_loader_version_range") as String
val neoVersionRange = property("neoforge_version_range") as String
val mcVersionRange = property("minecraft_version_range") as String
// REI ships on the 1.21.1 line only; its presence is signalled by a node-level rei_version.
val hasRei = project.findProperty("rei_version") != null
// Applied Energistics 2 (optional): its Crafting Terminal counts as a crafting station (issue #2).
// Signalled by a node-level ae2_version; without it compat/ae2 is not compiled at all.
val ae2Version = project.findProperty("ae2_version") as String?
val hasAe2 = ae2Version != null

// Sophisticated Core/Storage/Backpacks compat (compat/sophisticated): official NeoForge builds exist for 1.21.1
// and 26.x; a node opts in by pinning sophisticated_core_version (+ the storage / backpacks pins).
val sophisticatedCoreVersion = project.findProperty("sophisticated_core_version") as String?
val sophisticatedStorageVersion = project.findProperty("sophisticated_storage_version") as String?
val sophisticatedBackpacksVersion = project.findProperty("sophisticated_backpacks_version") as String?
// All three pins or none: the package compiles against all three, and stonecutter.gradle.kts derives the
// `sophisticated` constant from the same rule, so the gated call sites and the exclusion never disagree.
val hasSophisticated = sophisticatedCoreVersion != null && sophisticatedStorageVersion != null
        && sophisticatedBackpacksVersion != null
// -Psc.sophisticated=true: run the dev client/server with Sophisticated Core/Storage/Backpacks. Core declares a
// minimum JEI newer than the build's compile pin, so that runtime uses sophisticated_jei_version instead.
val sophisticatedRuntime = hasSophisticated && project.findProperty("sc.sophisticated") == "true"

// ─── Stonecutter source replacements ─────────────────────────────────────────────────────────
// The forward-port regex tiers (ResourceLocation -> Identifier, Minecraft.screen -> gui.screen(),
// RenderPipeline -> renderpearl, ...) are defined ONCE for every node in the controller,
// stonecutter.gradle.kts, inside `stonecutter parameters { replacements { ... } }`.

java {
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
}

// ─── Solver library ──────────────────────────────────────────────────────────────────────────
// The Minecraft-free planner is the separate library com.legoj15:solver (solver-core/, its own build and
// its own JUnit suite). settings.gradle.kts substitutes it from this checkout (composite build). It is
// bundled jar-in-jar, NEVER shaded: FML loads the nested copy as a JPMS module, and another mod that
// nests the same library (the bot mod) must resolve to ONE copy, not two split packages. The range lets
// FML pick the newest bundled 1.x across mods.
val solverVersion = property("solver_version") as String
val solverRange = "[$solverVersion,${solverVersion.substringBefore('.').toInt() + 1}.0.0)"

// ModDevGradle only wires Minecraft onto `main` by default. Extend the `test` source set with main's
// classpath + output so MC-touching unit tests (the payload codecs) AND the in-game game tests under
// src/test/.../gametest compile and run on this node (mirrors the proven BuildCraft setup).
run {
    val mainSourceSet = sourceSets["main"]
    sourceSets["test"].apply {
        compileClasspath += mainSourceSet.compileClasspath + mainSourceSet.output
        runtimeClasspath += mainSourceSet.runtimeClasspath + mainSourceSet.output
    }
}

repositories {
    mavenLocal()
    maven("https://maven.neoforged.net/releases")
    maven("https://maven.blamejared.com")   // JEI
    maven("https://maven.shedaniel.me/")     // REI
    maven("https://maven.parchmentmc.org")
    // Modrinth's maven (AE2, Sophisticated Core/Storage/Backpacks; artifact = project id), scoped to its own group
    // so no other dependency is ever looked up there.
    maven("https://api.modrinth.com/maven") {
        content { includeGroup("maven.modrinth") }
    }
    mavenCentral()
}

neoForge {
    version = neoVersion
    if (parchmentMc != null && parchmentVersion != null) {
        parchment {
            minecraftVersion = parchmentMc
            mappingsVersion = parchmentVersion
        }
    }
    // src/ lives at the Tree root, not in versions/<node>/, so resolve from rootProject.
    val at = rootProject.file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) accessTransformers.from(at)

    runs {
        register("client") {
            client()
            gameDirectory = project.file("run")
            // Test harness hook: -Psc.connect=host:port auto-connects the dev client (quick-play).
            (project.findProperty("sc.connect") as String?)?.let {
                programArgument("--quickPlayMultiplayer")
                programArgument(it)
            }
            // Test harness hook: -Psc.world="<save folder>" auto-loads a singleplayer world (quick-play).
            (project.findProperty("sc.world") as String?)?.let {
                programArgument("--quickPlaySingleplayer")
                programArgument(it)
            }
            // Dev diagnostic: -Psc.gfx=vulkan|opengl|default forces MC 26.2's graphics backend.
            (project.findProperty("sc.gfx") as String?)?.let {
                programArgument("--graphicsBackend")
                programArgument(it)
            }
        }
        register("server") {
            server()
            gameDirectory = project.file("run-server")
        }
        // Headless game-test server: boots, runs every registered GameTest, exits non-zero on any
        // failure. Task name: runGameTestServer. The mod includes the `test` source set below so the
        // gametest registration (src/test/.../gametest) is discovered.
        register("gameTestServer") {
            type = "gameTestServer"
            systemProperty("neoforge.enableGameTest", "true")
            gameDirectory = project.file("run-gametest")
        }
    }
    mods {
        register(modId) {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["test"]) // game tests + their registration live in src/test
        }
    }
}

dependencies {
    // Which recipe viewer loads in the dev runtime (runClient/runServer) is selectable via
    // -Pviewer=jei|rei|both|none (default jei). APIs stay compileOnly, so `build`/`test` are
    // unaffected. REI is 1.21.1-only, so -Pviewer=rei is a no-op on 26.x.
    val viewer = (project.findProperty("viewer") as String?) ?: "jei"

    // JEI: API for compile, full mod on the dev runtime so runClient exercises the integration.
    compileOnly("mezz.jei:jei-$minecraftVersion-common-api:$jeiVersion")
    compileOnly("mezz.jei:jei-$minecraftVersion-neoforge-api:$jeiVersion")
    if (viewer == "jei" || viewer == "both") {
        val jeiRuntimeVersion =
            if (sophisticatedRuntime) (project.findProperty("sophisticated_jei_version") as String?) ?: jeiVersion else jeiVersion
        runtimeOnly("mezz.jei:jei-$minecraftVersion-neoforge:$jeiRuntimeVersion")
    }

    if (hasRei) {
        // Compile against the full NeoForge jar for @REIPluginClient (not in the API jar);
        // non-transitive to keep architectury/cloth off the COMPILE classpath.
        val reiVersion = property("rei_version") as String
        compileOnly("me.shedaniel:RoughlyEnoughItems-neoforge:$reiVersion") { isTransitive = false }
        // REI's EntryStacks references architectury's FluidStack, needed at compile for the R/U
        // ViewSearchBuilder lookup. compileOnly + non-transitive; the runtime gets it via REI below.
        compileOnly("dev.architectury:architectury:13.0.6") { isTransitive = false }
        if (viewer == "rei" || viewer == "both") {
            // Transitive (pulls architectury/cloth) so runClient -Pviewer=rei actually loads REI.
            runtimeOnly("me.shedaniel:RoughlyEnoughItems-neoforge:$reiVersion")
        }
    }

    if (hasAe2) {
        // AE2 (optional): compile only; the shipped jar never requires it (compat/ae2 is reached
        // reflectively once ModList reports "ae2" loaded). -Psc.ae2=true also puts AE2 and GuideME (its
        // hard runtime dependency) on the dev runtime, so runClient can open a Crafting Terminal.
        compileOnly("maven.modrinth:ae2:$ae2Version")
        if ((project.findProperty("sc.ae2") as String?) == "true") {
            runtimeOnly("maven.modrinth:ae2:$ae2Version")
            runtimeOnly("maven.modrinth:guideme:${property("guideme_version")}")
        }
    }

    // Sophisticated Core (nmoqTijg), Storage (hMlaZH8f), Backpacks (TyCTlI4b): compile-only, the player
    // supplies them. -Psc.sophisticated=true also puts all three on the dev runtime (runClient/runServer)
    // so the crafting-upgrade integration can be exercised in game. Their POMs declare no dependencies.
    if (hasSophisticated) {
        compileOnly("maven.modrinth:nmoqTijg:$sophisticatedCoreVersion")
        compileOnly("maven.modrinth:hMlaZH8f:$sophisticatedStorageVersion")
        compileOnly("maven.modrinth:TyCTlI4b:$sophisticatedBackpacksVersion")
        if (sophisticatedRuntime) {
            runtimeOnly("maven.modrinth:nmoqTijg:$sophisticatedCoreVersion")
            runtimeOnly("maven.modrinth:hMlaZH8f:$sophisticatedStorageVersion")
            runtimeOnly("maven.modrinth:TyCTlI4b:$sophisticatedBackpacksVersion")
        }
    }

    // The solver library: compiled against and on the dev runtime (implementation), nested in the
    // shipped jar with a [current, next-major) range (jarJar). On the 1.x lines FML does not load a
    // plain non-mod jar from the JVM classpath (runServer dies with NoClassDefFoundError on the first
    // solver class), so it also goes on MDG's additionalRuntimeClasspath there. 26.x needs no extra
    // classpath (MDG rejects additionalRuntimeClasspath on it: "no additional classpath anymore").
    implementation("com.legoj15:solver:$solverVersion")
    if (minecraftVersion.startsWith("1.")) {
        "additionalRuntimeClasspath"("com.legoj15:solver:$solverVersion")
    }
    jarJar("com.legoj15:solver") {
        version {
            strictly(solverRange)
            prefer(solverVersion)
        }
    }

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Keep the other loader's source — and, where REI is absent, the REI compat — out of this compile.
tasks.withType<JavaCompile>().configureEach {
    exclude("**/platform/fabric/**")
    exclude("**/platform/forge/**")    // MinecraftForge platform layer
    if (!hasRei) {
        exclude("**/compat/rei/**")
        exclude("**/*ReiPlugin*")
    }
    if (!hasAe2) {
        exclude("**/compat/ae2/**")    // AE2 Crafting Terminal as a crafting station (issue #2)
    }
    // AE2's JEI plugin (compat/ae2/jei) needs the JEI API, as in build.forge.gradle.kts. Every NeoForge
    // node pins jei_version today (this script reads it unconditionally above), so this cannot trigger
    // yet; it keeps the two scripts' rule identical for the day a node drops JEI.
    if (project.findProperty("jei_version") == null) {
        exclude("**/compat/ae2/jei/**")
    }

    if (!hasSophisticated) {
        exclude("**/compat/sophisticated/**")
    }
    // Surface the full cross-cliff error set during the 26.x port (javac caps at 100 by default).
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "2000", "-Xmaxwarns", "200"))
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
        "credits" to modCredits,
        "neoforge_version_range" to neoVersionRange,
        "neoforge_loader_version_range" to neoLoaderRange,
        "minecraft_version_range" to mcVersionRange
    )
    inputs.properties(replaceProperties)
    filesMatching("META-INF/neoforge.mods.toml") { expand(replaceProperties) }
    // Don't ship the Fabric or (MinecraftForge) Forge metadata in the NeoForge jar.
    exclude("fabric.mod.json")
    exclude("META-INF/mods.toml")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// ModDevGradle must wait for Stonecutter to emit this node's preprocessed sources
// before it resolves/compiles them, or compileJava races stonecutterGenerate.
tasks.named("createMinecraftArtifacts") {
    dependsOn("stonecutterGenerate")
}

tasks.jar {
    archiveBaseName = "sprawlcrafting"
    archiveVersion = "$modVersion+mc$minecraftVersion-neoforge"
}
