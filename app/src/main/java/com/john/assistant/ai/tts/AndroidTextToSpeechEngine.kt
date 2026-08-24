package com.john.assistant.ai.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.john.assistant.core.speech.SpeechSettings
import com.john.assistant.core.speech.TextToSpeechEngine
import com.john.assistant.core.speech.VoiceOption
import com.john.assistant.core.util.AssistantLogger
import com.john.assistant.platform.AudioRouter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * John's voice, via the platform speech engine.
 *
 * The platform engine is the right default: it is already installed, most
 * devices ship with offline voice data, and it respects the user's chosen TTS
 * app. A neural engine such as Piper can be swapped in later behind
 * [TextToSpeechEngine] without anything above this class noticing.
 *
 * Two details that are easy to get wrong and matter here:
 *
 *  - [speak] genuinely suspends until the utterance finishes. Without that the
 *    assistant restarts the microphone while it is still talking and
 *    transcribes its own voice.
 *  - Audio attributes are set to USAGE_ASSISTANT (see [AudioRouter]), which is
 *    what makes music duck rather than John talking over it — and what routes
 *    the voice to connected earbuds along with everything else.
 */
@Singleton
class AndroidTextToSpeechEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioRouter: AudioRouter,
    private val logger: AssistantLogger,
) : TextToSpeechEngine {

    override val displayName: String = "Android speech engine"

    /**
     * The platform engine *may* use a network voice depending on which TTS app
     * the user has selected, so this cannot honestly claim to be local. The
     * privacy screen reads this and says "depends on your TTS engine".
     */
    override val runsLocally: Boolean = false

    private val ready = AtomicBoolean(false)
    private val utteranceCounter = AtomicLong()

    @Volatile
    private var engine: TextToSpeech? = null

    override val isReady: Boolean get() = ready.get()

    /**
     * Initialise lazily and only once.
     *
     * Constructing a TextToSpeech binds to another process, so doing it eagerly
     * in the DI graph would cost every cold start whether or not John speaks.
     */
    private fun ensureEngine(): TextToSpeech? {
        engine?.let { return it }

        synchronized(this) {
            engine?.let { return it }

            val created = runCatching {
                TextToSpeech(context) { status ->
                    val ok = status == TextToSpeech.SUCCESS
                    ready.set(ok)
                    if (!ok) logger.warn(TAG, "Speech engine failed to initialise: status=$status")
                }
            }.getOrElse {
                logger.error(TAG, "Could not create the speech engine", it)
                return null
            }

            created.setAudioAttributes(audioRouter.speechAttributes())
            engine = created
            return created
        }
    }

    override suspend fun speak(text: String, settings: SpeechSettings) {
        if (text.isBlank()) return

        val engine = ensureEngine() ?: return
        if (!ready.get()) {
            // Initialisation is asynchronous; a request arriving first is normal
            // on the very first utterance rather than an error.
            logger.debug(TAG, "Speech requested before the engine was ready")
            return
        }

        applySettings(engine, settings)

        val utteranceId = "john-${utteranceCounter.incrementAndGet()}"

        suspendCancellableCoroutine { continuation ->
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit

                    override fun onDone(id: String?) {
                        if (id == utteranceId && continuation.isActive) continuation.resume(Unit)
                    }

                    @Deprecated("Superseded by onError(String, Int)", ReplaceWith(""))
                    override fun onError(id: String?) {
                        if (id == utteranceId && continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        logger.warn(TAG, "Utterance failed: code=$errorCode")
                        if (id == utteranceId && continuation.isActive) continuation.resume(Unit)
                    }
                },
            )

            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
            if (result != TextToSpeech.SUCCESS && continuation.isActive) {
                logger.warn(TAG, "speak() rejected the utterance")
                continuation.resume(Unit)
            }

            // A cancelled turn must stop the voice, not leave it talking to itself.
            continuation.invokeOnCancellation { runCatching { engine.stop() } }
        }
    }

    override fun stop() {
        runCatching { engine?.stop() }
    }

    override suspend fun availableVoices(): List<VoiceOption> {
        val engine = ensureEngine() ?: return emptyList()

        return runCatching {
            engine.voices.orEmpty()
                .filterNot { it.isTooSlow() }
                .map { voice ->
                    VoiceOption(
                        id = voice.name,
                        displayName = voice.displayName(),
                        languageTag = voice.locale.toLanguageTag(),
                        isLocal = !voice.isNetworkConnectionRequired,
                    )
                }
                .sortedBy { it.displayName }
        }.getOrDefault(emptyList())
    }

    override fun shutdown() {
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        engine = null
        ready.set(false)
    }

    private fun applySettings(engine: TextToSpeech, settings: SpeechSettings) {
        engine.setSpeechRate(settings.speechRate.coerceIn(MIN_RATE, MAX_RATE))
        engine.setPitch(settings.pitch.coerceIn(MIN_PITCH, MAX_PITCH))

        val locale = runCatching { Locale.forLanguageTag(settings.languageTag) }
            .getOrDefault(Locale.getDefault())

        val availability = engine.setLanguage(locale)
        if (availability == TextToSpeech.LANG_MISSING_DATA ||
            availability == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            logger.warn(TAG, "No voice data for ${settings.languageTag}; using the default")
            engine.setLanguage(Locale.getDefault())
        }

        settings.voiceId?.let { id ->
            engine.voices.orEmpty().firstOrNull { it.name == id }?.let(engine::setVoice)
        }
    }

    /** Voices flagged as very low quality sound wrong for an assistant. */
    private fun Voice.isTooSlow(): Boolean = quality <= Voice.QUALITY_VERY_LOW

    private fun Voice.displayName(): String {
        val language = locale.getDisplayName(Locale.getDefault())
        val network = if (isNetworkConnectionRequired) " (online)" else ""
        return "$language$network"
    }

    private companion object {
        const val TAG = "AndroidTts"
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
    }
}
