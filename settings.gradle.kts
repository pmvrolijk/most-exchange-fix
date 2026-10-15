rootProject.name = "most-exchange-fix"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// The adapter SDK (`nl.lamia.most.exchange:client` and `:sbe`, Apache-2.0) comes from most-exchange's
// package registry on GitHub Packages (decided 2026-10-09, docs/Rationale.md §3). Only that group
// comes from there, and it comes from nowhere else.
//
// GitHub Packages wants credentials even to read a public package. Never in this repository:
//   - in a GitHub Actions job, GITHUB_ACTOR and the job's GITHUB_TOKEN (`packages: read`);
//   - locally, `githubUser` and a classic token with `read:packages` as `githubPackagesToken`, in
//     ~/.gradle/gradle.properties. Not as GITHUB_TOKEN in the shell: `gh` would use it over its login.
//
// `mostSdk.url` names the registry, so moving the projects to another host is one property.
val mostSdkUrl = providers.gradleProperty("mostSdk.url").get()
val sdkUser = providers.gradleProperty("githubUser")
    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
val sdkToken = providers.gradleProperty("githubPackagesToken")
    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull

dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "mostSdk"
                    url = uri(mostSdkUrl)
                    credentials {
                        username = sdkUser ?: ""
                        password = sdkToken ?: ""
                    }
                }
            }
            filter { includeGroup("nl.lamia.most.exchange") }
        }
        mavenCentral()
    }
}

// Changing the SDK and the adapter together: `-PmostExchange.local=true` (or the same line in
// ~/.gradle/gradle.properties) builds ../most-exchange's `client` and `sbe` in place of the published
// ones, so a change to the SDK is tried here before it is published.
if (providers.gradleProperty("mostExchange.local").map(String::toBoolean).getOrElse(false)) {
    includeBuild("../most-exchange")
}

include(
    "fix-session",  // Artio acceptor, logon, session persistence: shared by both backends
    "order-entry",  // FIX orders and cancels <-> SBE, through the SDK's OrderEntrySession
    "market-data",  // L1/L2 from the SDK's DepthFeedAssembler -> FIX market data
    "spikes",       // Design.md §8.13: what Artio 0.182 really does, against a real initiator. Not shipped
)
