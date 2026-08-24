package com.john.assistant.session

import com.john.assistant.core.assistant.AssistantEvent
import com.john.assistant.core.assistant.AssistantOrchestrator
import com.john.assistant.core.assistant.AssistantState
import com.john.assistant.core.conversation.ConversationContextManager
import com.john.assistant.core.speech.ListeningEvent
import com.john.assistant.core.speech.SpeechToTextEngine
import com.john.assistant.core.speech.TextToSpeechEngine
import com.john.assistant.core.speech.TranscriptionResult
import com.john.assistant.core.speech.WakeWordEngine
import com.john.assistant.core.tool.PermissionKey
import com.john.assistant.core.util.AssistantLogger
import com.john.assistant.data.preferences.SettingsRepository
import com.john.assistant.data.repository.ConversationRepository
import com.john.assistant.platform.AudioRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the home screen renders. */
data class AssistantUiState(
    val state: AssistantState = AssistantState.IDLE,
    /** What John heard, updating live while the user speaks. */
    val transcript: String = "",
    val reply: String = "",
    /** "Opening YouTube…" — shown under the orb while a tool runs. */
    val actionLabel: String? = null,
    val pendingQuestion: String? = null,
    val choices: List<String> = emptyList(),
    val error: String? = null,
    /** Microphone level in dB, for the orb animation. */
    val micLevel: Float = 0f,
    val wakeWordActive: Boolean = false,
)

/** Things the UI must act on, delivered once. */
sealed interface AssistantSideEffect {
    data class RequestPermission(val permission: PermissionKey, val message: String) : AssistantSideEffect
    data class ShowError(val message: String) : AssistantSideEffect
}

/**
 * The running assistant.
 *
 * Owns the loop the user actually experiences: wake word fires, microphone
 * opens, transcript arrives, [AssistantOrchestrator] decides and acts, John
 * speaks. Everything Android-specific about *sequencing* lives here so the
 * orchestrator can stay a pure decision engine.
 *
 * Three things this class is careful about, all of them learned from voice
 * assistants that get them wrong:
 *
 *  - **John never listens to itself.** Speaking suspends until the utterance
 *    finishes, and only then does the microphone reopen. Otherwise the wake
 *    word triggers on John's own voice and the loop never ends.
 *  - **One turn at a time.** A new request cancels the previous one rather than
 *    interleaving with it, so "no, stop" always wins.
 *  - **A pending question keeps the microphone open.** After "Send it?" John
 *    listens again automatically, because requiring a second wake word to say
 *    "yes" is the fastest way to make a confirmation prompt feel like a
 *    punishment.
 */
@Singleton
class AssistantSession @Inject constructor(
    private val orchestrator: AssistantOrchestrator,
    private val speechToText: SpeechToTextEngine,
    private val textToSpeech: TextToSpeechEngine,
    private val wakeWordEngine: WakeWordEngine,
    private val conversationContext: ConversationContextManager,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val audioRouter: AudioRouter,
    private val logger: AssistantLogger,
    private val scope: CoroutineScope,
) {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val sideEffects = Channel<AssistantSideEffect>(Channel.BUFFERED)
    val effects: Flow<AssistantSideEffect> = sideEffects.receiveAsFlow()

    private var turnJob: Job? = null
    private var listenJob: Job? = null

    init {
        observeWakeWord()
    }

    // ------------------------------------------------------------- listening

    /** Open the microphone and act on whatever the user says. */
    fun startListening() {
        if (listenJob?.isActive == true) return

        listenJob = scope.launch {
            // Only meaningful with a headset; harmless otherwise.
            audioRouter.preferHeadsetMic()

            update {
                it.copy(
                    state = AssistantState.LISTENING,
                    transcript = "",
                    error = null,
                    actionLabel = null,
                )
            }

            try {
                speechToText.listen(settingsRepository.current().languageTag).collect { event ->
                    when (event) {
                        ListeningEvent.Started ->
                            update { it.copy(state = AssistantState.LISTENING) }

                        is ListeningEvent.Level ->
                            update { it.copy(micLevel = event.rms) }

                        is ListeningEvent.PartialTranscript ->
                            update { it.copy(transcript = event.text) }

                        is ListeningEvent.Finished -> onTranscription(event.result)
                    }
                }
            } finally {
                audioRouter.releaseHeadsetMic()
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        speechToText.cancel()
        audioRouter.releaseHeadsetMic()
        update { it.copy(state = AssistantState.IDLE, micLevel = 0f) }
    }

    /** Handle typed input — the accessible path, and the one used in tests. */
    fun submitText(text: String) {
        runTurn(text)
    }

    /** Stop everything: speech, listening, and the turn in flight. */
    fun cancelEverything() {
        turnJob?.cancel()
        stopListening()
        textToSpeech.stop()
        orchestrator.cancelPending()
        update {
            it.copy(
                state = AssistantState.IDLE,
                pendingQuestion = null,
                choices = emptyList(),
                actionLabel = null,
            )
        }
    }

    private suspend fun onTranscription(result: TranscriptionResult) {
        when (result) {
            is TranscriptionResult.Success -> {
                update { it.copy(transcript = result.text) }
                runTurn(result.text)
            }

            TranscriptionResult.NoSpeech ->
                // Silence is not an error. Say nothing and go quiet.
                update { it.copy(state = AssistantState.IDLE, micLevel = 0f) }

            is TranscriptionResult.Failure -> {
                logger.warn(TAG, "Transcription failed: ${result.message}")
                update {
                    it.copy(
                        state = AssistantState.ERROR,
                        error = result.message,
                        micLevel = 0f,
                    )
                }
                sideEffects.trySend(AssistantSideEffect.ShowError(result.message))
            }
        }
    }

    // ------------------------------------------------------------------ turn

    private fun runTurn(utterance: String) {
        turnJob?.cancel()

        turnJob = scope.launch {
            orchestrator.handle(utterance).collect { event -> onEvent(event) }

            // Persist after the turn rather than per event: the orchestrator is
            // the one that decides what a turn's final wording was.
            conversationContext.history.lastOrNull()
                ?.let { conversationRepository.record(it) }
            conversationRepository.pruneExpired()

            // A question left open means John is mid-conversation; reopening the
            // microphone is what makes "yes" work without another wake word.
            if (_uiState.value.pendingQuestion != null) {
                startListening()
            }
        }
    }

    private suspend fun onEvent(event: AssistantEvent) {
        when (event) {
            is AssistantEvent.Heard ->
                update {
                    it.copy(
                        transcript = event.text,
                        state = AssistantState.THINKING,
                        pendingQuestion = null,
                        choices = emptyList(),
                        error = null,
                    )
                }

            AssistantEvent.Thinking ->
                update { it.copy(state = AssistantState.THINKING) }

            is AssistantEvent.Executing ->
                update {
                    it.copy(
                        state = AssistantState.EXECUTING,
                        actionLabel = event.description.replaceFirstChar { char -> char.uppercase() },
                    )
                }

            is AssistantEvent.Reply -> {
                update {
                    it.copy(
                        state = AssistantState.SPEAKING,
                        reply = event.text,
                        actionLabel = null,
                    )
                }
                if (event.spoken) speak(event.text)
                update { it.copy(state = AssistantState.IDLE) }
            }

            is AssistantEvent.AwaitingConfirmation -> {
                update {
                    it.copy(
                        state = AssistantState.AWAITING_INPUT,
                        reply = event.question,
                        pendingQuestion = event.question,
                        choices = emptyList(),
                    )
                }
                speak(event.question)
            }

            is AssistantEvent.AwaitingChoice -> {
                update {
                    it.copy(
                        state = AssistantState.AWAITING_INPUT,
                        reply = event.question,
                        pendingQuestion = event.question,
                        choices = event.options,
                    )
                }
                speak(event.question)
            }

            is AssistantEvent.PermissionNeeded -> {
                update {
                    it.copy(state = AssistantState.AWAITING_INPUT, reply = event.message)
                }
                speak(event.message)
                sideEffects.trySend(
                    AssistantSideEffect.RequestPermission(event.permission, event.message),
                )
                update { it.copy(state = AssistantState.IDLE) }
            }

            AssistantEvent.Done ->
                update { it.copy(micLevel = 0f) }
        }
    }

    /** Speak, and wait. The waiting is the point — see the class comment. */
    private suspend fun speak(text: String) {
        val settings = settingsRepository.current()
        if (!settings.speakResponses) return
        textToSpeech.speak(text, settings.toSpeechSettings())
    }

    // ------------------------------------------------------------- wake word

    private fun observeWakeWord() {
        scope.launch {
            wakeWordEngine.detections().collect { detection ->
                logger.info(TAG, "Wake word detected (${detection.confidence})")
                // The engine releases the microphone when it fires, so the
                // session can take it without contending for the hardware.
                startListening()
            }
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        if (enabled) {
            if (!wakeWordEngine.isAvailable) {
                sideEffects.trySend(
                    AssistantSideEffect.ShowError(
                        "Wake-word detection needs a speech recogniser, and this phone doesn't have one.",
                    ),
                )
                return
            }
            wakeWordEngine.start()
        } else {
            wakeWordEngine.stop()
        }
        update { it.copy(wakeWordActive = wakeWordEngine.isRunning()) }
    }

    fun isWakeWordRunning(): Boolean = wakeWordEngine.isRunning()

    private inline fun update(transform: (AssistantUiState) -> AssistantUiState) {
        _uiState.value = transform(_uiState.value)
    }

    private companion object {
        const val TAG = "Session"
    }
}
