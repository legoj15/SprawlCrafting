// com.legoj15:solver — the pure-Java crafting planner (RecipeGraphSolver, PlannedStep, ProducedLedger,
// UndoMap, CountFormat). No Minecraft types, no runtime dependencies. Java 8 bytecode because the
// oldest consumers (forge-1.12.2, legacy-1.16.5) run on Java 8.
plugins {
    `java-library`
    `maven-publish`
}

group = "com.legoj15"
version = property("solver_version") as String

java {
    // Compile AND run the JUnit suite on a real Java 8 runtime: proves the library works on the
    // 1.12.2/1.16.5 runtimes, not just that it emits Java 8 class files.
    toolchain { languageVersion = JavaLanguageVersion.of(8) }
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        // A stable JPMS name: Forge/NeoForge load the jar-in-jar copy as a module. Every mod that
        // bundles this library must bundle it NESTED (jar-in-jar), never shaded, or two copies of the
        // com.legoj15.solver package collide as a split package.
        attributes(
            "Automatic-Module-Name" to "com.legoj15.solver",
            "Implementation-Title" to "solver",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "legoj15",
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "solver"
            from(components["java"])
            pom {
                name = "SprawlCrafting solver"
                description = "Minecraft-free recipe-graph crafting planner used by SprawlCrafting."
                licenses {
                    license {
                        name = "MIT"
                    }
                }
            }
        }
    }
}
