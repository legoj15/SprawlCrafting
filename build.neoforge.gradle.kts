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

// ─── Stonecutter source replacements (forward-port 1.21.1-canonical source to 26.x) ──────────
// The shared source is authored in the 1.21.1 form. `direction` is true only on >=1.21.11 nodes
// (i.e. 26.x), so the forward rename runs there; on 1.21.1 the reverse pair runs but matches
// nothing in the canonical source (no-op), keeping the green 1.21.1 node untouched. Word
// boundaries (\b) avoid clobbering substrings (e.g. ResourceLocationArgument, IdentifierException).
val is1_21_11Plus = stonecutter.eval(stonecutter.current.version, ">=1.21.11")
// 26.2 is the first MC line that diverges from the 26.1.x API (not just from 1.21.1), so it needs its
// own forward-port tier layered on top of the >=1.21.11 one below.
val is26_2Plus = stonecutter.eval(stonecutter.current.version, ">=26.2")
stonecutter {
    replacements {
        // MC 1.21.11 renamed net.minecraft.resources.ResourceLocation -> Identifier (same package).
        regex {
            direction.set(is1_21_11Plus)
            replace("\\bResourceLocation\\b", "Identifier", "\\bIdentifier\\b", "ResourceLocation")
        }
        regex {
            direction.set(is1_21_11Plus)
            replace("\\bResourceLocationArgument\\b", "IdentifierArgument", "\\bIdentifierArgument\\b", "ResourceLocationArgument")
        }
        // net.minecraft.Util moved to net.minecraft.util.Util.
        regex {
            direction.set(is1_21_11Plus)
            replace("\\bnet\\.minecraft\\.Util\\b", "net.minecraft.util.Util", "\\bnet\\.minecraft\\.util\\.Util\\b", "net.minecraft.Util")
        }
        // GameRules moved into the net.minecraft.world.level.gamerules subpackage.
        regex {
            direction.set(is1_21_11Plus)
            replace("net\\.minecraft\\.world\\.level\\.GameRules\\b", "net.minecraft.world.level.gamerules.GameRules", "net\\.minecraft\\.world\\.level\\.gamerules\\.GameRules\\b", "net.minecraft.world.level.GameRules")
        }
        // MC 26.2 moved screen/overlay/toast management off Minecraft and onto the Minecraft.gui (Gui)
        // instance: minecraft.screen -> minecraft.gui.screen(); setScreen(..) -> gui.setScreen(..);
        // getToastManager() -> gui.toastManager(). These run only on >=26.2 nodes (26.1.x keeps the
        // Minecraft-level members), layering on top of the >=1.21.11 forward-port above.
        regex {
            direction.set(is26_2Plus)
            replace("\\.getToastManager\\(\\)", ".gui.toastManager()", "\\.gui\\.toastManager\\(\\)", ".getToastManager()")
        }
        regex {
            direction.set(is26_2Plus)
            replace("\\.setScreen\\(", ".gui.setScreen(", "\\.gui\\.setScreen\\(", ".setScreen(")
        }
        regex {
            direction.set(is26_2Plus)
            replace("\\bMinecraft\\.getInstance\\(\\)\\.screen\\b", "Minecraft.getInstance().gui.screen()", "\\bMinecraft\\.getInstance\\(\\)\\.gui\\.screen\\(\\)", "Minecraft.getInstance().screen")
        }
        regex {
            direction.set(is26_2Plus)
            replace("\\bminecraft\\.screen\\b", "minecraft.gui.screen()", "\\bminecraft\\.gui\\.screen\\(\\)", "minecraft.screen")
        }
        regex {
            direction.set(is26_2Plus)
            replace("\\bmc\\.screen\\b", "mc.gui.screen()", "\\bmc\\.gui\\.screen\\(\\)", "mc.screen")
        }
    }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
}

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
        runtimeOnly("mezz.jei:jei-$minecraftVersion-neoforge:$jeiVersion")
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

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Keep the other loader's source — and, where REI is absent, the REI compat — out of this compile.
tasks.withType<JavaCompile>().configureEach {
    exclude("**/platform/fabric/**")
    if (!hasRei) {
        exclude("**/compat/rei/**")
        exclude("**/*ReiPlugin*")
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
    // Don't ship the Fabric metadata in the NeoForge jar.
    exclude("fabric.mod.json")
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
