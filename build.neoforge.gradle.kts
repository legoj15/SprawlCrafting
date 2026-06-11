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
val modCredits = property("credits") as String
val neoLoaderRange = property("neoforge_loader_version_range") as String
val mcVersionRange = property("minecraft_version_range") as String
// REI ships on the 1.21.1 line only; its presence is signalled by a node-level rei_version.
val hasRei = project.findProperty("rei_version") != null

java {
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
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
    parchment {
        minecraftVersion = parchmentMc
        mappingsVersion = parchmentVersion
    }
    // src/ lives at the Tree root, not in versions/<node>/, so resolve from rootProject.
    val at = rootProject.file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) accessTransformers.from(at)

    runs {
        register("client") {
            client()
            gameDirectory = project.file("run")
        }
        register("server") {
            server()
            gameDirectory = project.file("run-server")
        }
    }
    mods {
        register(modId) {
            sourceSet(sourceSets["main"])
        }
    }
}

dependencies {
    // JEI: API for compile, full mod on the dev runtime so runClient exercises the integration.
    compileOnly("mezz.jei:jei-$minecraftVersion-common-api:$jeiVersion")
    compileOnly("mezz.jei:jei-$minecraftVersion-neoforge-api:$jeiVersion")
    runtimeOnly("mezz.jei:jei-$minecraftVersion-neoforge:$jeiVersion")

    if (hasRei) {
        // Compile against the full NeoForge jar for @REIPluginClient (not in the API jar);
        // non-transitive to avoid pulling architectury/cloth. No runtimeOnly — REI is provided
        // by the tester's own install to avoid double-loading.
        val reiVersion = property("rei_version") as String
        compileOnly("me.shedaniel:RoughlyEnoughItems-neoforge:$reiVersion") { isTransitive = false }
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
        "neoforge_version" to neoVersion,
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
