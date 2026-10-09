// The exchange's L1/L2 feeds, assembled by the SDK's DepthFeedAssembler, out as FIX market data,
// and the directory out as a SecurityList (docs/Design.md §4).

dependencies {
    implementation(project(":fix-session"))
    implementation(libs.most.client)
}
