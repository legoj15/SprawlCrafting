// Per-node Fabric build for OLD MC lines (1.x), using Loom's remapping variant against the
// shared Mojmap source. Modern CalVer lines (26.x) use build.fabric-m.gradle.kts (plain Loom).
plugins {
    id("net.fabricmc.fabric-loom-remap")
}

// Per-node values, resolved from versions/<node>/gradle.properties (inheriting root gradle.properties).
val minecraftVersion = property("minecraft_version") as String
val fabricLoaderVersion = property("fabric_loader_version") as String
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

java {
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
}

repositories {
    mavenLocal()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.blamejared.com")   // JEI
    maven("https://maven.shedaniel.me/")     // REI
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

    // JEI: common-api is Mojmap (matches our mappings); the runtime fabric jar is Loom-remapped.
    compileOnly("mezz.jei:jei-$minecraftVersion-common-api:$jeiVersion")
    modCompileOnly("mezz.jei:jei-$minecraftVersion-fabric-api:$jeiVersion")
    modLocalRuntime("mezz.jei:jei-$minecraftVersion-fabric:$jeiVersion")

    if (hasRei) {
        // API only for compile (Loom-remapped). Runtime REI is left to the tester's install.
        val reiVersion = property("rei_version") as String
        modCompileOnly("me.shedaniel:RoughlyEnoughItems-api:$reiVersion")
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

tasks.named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("remapJar") {
    archiveBaseName.set("sprawlcrafting")
    archiveVersion.set("$modVersion+mc$minecraftVersion-fabric")
}
