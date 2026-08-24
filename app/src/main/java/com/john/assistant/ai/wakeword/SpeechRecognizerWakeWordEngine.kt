package com.john.assistant.ai.wakeword

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.john.assistant.core.speech.WakeWordDetection
import com.john.assistant.core.speech.WakeWordEngine
import com.john.assistant.core.util.AssistantLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * "Hey John", detected on the device with what every Android phone already has.
 *
 * ### Why this exists in this form
 *
 * A purpose-built keyword spotter (Porcupine, openWakeWord, a small TFLite
 * model) is the right long-term answer: a few hundred KB of weights, a fraction
 * of a percent of battery, and no continuous recognition. John's architecture
 * is built for that — [WakeWordEngine] is the seam, and swapping the
 * implementation changes nothing above it.
 *
 * What ships here is the version that works *without* asking the user to
 * download anything first: the platform recogniser, restarted in a loop, with
 * `EXTRA_PREFER_OFFLINE` set and the transcript matched against the phrase
 * locally. It is honest about its two costs, which are real:
 *
 *  - **Battery.** Continuous recognition is far heavier than a keyword spotter.
 *    This is why the wake word is off by default and why the foreground service
 *    notification is visible rather than hidden.
 *  - **Locality.** Whether audio stays on the phone depends on the installed
 *    recogniser. [runsFullyOnDevice] reports what can actually be determined,
 *    and the settings screen shows it rather than promising.
 *
 * Nothing is recorded to disk, and no transcript except a matched wake phrase
 * leaves this class.
 */
@Singleton
class SpeechRecognizerWakeWordEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AssistantLogger,
) : WakeWordEngine {

    override val displayName: String = "Built-in recogniser (no model needed)"

    override val phrase: String = DEFAULT_PHRASE

    override val isAvailable: Boolean
        get() = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    /** Surfaced in settings so the battery/privacy trade-off is visible. */
    val runsFullyOnDevice: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                .getOrDefault(false)

    override var sensitivity: Float = DEFAULT_SENSITIVITY
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    private val running = AtomicBoolean(false)
    private val detections = MutableSharedFlow<WakeWordDetection>(extraBufferCapacity = 4)

    // Main dispatcher: SpeechRecognizer must be created and driven from the
    // main looper, and this engine outlives any one coroutine caller.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var recognizer: SpeechRecognizer? = null

    /** Consecutive failures, used to back off instead of spinning. */
    private var failureStreak = 0

    override fun isRunning(): Boolean = running.get()

    override fun start() {
        if (!isAvailable) {
            logger.warn(TAG, "No speech recogniser on this device; wake word cannot start")
            return
        }
        if (!running.compareAndSet(false, true)) return

        failureStreak = 0
        scope.launch { listenOnce() }
    }

    override fun stop() {
        running.set(false)
        scope.launch {
            runCatching {
                recognizer?.cancel()
                recognizer?.destroy()
            }
            recognizer = null
        }
    }

    override fun detections(): Flow<WakeWordDetection> = detections.asSharedFlow()

    /**
     * One recognition pass, which re-arms itself.
     *
     * The recogniser stops after every utterance and after every error, so
     * "always listening" is really "restart immediately, forever". Failures
     * back off exponentially — a device that has revoked the microphone would
     * otherwise burn battery restarting hundreds of times a second.
     */
    private fun listenOnce() {
        if (!running.get()) return

        runCatching { recognizer?.destroy() }

        val client = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = client

        client.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                // Matching on partials is what makes the wake word feel instant:
                // "hey john, open youtube" fires as soon as the phrase lands,
                // rather than after the whole sentence.
                override fun onPartialResults(partialResults: Bundle?) {
                    if (matches(partialResults)) fire()
                }

                override fun onResults(results: Bundle?) {
                    if (matches(results)) fire() else restart(delayMillis = 0)
                }

                override fun onError(error: Int) {
                    onRecognitionError(error)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )

        runCatching { client.startListening(wakeWordIntent()) }
            .onFailure {
                logger.warn(TAG, "Could not start wake-word recognition", it)
                onRecognitionError(SpeechRecognizer.ERROR_CLIENT)
            }
    }

    private fun onRecognitionError(error: Int) {
        when (error) {
            // Nobody spoke. Expected constantly; not a failure.
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> {
                failureStreak = 0
                restart(delayMillis = 0)
            }

            // Permission gone, or another app took the microphone. Stop rather
            // than fight for it — a phone call must win.
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                logger.warn(TAG, "Microphone permission revoked; stopping wake word")
                stop()
            }

            else -> {
                failureStreak++
                val backoff = min(
                    BASE_BACKOFF_MILLIS shl min(failureStreak, MAX_BACKOFF_SHIFT),
                    MAX_BACKOFF_MILLIS,
                )
                logger.debug(TAG, "Recogniser error $error; retrying in ${backoff}ms")
                restart(backoff)
            }
        }
    }

    private fun restart(delayMillis: Long) {
        if (!running.get()) return
        scope.launch {
            if (delayMillis > 0) kotlinx.coroutines.delay(delayMillis)
            listenOnce()
        }
    }

    private fun fire() {
        failureStreak = 0
        detections.tryEmit(
            WakeWordDetection(
                phrase = phrase,
                confidence = 1f,
                timestampMillis = System.currentTimeMillis(),
            ),
        )
        // The session takes the microphone next; restarting immediately would
        // fight it for the same hardware.
        stop()
    }

    private fun matches(results: Bundle?): Boolean {
        val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return false

        return candidates.any { candidate ->
            val normalised = candidate.lowercase().replace(Regex("[^a-z ]"), " ")
            PHRASE_VARIANTS.any { normalised.contains(it) }
        }
    }

    private fun wakeWordIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_HYPOTHESES)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private companion object {
        const val TAG = "WakeWord"
        const val DEFAULT_PHRASE = "Hey John"
        const val DEFAULT_SENSITIVITY = 0.5f

        /**
         * Recognisers routinely hear "hey john" as "hey jon", and drop the
         * "hey" entirely on partial results. Accepting the near-misses is the
         * difference between a wake word that works and one that needs shouting.
         */
        val PHRASE_VARIANTS = listOf("hey john", "hey jon", "hi john", "ok john", "hey joan")

        // Several hypotheses raise the chance the phrase appears in one of them.
        const val MAX_HYPOTHESES = 3

        const val BASE_BACKOFF_MILLIS = 500L
        const val MAX_BACKOFF_MILLIS = 30_000L
        const val MAX_BACKOFF_SHIFT = 6
    }
}
