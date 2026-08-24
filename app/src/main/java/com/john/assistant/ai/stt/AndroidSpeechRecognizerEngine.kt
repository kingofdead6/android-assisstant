package com.john.assistant.ai.stt

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.john.assistant.core.speech.AudioBuffer
import com.john.assistant.core.speech.ListeningEvent
import com.john.assistant.core.speech.SpeechToTextEngine
import com.john.assistant.core.speech.TranscriptionResult
import com.john.assistant.core.util.AssistantLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speech recognition through the platform recogniser.
 *
 * Chosen as the default because it is the only recogniser guaranteed to exist
 * on every device, it streams partial results (which is what makes the orb feel
 * alive while the user is still talking), and on most modern devices it runs
 * on-device.
 *
 * It is **not** unconditionally local, and this class does not pretend
 * otherwise. `EXTRA_PREFER_OFFLINE` asks the recogniser to stay on the device,
 * and Android 13 added `isOnDeviceRecognitionAvailable`, but whether audio
 * leaves the phone ultimately depends on which recogniser the user has
 * installed. [runsLocally] reports what can actually be determined, and the
 * privacy screen shows it — the honest answer to "does my voice leave the
 * phone?" is what a local-first assistant owes its user.
 *
 * A fully offline path (Whisper on-device) plugs in behind
 * [SpeechToTextEngine]; see docs/local-ai.md.
 */
@Singleton
class AndroidSpeechRecognizerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AssistantLogger,
) : SpeechToTextEngine {

    override val displayName: String = "Android speech recognition"

    override val runsLocally: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                .getOrDefault(false)

    override val isAvailable: Boolean
        get() = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    @Volatile
    private var recognizer: SpeechRecognizer? = null

    /**
     * Capture and transcribe one utterance.
     *
     * `SpeechRecognizer` must be created and driven on the main thread — it is
     * a bound-service client with a main-looper callback contract — so the flow
     * is pinned to [Dispatchers.Main]. Getting this wrong produces a recogniser
     * that silently never calls back.
     */
    override fun listen(languageTag: String): Flow<ListeningEvent> = callbackFlow {
        if (!isAvailable) {
            trySend(
                ListeningEvent.Finished(
                    TranscriptionResult.Failure("Speech recognition isn't available on this phone."),
                ),
            )
            close()
            return@callbackFlow
        }

        val client = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = client

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(ListeningEvent.Started)
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                trySend(ListeningEvent.Level(rmsdB))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstTranscript()
                    ?.let { trySend(ListeningEvent.PartialTranscript(it)) }
            }

            override fun onResults(results: Bundle?) {
                val transcript = results.firstTranscript()
                val confidence = results
                    ?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    ?.firstOrNull()
                    ?: 1f

                trySend(
                    ListeningEvent.Finished(
                        if (transcript.isNullOrBlank()) {
                            TranscriptionResult.NoSpeech
                        } else {
                            TranscriptionResult.Success(transcript, confidence)
                        },
                    ),
                )
                close()
            }

            override fun onError(error: Int) {
                trySend(ListeningEvent.Finished(errorToResult(error)))
                close()
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        client.setRecognitionListener(listener)
        client.startListening(recognitionIntent(languageTag))

        awaitClose {
            runCatching {
                client.stopListening()
                client.destroy()
            }
            recognizer = null
        }
    }.flowOn(Dispatchers.Main)

    /**
     * Not supported by the platform recogniser.
     *
     * `SpeechRecognizer` owns the microphone itself and has no API for
     * transcribing a buffer. Returning a clear failure is better than
     * pretending: the caller can fall back to [listen], and an on-device
     * Whisper engine implements this properly when one is installed.
     */
    override suspend fun transcribe(audio: AudioBuffer, languageTag: String): TranscriptionResult =
        TranscriptionResult.Failure(
            "The Android recogniser can only transcribe live audio. " +
                "Install an on-device model to transcribe recordings.",
        )

    override fun cancel() {
        runCatching { recognizer?.cancel() }
    }

    private fun recognitionIntent(languageTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

            // Ask to stay on the device. Honoured from Android 6; recognisers
            // that cannot go offline fall back to network and say so via
            // ERROR_LANGUAGE_UNAVAILABLE rather than silently uploading.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

            // How long a pause is allowed before the recogniser decides the
            // utterance is over.
            //
            // Without these the platform default applies, and several OEM
            // recognisers set it near half a second — short enough that a
            // normal pause mid-sentence ends the turn, which reads to the user
            // as the microphone cutting them off. A command like "remind me to
            // call Sam ... tomorrow at nine" is one utterance, not two.
            //
            // These are documented as hints: a recogniser is free to ignore
            // them, so this improves the common case rather than guaranteeing
            // it everywhere.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_BEFORE_END_MILLIS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_BEFORE_END_MILLIS,
            )
            // Do not end the turn before the user has plausibly started.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                MINIMUM_UTTERANCE_MILLIS,
            )
        }

    private fun Bundle?.firstTranscript(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * Recogniser errors, translated into something worth saying out loud.
     *
     * NO_MATCH and SPEECH_TIMEOUT are not failures — they mean the user did not
     * say anything — and are reported as [TranscriptionResult.NoSpeech] so John
     * quietly stops rather than announcing an error at an empty room.
     */
    private fun errorToResult(error: Int): TranscriptionResult = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> TranscriptionResult.NoSpeech

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            TranscriptionResult.Failure("I need microphone permission to hear you.")

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> TranscriptionResult.Failure(
            "Speech recognition needs a connection on this phone, and you're offline.",
        )

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            TranscriptionResult.Failure("Something else is using the microphone.")

        else -> {
            logger.warn(TAG, "Recogniser error $error")
            TranscriptionResult.Failure("I couldn't hear that clearly.")
        }
    }

    private companion object {
        const val TAG = "AndroidStt"

        /**
         * Silence that ends an utterance.
         *
         * Two seconds is long enough to survive the pause in "set an alarm
         * for... seven", and short enough that John does not sit there after
         * the user has plainly finished.
         */
        const val SILENCE_BEFORE_END_MILLIS = 2_000L

        /** Never end a turn in under this, however quiet the room is. */
        const val MINIMUM_UTTERANCE_MILLIS = 1_500L
    }
}
