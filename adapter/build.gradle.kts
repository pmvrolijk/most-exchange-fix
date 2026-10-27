// The adapter as a process: the node file in, the acceptor and its backends running (docs/Design.md §1).
// `installDist` gives build/install/adapter/bin/adapter <node file>.

plugins {
    application
}

dependencies {
    implementation(project(":fix-session"))
    implementation(project(":order-entry"))
    implementation(libs.most.client)
}

application {
    mainClass.set("nl.lamia.most.exchange.fixadapter.AdapterMainKt")
    applicationName = "adapter"
    applicationDefaultJvmArgs = listOf(
        // Agrona 2.x and Artio's ReceiverEndPoints reach into the JDK (most-exchange README, "JVM flags").
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        // The acceptor refuses to start without it (Design.md §5.4).
        "-Dfix.codecs.reject_unknown_field=true",
    )
}

tasks.test {
    systemProperty("fix.codecs.reject_unknown_field", "true")
}

dependencies {
    testImplementation(testFixtures(project(":fix-session")))
}

tasks.test {
    // One shared driver thread and archive thread in the tests, as the rigs elsewhere configure them.
    systemProperty("aeron.threading.mode", "SHARED")
    systemProperty("aeron.archive.threading.mode", "SHARED")
}
