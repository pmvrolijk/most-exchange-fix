rootProject.name = "most-exchange-fix"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// The adapter SDK (`nl.lamia.most.exchange:client` and `:sbe`, Apache-2.0) comes from most-exchange's
// package registry (decided 2026-10-04, docs/Rationale.md §3). Only that group comes from there, and
// it comes from nowhere else.
//
// Credentials, never in this repository:
//   - in a GitLab pipeline, the job's own token (CI_JOB_TOKEN; most-exchange must allow this project
//     in its job token allowlist);
//   - locally, a personal access token with `read_api`, as `gitlabToken` in ~/.gradle/gradle.properties.
// The registry's certificate is signed by a private CA, which the JVM running Gradle must trust:
// tools/trust-gitlab-ca.sh (docs/Status.md §1).
//
// `mostSdk.url` names the registry, so moving the projects to another host is one property.
val mostSdkUrl = providers.gradleProperty("mostSdk.url").get()
val ciJobToken = providers.environmentVariable("CI_JOB_TOKEN").orNull
val personalToken = providers.gradleProperty("gitlabToken").orNull

dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "mostSdk"
                    url = uri(mostSdkUrl)
                    credentials(HttpHeaderCredentials::class) {
                        name = if (ciJobToken != null) "Job-Token" else "Private-Token"
                        value = ciJobToken ?: personalToken ?: ""
                    }
                    authentication { create<HttpHeaderAuthentication>("header") }
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
)
