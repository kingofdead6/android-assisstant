pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "John"

// The pure-Kotlin core is always part of the build. It contains the assistant's
// reasoning pipeline (tools, registry, orchestration, policy) and has no
// dependency on the Android SDK, so it builds and tests on any JVM.
include(":core")

// The Android application requires the Android SDK and Google's Maven repository.
// Environments that have neither (JVM-only CI, sandboxes without SDK access) can
// build and test the core in isolation with:
//
//     ./gradlew -PskipAndroidModules=true :core:test
//
val skipAndroidModules: Boolean =
    (providers.gradleProperty("skipAndroidModules").orNull ?: "false").toBoolean()

if (skipAndroidModules) {
    logger.lifecycle("[John] skipAndroidModules=true — building the JVM core only; :app is excluded.")
} else {
    include(":app")
}
