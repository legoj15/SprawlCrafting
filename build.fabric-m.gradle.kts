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

// ─── Stonecutter source replacements (forward-port 1.21.1-canonical source to 26.x) ──────────
// Same as the neoforge node: the shared source is authored in 1.21.1 form, and these renames run
// only on >=1.21.11 nodes. 26.1 is pure Mojmap, and Mojang renamed these classes regardless of
// loader, so the fabric-26 node needs the identical forward-port (otherwise the source keeps
// ResourceLocation while MC 26.1.2 ships Identifier). On 1.21.1 the reverse pair is a no-op.
val is1_21_11Plus = stonecutter.eval(stonecutter.current.version, ">=1.21.11")
stonecutter {
    replacements {
        regex {
            direction.set(is1_21_11Plus)
            replace("\\bResourceLocation\\b", "Identifier", "\\bIdentifier\\b", "ResourceLocation")
        }
        regex {
            direction.set(is1_21_11Plus)
            replace("\\bResourceLocationArgument\\b", "IdentifierArgument", "\\bIdentifierArgument\\b", "ResourceLocationArgument")
        }
        regex {
            direction.set(is1_21_11Plus)
            replace("\\bnet\\.minecraft\\.Util\\b", "net.minecraft.util.Util", "\\bnet\\.minecraft\\.util\\.Util\\b", "net.minecraft.Util")
        }
        regex {
            direction.set(is1_21_11Plus)
            replace("net\\.minecraft\\.world\\.level\\.GameRules\\b", "net.minecraft.world.level.gamerules.GameRules", "net\\.minecraft\\.world\\.level\\.gamerules\\.GameRules\\b", "net.minecraft.world.level.GameRules")
        }
    }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
}

repositories {
    mavenLocal()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.blamejared.com")   // JEI
    maven("https://maven.shedaniel.me/")     // REI (unused on 26.x; kept for parity)
    maven("https://maven.terraformersmc.com/releases") // Mod Menu
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
        "fabric_loader_version" to fabricLoaderVersion,
        "minecraft_version" to minecraftVersion,
        "java_version" to javaVersion.toString()
    )
    inputs.properties(replaceProperties)
    filesMatching("fabric.mod.json") { expand(replaceProperties) }
    // Don't ship the NeoForge metadata in the Fabric jar.
    exclude("META-INF/neoforge.mods.toml")
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
