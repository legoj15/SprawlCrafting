// Per-node MinecraftForge build (ForgeGradle 7). Runs once per <mc>-forge version node. This is
// MinecraftForge, NOT NeoForge (build.neoforge.gradle.kts): different plugin, different loader API,
// different mods.toml. The plugin's version is declared `apply false` in stonecutter.gradle.kts.
//
// Forge for 1.21.1 runs Mojang's official names at runtime (no reobf since 1.20.6), so the shared
// Mojmap source compiles against Forge directly, exactly like the NeoForge node.
plugins {
    id("java")
    id("net.minecraftforge.gradle")
    // Jar-in-jar for MixinExtras (the one thing Forge 1.21.1 does not bundle that our mixins need).
    id("net.minecraftforge.jarjar")
}

// Per-node values, resolved from versions/<node>/gradle.properties (inheriting root gradle.properties).
val minecraftVersion = property("minecraft_version") as String
val forgeVersion = property("forge_version") as String
val jeiVersion = property("jei_version") as String
val mixinExtrasVersion = property("mixinextras_version") as String
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
val forgeLoaderRange = property("forge_loader_version_range") as String
val forgeVersionRange = property("forge_version_range") as String
val mcVersionRange = property("minecraft_version_range") as String
// REI has no Forge 1.21.1 build; this stays false unless a future Forge node pins rei_version.
val hasRei = project.findProperty("rei_version") != null
// Jar naming (sprawlcrafting-<ver>+mc<mc>-forge.jar), shared by the slim `jar` and the shipped `jarJar`.
val jarBaseName = "sprawlcrafting"
val jarVersion = "$modVersion+mc$minecraftVersion-forge"

// ─── Stonecutter source replacements (forward-port 1.21.1-canonical source to 26.x) ──────────
// SHARED: the regex rename tiers (ResourceLocation -> Identifier, Minecraft.screen -> gui.screen(),
// RenderPipeline -> renderpearl, ...) live ONCE in the controller (stonecutter.gradle.kts, inside
// `stonecutter parameters { replacements { ... } }`) and apply to every node, so this script needs no
// copy. On this 1.21.1 node every tier's direction is false, i.e. they are no-ops.

java {
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
}

// ─── Shared solver-core module ───────────────────────────────────────────────────────────────
// The loader/version-agnostic craft solver (RecipeGraphSolver + PlannedStep + its test suite) lives
// in its own pure-Java-8 module at solver-core/, so the legacy-Forge 1.12.2 build can compile the
// EXACT same source (Java 8 has no records/sealed types). It carries no Stonecutter directives and
// no Minecraft imports, so it just folds into this node's main+test source sets and ships inside the
// mod jar like any other source — single source of truth, zero duplication.
sourceSets["main"].java.srcDir(rootProject.file("solver-core/src/main/java"))
sourceSets["test"].java.srcDir(rootProject.file("solver-core/src/test/java"))

repositories {
    // ForgeGradle 7's repository helpers: the Minecraft "mavenizer" (builds the patched, mapped
    // Minecraft+Forge artifact on demand), Forge's Maven, and Mojang's libraries Maven.
    minecraft.mavenizer(this)
    maven(fg.forgeMaven)
    maven(fg.minecraftLibsMaven)
    mavenLocal()
    maven("https://maven.blamejared.com")   // JEI
    maven("https://maven.parchmentmc.org")
    mavenCentral()
}

minecraft {
    // Parchment layers param names + javadocs over Mojmap; FG7 wants "<date>-<mc>" for the version.
    if (parchmentMc != null && parchmentVersion != null) {
        mappings("parchment", "$parchmentVersion-$parchmentMc")
    } else {
        mappings("official", minecraftVersion)
    }
    // src/ lives at the Tree root, not in versions/<node>/, so an AT would be found via the shared
    // resources; none exists today. Opt in only when the file does, so FG7 doesn't error on a missing one.
    if (rootProject.file("src/main/resources/META-INF/accesstransformer.cfg").exists()) {
        useDefaultAccessTransformer()
    }

    runs {
        configureEach {
            workingDir.convention(layout.projectDirectory.dir("run"))
            // Mixin has no manifest to read on the dev classpath (the built jar carries MixinConfigs),
            // so name the shared config on the command line for every dev run.
            args("--mixin.config=$modId.mixins.json")
        }
        register("client") {
            // Test harness hook: -Psc.connect=host:port auto-connects the dev client (quick-play).
            (project.findProperty("sc.connect") as String?)?.let { args("--quickPlayMultiplayer", it) }
            // Test harness hook: -Psc.world="<save folder>" auto-loads a singleplayer world (quick-play).
            (project.findProperty("sc.world") as String?)?.let { args("--quickPlaySingleplayer", it) }
        }
        register("server") {
            workingDir.set(layout.projectDirectory.dir("run-server"))
            args("--nogui")
        }
    }
}

// Registers the `jarJar` configuration + task (must precede the dependency declarations below). The
// shipped artifact is the jar-in-jar ("all") jar with no classifier; the plain classes-only `jar`
// becomes the "-slim" intermediate (the root buildAndCollect skips it).
jarJar.register {
    archiveClassifier = null
    archiveBaseName = jarBaseName
    archiveVersion = jarVersion
}

dependencies {
    // Which recipe viewer loads in the dev runtime (runClient/runServer) is selectable via
    // -Pviewer=jei|none (default jei). APIs stay compileOnly, so `build`/`test` are unaffected.
    val viewer = (project.findProperty("viewer") as String?) ?: "jei"

    implementation(minecraft.dependency("net.minecraftforge:forge:$minecraftVersion-$forgeVersion"))

    // JEI: API for compile, full mod on the dev runtime so runClient exercises the integration.
    compileOnly("mezz.jei:jei-$minecraftVersion-common-api:$jeiVersion")
    compileOnly("mezz.jei:jei-$minecraftVersion-forge-api:$jeiVersion")
    if (viewer == "jei" || viewer == "both") {
        runtimeOnly("mezz.jei:jei-$minecraftVersion-forge:$jeiVersion")
    }

    // MixinExtras: compile against the common API, load the Forge loader jar on the dev runtime, and
    // jar-in-jar it into the shipped mod (the JarJar plugin constrains the bundled copy to
    // [version,), so another mod's newer MixinExtras wins).
    compileOnly("io.github.llamalad7:mixinextras-common:$mixinExtrasVersion")
    runtimeOnly("io.github.llamalad7:mixinextras-forge:$mixinExtrasVersion")
    "jarJar"("io.github.llamalad7:mixinextras-forge:$mixinExtrasVersion")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Keep the other loaders' source — and the integrations Forge has no build of — out of this compile.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    exclude("**/platform/fabric/**")
    exclude("**/platform/neoforge/**")
    exclude("**/gametest/neoforge/**") // NeoForge-specific game-test registration (src/test)
    exclude("**/*ModMenu*")            // Mod Menu is Fabric-only
    if (!hasRei) {
        exclude("**/compat/rei/**")
        exclude("**/*ReiPlugin*")
    }
    // Surface the full cross-cliff error set during ports (javac caps at 100 by default).
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
        "forge_version_range" to forgeVersionRange,
        "forge_loader_version_range" to forgeLoaderRange,
        "minecraft_version_range" to mcVersionRange
    )
    inputs.properties(replaceProperties)
    filesMatching("META-INF/mods.toml") { expand(replaceProperties) }
    // Ship ONLY Forge's metadata: not Fabric's, and not NeoForge's (a different file).
    exclude("fabric.mod.json")
    exclude("META-INF/neoforge.mods.toml")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Stonecutter must emit this node's preprocessed sources before anything compiles them.
tasks.named("compileJava") {
    dependsOn("stonecutterGenerate")
}

tasks.jar {
    archiveClassifier = "slim"
    archiveBaseName = jarBaseName
    archiveVersion = jarVersion
    // Forge's Mixin reads this manifest attribute to find the shared config (the dev runs above pass
    // it on the command line instead, since the dev classpath is directories, not a jar).
    manifest {
        attributes["MixinConfigs"] = "$modId.mixins.json"
    }
}
