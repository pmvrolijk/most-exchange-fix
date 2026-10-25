// FIX NewOrderSingle / OrderCancelRequest <-> SBE, through the SDK's OrderEntrySession, which
// implements most-exchange docs/Adapters.md §1-§4 (docs/Design.md §3).

dependencies {
    implementation(project(":fix-session"))
    implementation(libs.most.client)
    implementation(libs.rocksdb)
    testImplementation(testFixtures(project(":fix-session")))
}

tasks.test {
    // As the adapter runs: the acceptor refuses to start without it (Design.md §5.4), and the inbound
    // path's 35=3 for an unknown tag depends on it.
    systemProperty("fix.codecs.reject_unknown_field", "true")
}
