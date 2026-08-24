package com.john.assistant.core.speech

/** Where John's voice should come out. */
enum class AudioRoute {
    /** Let the platform decide — follows Bluetooth/headset routing automatically. */
    AUTOMATIC,
    SPEAKER,
    BLUETOOTH,
}

data class SpeechSettings(
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val voiceId: String? = null,
    val languageTag: String = "en-US",
    val route: AudioRoute = AudioRoute.AUTOMATIC,
)

/**
 * Speech synthesis.
 *
 * [speak] suspends until the utterance finishes (or is stopped) so the
 * orchestrator can sequence "say this, then listen again" without racing the
 * microphone against its own voice.
 */
interface TextToSpeechEngine {

    val displayName: String

    val runsLocally: Boolean

    val isReady: Boolean

    suspend fun speak(text: String, settings: SpeechSettings = SpeechSettings())

    /** Abandon the current utterance and clear the queue. */
    fun stop()

    /** Voices the engine can offer in settings. */
    suspend fun availableVoices(): List<VoiceOption> = emptyList()

    fun shutdown()
}

data class VoiceOption(
    val id: String,
    val displayName: String,
    val languageTag: String,
    /** True when the voice is on the device rather than streamed. */
    val isLocal: Boolean,
)
