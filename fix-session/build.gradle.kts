// The FIX side, shared by both backends: the Artio engine and library, the acceptor, logon and
// authentication, session persistence, and the FIX dictionary's generated codecs (docs/Design.md §2).

plugins {
    // AcceptorRig, the Initiator and their helpers: order-entry's tests drive a real Artio session too.
    `java-test-fixtures`
}

val codecGeneration: Configuration by configurations.creating

dependencies {
    api(libs.artio.core)
    api(libs.artio.codecs)
    codecGeneration(libs.artio.codecs)
    // DirectoryBroadcast, the exchange's directory as both backends' tests receive it: SBE only, never main's.
    testFixturesImplementation(libs.most.client)
}

// The adapter's dictionary (Design.md §5.4), generated into codecs by Artio's own tool. The XML is
// itself generated, by src/main/dictionary/extract_fix44.py; it replaces Artio's session codecs.
val fixDictionary = layout.projectDirectory.file("src/main/dictionary/fix44-most.xml")
val generatedCodecs = layout.buildDirectory.dir("generated/sources/fix-codecs/java/main")

val generateFixCodecs = tasks.register<JavaExec>("generateFixCodecs") {
    description = "Generates the FIX codecs from the adapter's dictionary"
    classpath = codecGeneration
    mainClass.set("uk.co.real_logic.artio.dictionary.CodecGenerationTool")
    systemProperty("fix.codecs.parent_package", "nl.lamia.most.exchange.fixadapter.codecs")
    jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED") // Agrona, in the generator too
    inputs.file(fixDictionary)
    outputs.dir(generatedCodecs)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(generatedCodecs.get().asFile.path, fixDictionary.asFile.path)
    })
    doFirst { generatedCodecs.get().asFile.deleteRecursively() }
}

sourceSets.main {
    java.srcDir(generateFixCodecs)
}

tasks.test {
    // As the adapter runs: FixAcceptor refuses to start without it (Design.md §5.4).
    systemProperty("fix.codecs.reject_unknown_field", "true")
    filter { excludeTestsMatching("*.AcceptorGuardTest") }
}

// Artio reads the switch once per JVM, so the refusal to start without it needs a JVM of its own.
val testAcceptorGuard = tasks.register<Test>("testAcceptorGuard") {
    description = "Shows the acceptor refuses to start without Artio's unknown-field rejection"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*.AcceptorGuardTest") }
}

tasks.check { dependsOn(testAcceptorGuard) }
