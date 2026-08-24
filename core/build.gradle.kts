plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Fake engines live in `testFixtures` so the Android module's tests can reuse
    // them instead of maintaining a second set of stubs that drifts out of sync.
    `java-test-fixtures`
}

/**
 * `:core` is deliberately a plain Kotlin/JVM module.
 *
 * It holds everything that decides *what John should do* — the tool contract,
 * the tool registry, LLM/STT/TTS/wake-word contracts, tool-call parsing,
 * conversation context, and the risk/confirmation policy. None of it touches
 * the Android SDK, which means:
 *
 *  - the assistant's reasoning is unit-testable on a plain JVM, with no
 *    emulator, no Robolectric and no Android SDK installed;
 *  - the Android layer can only ever be a *provider* of tools, never a
 *    dependency of the decision logic.
 */
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testFixturesApi(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core")))
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
