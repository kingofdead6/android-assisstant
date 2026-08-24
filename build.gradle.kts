/**
 * Root build file.
 *
 * Deliberately declares no plugins. Declaring the Android Gradle Plugin here —
 * even with `apply false` — forces Gradle to resolve it from Google's Maven
 * repository during configuration of *every* build, which breaks the JVM-only
 * `-PskipAndroidModules=true :core:test` path used by environments without
 * Android SDK access. Each module declares the plugins it actually applies.
 */

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
