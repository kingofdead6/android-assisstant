package com.john.assistant.core.speech

import kotlinx.coroutines.flow.Flow

/**
 * Raw audio handed to a recogniser.
 *
 * 16 kHz mono 16-bit PCM is the common denominator for on-device recognisers
 * (Whisper and the TFLite/ONNX ports all expect it), so it is the contract here
 * rather than an implementation detail of one engine.
 */
data class AudioBuffer(
    val samples: ShortArray,
    val sampleRateHz: Int = 16_000,
    val channels: Int = 1,
) {
    val durationMillis: Long
        get() = if (sampleRateHz <= 0) 0 else samples.size * 1000L / (sampleRateHz * channels)

    // ShortArray has reference equality by default; a value-like data class needs these.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioBuffer) return false
        return sampleRateHz == other.sampleRateHz &&
            channels == other.channels &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int =
        (samples.contentHashCode() * 31 + sampleRateHz) * 31 + channels
}

/** Outcome of one transcription attempt. */
sealed interface TranscriptionResult {
    data class Success(val text: String, val confidence: Float = 1f) : TranscriptionResult

    /** The user said nothing intelligible. Distinct from an engine failure. */
    data object NoSpeech : TranscriptionResult

    data class Failure(val message: String, val cause: Throwable? = null) : TranscriptionResult
}

/** Interim state emitted while the user is still speaking. */
sealed interface ListeningEvent {
    data object Started : ListeningEvent
    data class PartialTranscript(val text: String) : ListeningEvent
    /** RMS in dB, for driving the listening orb. */
    data class Level(val rms: Float) : ListeningEvent
    data class Finished(val result: TranscriptionResult) : ListeningEvent
}

/**
 * Speech recognition.
 *
 * Two entry points on purpose: [listen] for the live microphone path (which the
 * platform recogniser implements natively and streams partial results from),
 * and [transcribe] for a buffer already captured — which is what an on-device
 * Whisper build needs, since it works on complete utterances.
 */
interface SpeechToTextEngine {

    val displayName: String

    /** False for engines that send audio off-device; surfaced in the privacy screen. */
    val runsLocally: Boolean

    val isAvailable: Boolean

    /** Capture from the microphone and stream recognition events until silence. */
    fun listen(languageTag: String = "en-US"): Flow<ListeningEvent>

    /** Transcribe already-captured audio. */
    suspend fun transcribe(audio: AudioBuffer, languageTag: String = "en-US"): TranscriptionResult

    /** Stop an in-flight [listen]. */
    fun cancel()
}
