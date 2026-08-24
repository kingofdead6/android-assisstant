package com.john.assistant.core.util

/**
 * Logging seam.
 *
 * `android.util.Log` is not reachable from `:core`, and it should not be: the
 * decision pipeline is unit-tested on a plain JVM, and tests want to *assert*
 * on what was logged rather than watch it disappear into logcat.
 */
interface AssistantLogger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, error: Throwable? = null)
    fun error(tag: String, message: String, error: Throwable? = null)

    companion object {
        val NONE: AssistantLogger = object : AssistantLogger {
            override fun debug(tag: String, message: String) = Unit
            override fun info(tag: String, message: String) = Unit
            override fun warn(tag: String, message: String, error: Throwable?) = Unit
            override fun error(tag: String, message: String, error: Throwable?) = Unit
        }
    }
}

/** Wall-clock seam so time-dependent behaviour is testable. */
fun interface TimeSource {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = TimeSource { System.currentTimeMillis() }
    }
}
