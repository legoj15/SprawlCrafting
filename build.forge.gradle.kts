// Per-node MinecraftForge build (ForgeGradle 7). Runs once per <mc>-forge version node. This is
// MinecraftForge, NOT NeoForge (build.neoforge.gradle.kts): different plugin, different loader API,
// different mods.toml. The plugin's version is declared `apply false` in stonecutter.gradle.kts.
//
// Forge for 1.21.1 runs Mojang's official names at runtime (no reobf since 1.20.6), so the shared
// Mojmap source compiles against Forge directly, exactly like the NeoForge node.
//
// Forge BELOW 1.20.6 (the 1.20.1 node, Forge 47) still runs SRG names (m_/f_) at runtime. The source is
// the same Mojmap source (dev runs are Mojmap), but the shipped jar must be reobfuscated: on those nodes
// (`srgRuntime` below) this script also applies the Renamer plugin, runs the Mixin + MixinExtras
// annotation processors to write an SRG refmap, renames SRG-published dependencies (JEI) to dev names,
// and renames the final jar to SRG. Everything SRG-only is behind `if (srgRuntime)`, so the 1.21.1 node's
// build is unchanged. One script rather than a sibling: the two lines share every other line of it.
plugins {
    id("java")
    id("net.minecraftforge.gradle")
    // Jar-in-jar for MixinExtras (the one thing Forge 1.21.1 does not bundle that our mixins need).
    id("net.minecraftforge.jarjar")
}

// SRG-at-runtime Forge lines (see the header). Forge dropped reobf in 1.20.6.
val srgRuntime = stonecutter.eval(stonecutter.current.version, "<1.20.6")
// Imperative (not in plugins {}) so the 1.21.1 node never applies it; the version comes from the
// controller's `apply false` declaration, which also puts its classes on this script's classpath.
// Resolved here at project scope (inside dependencies {} `the<>()` would hit the DependencyHandler).
val renamer: net.minecraftforge.renamer.gradle.RenamerExtension? = if (srgRuntime) {
    apply(plugin = "net.minecraftforge.renamer")
    the<net.minecraftforge.renamer.gradle.RenamerExtension>()
} else null

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
val jeiVersionRange = property("jei_version_range") as String
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

// Old-line adjustments shared with build.fabric-o.gradle.kts (mixin compatibilityLevel below Java 21,
// PayloadCodecTest exclusion below 1.20.5). No-ops on the 1.21.1 node.
apply(from = rootProject.file("gradle/legacy-node.gradle.kts"))

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
            // so name the shared config on the command line for every dev run. On SRG nodes Renamer's
            // enableMixinRefmaps (below) already adds `--mixin.config <config>` to every run.
            if (!srgRuntime) {
                args("--mixin.config=$modId.mixins.json")
            }
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
    // common-api is published in Mojmap on every line.
    compileOnly("mezz.jei:jei-$minecraftVersion-common-api:$jeiVersion")
    if (srgRuntime) {
        // On SRG lines JEI's Forge jars are published reobfuscated (SRG): rename them to dev names.
        // The runtime jar carries an SRG access transformer, renamed too so it applies in dev.
        val rn = renamer!!
        compileOnly(rn.dependency("mezz.jei:jei-$minecraftVersion-forge-api:$jeiVersion"))
        if (viewer == "jei" || viewer == "both") {
            runtimeOnly(rn.dependency("mezz.jei:jei-$minecraftVersion-forge:$jeiVersion") {
                accessTransformers.set(true)
            })
            // JEI 15.74 hard-depends on mezz_config, which it ships jar-in-jar. Renamer does not touch
            // nested jars, so in dev that copy keeps its SRG references (NoSuchMethodError on boot).
            // A renamed copy of the same version on the dev classpath is what loads instead.
            (project.findProperty("mezz_config_version") as String?)?.let {
                runtimeOnly(rn.dependency("net.mezzdev.config:mezz_config-$minecraftVersion-forge:$it"))
            }
        }
    } else {
        compileOnly("mezz.jei:jei-$minecraftVersion-forge-api:$jeiVersion")
        if (viewer == "jei" || viewer == "both") {
            runtimeOnly("mezz.jei:jei-$minecraftVersion-forge:$jeiVersion")
        }
    }

    // MixinExtras: compile against the common API, load the Forge loader jar on the dev runtime, and
    // jar-in-jar it into the shipped mod (the JarJar plugin constrains the bundled copy to
    // [version,), so another mod's newer MixinExtras wins).
    compileOnly("io.github.llamalad7:mixinextras-common:$mixinExtrasVersion")
    runtimeOnly("io.github.llamalad7:mixinextras-forge:$mixinExtrasVersion")
    "jarJar"("io.github.llamalad7:mixinextras-forge:$mixinExtrasVersion")

    if (srgRuntime) {
        // Annotation processors that write the refmap (Mojmap -> SRG) Renamer ships in the jar: Mixin's
        // own (the same 0.8.5 Forge 47 bundles) for @Inject/@Shadow/@Accessor, and MixinExtras' for
        // @WrapOperation, which Mixin's processor does not know.
        annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
        annotationProcessor("io.github.llamalad7:mixinextras-common:$mixinExtrasVersion")
    }

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
        "minecraft_version_range" to mcVersionRange,
        "jei_version_range" to jeiVersionRange
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

// ─── SRG reobfuscation (Forge < 1.20.6 only) ─────────────────────────────────────────────────
// Chain on these nodes: jar (Mojmap classes + the AP's SRG refmap, "-slim") -> jarJar (+ nested
// MixinExtras, "-dev"; still Mojmap, not for players) -> renameJarJar (Mojmap -> SRG classes, no classifier:
// the shipped sprawlcrafting-<ver>+mc<mc>-forge.jar). The root buildAndCollect skips -slim and -dev.
if (srgRuntime) {
    val rn = renamer!!
    // Default mapping for every Renamer task here (the JEI dependency renames above included): the
    // Mavenizer's named -> SRG map for this node's Minecraft dependency.
    rn.mappings(minecraft.dependency.toSrg)
    // Mixin AP config: writes the refmap in SRG plus the extra mappings (mixin-class shadows/accessors)
    // renameJarJar needs to rename those members too.
    rn.enableMixinRefmaps {
        config("$modId.mixins.json")
        refMap.set("$modId.refmap.json")
    }
    tasks.named<Jar>("jarJar") {
        archiveClassifier = "dev"
    }
    val renameJar = rn.classes(tasks.named<Jar>("jarJar")) {
        // RenameJar is not an archive task (no archiveVersion): name the output file directly.
        archiveClassifier.set("")
        output.set(layout.buildDirectory.file("libs/$jarBaseName-$jarVersion.jar"))
        mappings(rn.mixin.generatedMappings)
    }
    tasks.named("assemble") { dependsOn(renameJar) }
}
