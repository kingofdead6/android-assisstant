package com.john.assistant.core.speech

import kotlinx.coroutines.flow.Flow

/** A wake-word firing. */
data class WakeWordDetection(
    val phrase: String,
    val confidence: Float,
    val timestampMillis: Long,
)

/**
 * Always-on detection of "Hey John".
 *
 * Hard requirement: implementations run **entirely on the device**. Audio
 * captured while waiting for the wake word is never written to disk and never
 * leaves the phone — that is the whole point of a local-first assistant, and
 * it is the property the microphone-permission rationale promises the user.
 */
interface WakeWordEngine {

    val displayName: String

    /** The phrase this engine is trained or configured for. */
    val phrase: String

    /** False when weights are missing, so the UI can point at the model manager. */
    val isAvailable: Boolean

    fun isRunning(): Boolean

    /** Begin listening. Idempotent. */
    fun start()

    fun stop()

    /** Fires once per detection. Cold flows may start the engine on collection. */
    fun detections(): Flow<WakeWordDetection>

    /**
     * 0..1. Higher means fewer false accepts and more misses. Engines that
     * cannot be tuned ignore this.
     */
    var sensitivity: Float
}
