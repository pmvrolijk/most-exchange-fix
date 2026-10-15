// The Artio spikes of docs/Design.md §8.13: each answers, against a real Artio engine, library and
// initiator, a question the persistence design (§6.2–§6.4) or the threading (§3.5) rests on. Nothing
// here ships; the findings go into Design.md, and the tests stay so that an Artio bump re-asks them.

dependencies {
    testImplementation(libs.artio.core)
    testImplementation(libs.artio.session.codecs)
}

tasks.test {
    // A spike's output is its finding.
    testLogging.showStandardStreams = true
}

// Two minutes, with SIGKILLs and mark-file waits: run on purpose (`./gradlew :spikes:test`), and
// after any Artio or Aeron bump, not on every build.
tasks.named("check") { setDependsOn(emptyList<Any>()) }
