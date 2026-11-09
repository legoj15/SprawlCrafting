// ─── Shared build logic for the OLD-MC nodes (Java < 21 and/or MC < 1.20.5) ─────────────────────
// Applied by every per-node script whose loader has a 1.20.x node:
//   build.fabric-o.gradle.kts (1.20.1-fabric) and build.forge.gradle.kts (1.20.1-forge)
// with `apply(from = rootProject.file("gradle/legacy-node.gradle.kts"))`, AFTER the java plugin is applied.
// Both adjustments below are conditional, so on the 1.21.1+ nodes this script changes nothing.
import dev.kikugie.stonecutter.build.StonecutterBuildExtension

val legacyJavaVersion = (property("java_version") as String).toInt()
val legacyModId = property("mod_id") as String
val legacySc = the<StonecutterBuildExtension>()

// The mixin config is authored for Java 21 (compatibilityLevel "JAVA_21"). A node on an older Java
// (1.20.1 = 17) must declare a level its runtime supports, or Mixin rejects the config at boot.
if (legacyJavaVersion < 21) {
    val mixinJava = "JAVA_$legacyJavaVersion"
    tasks.named<ProcessResources>("processResources") {
        inputs.property("mixin_java", mixinJava)
        filesMatching("$legacyModId.mixins.json") { filter { it.replace("\"JAVA_21\"", "\"$mixinJava\"") } }
    }
}

// Pre-1.20.5 lines have no StreamCodec/CustomPacketPayload.Type: payloads serialize through
// write/read(FriendlyByteBuf) instead, so the StreamCodec wire test can't compile there
// (LegacyPayloadBufTest covers the legacy format on those nodes).
if (legacySc.eval(legacySc.current.version, "<1.20.5")) {
    tasks.withType<JavaCompile>().configureEach {
        exclude("**/network/PayloadCodecTest*")
    }
}
