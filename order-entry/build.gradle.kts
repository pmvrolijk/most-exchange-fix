// FIX NewOrderSingle / OrderCancelRequest <-> SBE, through the SDK's OrderEntrySession, which
// implements most-exchange docs/Adapters.md §1-§4 (docs/Design.md §3).

dependencies {
    implementation(project(":fix-session"))
    implementation(libs.most.client)
}
