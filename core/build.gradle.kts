plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * `:core` is deliberately a plain Kotlin/JVM module.
 *
 * It holds everything that decides *what John should do* — the tool contract,
 * the tool registry, LLM/STT/TTS/wake-word contracts, tool-call parsing,
 * conversation context, and the risk/confirmation policy. None of it touches
 * the Android SDK, which means:
 *
 *  - the assistant's reasoning builds on a plain JVM, with no emulator and no
 *    Android SDK installed;
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
}
