/*
 * Compile-time stubs for symbols published to Google's Maven repository.
 *
 * These exist ONLY for `tools/verify-android-sources.sh`, which type-checks the
 * app's Android code in environments that can reach Maven Central but not
 * dl.google.com. They are never part of the app build — a normal Gradle /
 * Android Studio build resolves the real artifacts.
 *
 * Each stub mirrors the real declaration closely enough that a call site which
 * compiles here compiles against the real thing. When verified code starts
 * using a new AndroidX or Hilt symbol, add its stub alongside these.
 */
package dagger

@Target(AnnotationTarget.CLASS)
annotation class Module

@Target(AnnotationTarget.FUNCTION)
annotation class Provides

@Target(AnnotationTarget.FUNCTION)
annotation class Binds
