// Per-node MinecraftForge build (ForgeGradle 7). Runs once per <mc>-forge version node. This is
// MinecraftForge, NOT NeoForge (build.neoforge.gradle.kts): different plugin, different loader API,
// different mods.toml. The plugin's version is declared `apply false` in stonecutter.gradle.kts.
//
// Forge for 1.21.1 and 26.x runs Mojang's official names at runtime (no reobf since 1.20.6; 26.x is
// unobfuscated), so the shared Mojmap source compiles against Forge directly, exactly like the NeoForge
// nodes. Nodes: 1.21.1 (Java 21, Parchment, JEI, jar-in-jarred MixinExtras) and 26.1.2 / 26.2 / 26.3
// (Java 25, pure Mojmap, no JEI because no Forge JEI exists for 26.x, MixinExtras bundled by Forge itself).
// Which of those extras a node gets is decided purely by which props its gradle.properties sets.
//
// Forge BELOW 1.20.6 (the 1.20.1 node, Forge 47) still runs SRG names (m_/f_) at runtime. The source is
// the same Mojmap source (dev runs are Mojmap), but the shipped jar must be reobfuscated: on those nodes
// (`srgRuntime` below) this script also applies the Renamer plugin, runs the Mixin + MixinExtras
// annotation processors to write an SRG refmap, renames SRG-published dependencies (JEI) to dev names,
// and renames the final jar to SRG. Everything SRG-only is behind `if (srgRuntime)`, so the other nodes'
// builds are unchanged. One script rather than a sibling: the lines share every other line of it.
plugins {
    id("java")
    id("net.minecraftforge.gradle")
    // Jar-in-jar: the solver library on every node, plus MixinExtras where the Forge line does not bundle it
    // (1.20.1 / 1.21.1, the nodes that pin mixinextras_version).
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
// JEI has a Forge build on the 1.21.1 line only (no Forge JEI exists for 26.x); null on nodes that omit the prop.
val jeiVersion = project.findProperty("jei_version") as String?
// MixinExtras is jar-in-jarred only where the Forge line does not bundle it (1.21.1); null elsewhere.
val mixinExtrasVersion = project.findProperty("mixinextras_version") as String?
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
// mods.toml optional-dependency range for JEI; only read on nodes that pin a jei_version.
val jeiVersionRange = (project.findProperty("jei_version_range") as String?) ?: ""
// REI has no Forge build on any line; this stays false unless a future Forge node pins rei_version.
val hasRei = project.findProperty("rei_version") != null
// JEI's compat layer (compat/jei, @JeiPlugin) exists only on nodes that pin a jei_version.
val hasJei = jeiVersion != null
// Jar naming (sprawlcrafting-<ver>+mc<mc>-forge.jar), shared by the slim `jar` and the shipped `jarJar`.
val jarBaseName = "sprawlcrafting"
val jarVersion = "$modVersion+mc$minecraftVersion-forge"

// ─── Stonecutter source replacements (forward-port 1.21.1-canonical source to 26.x) ──────────
// SHARED: the regex rename tiers (ResourceLocation -> Identifier, Minecraft.screen -> gui.screen(),
// RenderPipeline -> renderpearl, ...) live ONCE in the controller (stonecutter.gradle.kts, inside
// `stonecutter parameters { replacements { ... } }`) and apply to every node, so this script needs no
// copy. On the 1.21.1 node every tier's direction is false, i.e. they are no-ops; the 26.x nodes run them.

java {
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
}

// Old-line adjustments shared with build.fabric-o.gradle.kts (mixin compatibilityLevel below Java 21,
// PayloadCodecTest exclusion below 1.20.5). No-ops on the 1.21.1 node.
apply(from = rootProject.file("gradle/legacy-node.gradle.kts"))

// ─── Solver library ──────────────────────────────────────────────────────────────────────────
// The Minecraft-free planner is the separate library com.legoj15:solver (solver-core/, its own build and
// its own JUnit suite). settings.gradle.kts substitutes it from this checkout (composite build). It is
// bundled jar-in-jar on EVERY Forge node, NEVER shaded: FML loads the nested copy as a JPMS module, and
// another mod that nests the same library (the bot mod) must resolve to ONE copy, not two split packages.
// The range lets FML pick the newest bundled 1.x across mods.
val solverVersion = property("solver_version") as String
val solverRange = "[$solverVersion,${solverVersion.substringBefore('.').toInt() + 1}.0.0)"

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

// Dev-run classpath for the solver library on the SRG (FML 47) line: FML 47 does not load a plain
// non-mod jar from the JVM classpath (runServer dies with NoClassDefFoundError on the first solver
// class), so it goes on Forge's minecraft_classpath via the run's extraLibraries below. FG7's 1.20.6+
// launch puts the whole JVM classpath in the boot module layer, so the other nodes need nothing extra.
val solverDevLibrary = configurations.create("solverDevLibrary") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies { solverDevLibrary("com.legoj15:solver:$solverVersion") }

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
            } else {
                extraLibraries.from(solverDevLibrary)
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
    // Only on nodes that pin a jei_version (Forge 1.20.1 / 1.21.1); nothing is resolved or fetched otherwise.
    if (jeiVersion != null) {
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
    }

    // The solver library: compiled against and on the dev runtime (implementation), nested in the
    // shipped jar with a [current, next-major) range (jarJar). Never shaded (see the header above).
    implementation("com.legoj15:solver:$solverVersion")
    jarJar.configure("jarJar"("com.legoj15:solver:$solverVersion")!!) { setRange(solverRange) }

    // MixinExtras: compile against the common API, load the Forge loader jar on the dev runtime, and
    // jar-in-jar it into the shipped mod (the JarJar plugin constrains the bundled copy to
    // [version,), so another mod's newer MixinExtras wins).
    if (mixinExtrasVersion != null) {
        compileOnly("io.github.llamalad7:mixinextras-common:$mixinExtrasVersion")
        runtimeOnly("io.github.llamalad7:mixinextras-forge:$mixinExtrasVersion")
        "jarJar"("io.github.llamalad7:mixinextras-forge:$mixinExtrasVersion")
    }

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
    if (!hasJei) {
        exclude("**/compat/jei/**")    // no Forge JEI build on this line (26.x)
    }
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
        "jei_version_range" to jeiVersionRange,
        "has_jei" to hasJei
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
    // The plain classes-only jar is the "-slim" intermediate; the shipped jar is `jarJar`'s output.
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
// Chain on these nodes: jar (Mojmap classes + the AP's SRG refmap, "-slim") -> jarJar (+ nested solver
// library + MixinExtras, "-dev"; still Mojmap, not for players) -> renameJarJar (Mojmap -> SRG classes,
// no classifier: the shipped sprawlcrafting-<ver>+mc<mc>-forge.jar). Renamer leaves the nested jars
// alone, which is right for both: neither references Minecraft. The root buildAndCollect skips -slim and -dev.
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
        // Renamer otherwise writes fernflower_abstract_parameter_names.txt into the shipped jar.
        additionalArgs.add("--disable-abstract-param")
    }
    tasks.named("assemble") { dependsOn(renameJar) }
}
