// Test tooling (docs/Design.md §10): `fix`, an Artio initiator on the adapter's dictionary, which the
// e2e scripts drive; `fix perf`, the measuring sender, and `fix report`, its latency report.

plugins {
    application
}

dependencies {
    implementation(project(":fix-session"))
    implementation(libs.hdrhistogram)
    testImplementation(testFixtures(project(":fix-session")))
}

application {
    mainClass.set("nl.lamia.most.exchange.fixadapter.tools.FixMainKt")
    applicationName = "fix"
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "-Daeron.threading.mode=SHARED",
    )
}

tasks.test {
    systemProperty("fix.codecs.reject_unknown_field", "true")
    systemProperty("aeron.threading.mode", "SHARED")
    systemProperty("aeron.archive.threading.mode", "SHARED")
}
