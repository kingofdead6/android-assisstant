package com.john.assistant.core.fake

import com.john.assistant.core.llm.ChatMessage
import com.john.assistant.core.llm.LlmEngine
import com.john.assistant.core.llm.LlmOptions
import com.john.assistant.core.llm.LlmResponse
import com.john.assistant.core.speech.AudioBuffer
import com.john.assistant.core.speech.ListeningEvent
import com.john.assistant.core.speech.SpeechSettings
import com.john.assistant.core.speech.SpeechToTextEngine
import com.john.assistant.core.speech.TextToSpeechEngine
import com.john.assistant.core.speech.TranscriptionResult
import com.john.assistant.core.speech.WakeWordDetection
import com.john.assistant.core.speech.WakeWordEngine
import com.john.assistant.core.tool.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Scripted [LlmEngine].
 *
 * Queue responses with [enqueue]; each [generate] pops the next one. When the
 * queue empties it repeats the last response, so a test that only cares about
 * one decision does not have to enqueue one per turn.
 */
class FakeLlmEngine(
    override val displayName: String = "Fake LLM",
    override var isReady: Boolean = true,
) : LlmEngine {

    private val queued = ArrayDeque<LlmResponse>()
    private var last: LlmResponse = LlmResponse.Text("ok")

    /** Every prompt this engine was asked to answer, in order. */
    val prompts = mutableListOf<List<ChatMessage>>()

    /** Tool definitions offered on the most recent call. */
    var lastOfferedTools: List<ToolDefinition> = emptyList()
        private set

    var warmUpCount: Int = 0
        private set

    fun enqueue(vararg responses: LlmResponse) = apply { queued.addAll(responses) }

    fun enqueueToolCall(tool: String, arguments: Map<String, Any?> = emptyMap()) =
        enqueue(LlmResponse.ToolCall(tool, arguments))

    fun enqueueText(text: String) = enqueue(LlmResponse.Text(text))

    override suspend fun warmUp() {
        warmUpCount++
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        options: LlmOptions,
    ): LlmResponse {
        prompts += messages
        lastOfferedTools = tools
        return queued.removeFirstOrNull()?.also { last = it } ?: last
    }
}

/** [SpeechToTextEngine] that returns whatever the test told it to hear. */
class FakeSpeechToTextEngine(
    var nextResult: TranscriptionResult = TranscriptionResult.Success("hello"),
    override val displayName: String = "Fake STT",
    override val runsLocally: Boolean = true,
    override val isAvailable: Boolean = true,
) : SpeechToTextEngine {

    var cancelCount: Int = 0
        private set

    override fun listen(languageTag: String): Flow<ListeningEvent> = flowOf(
        ListeningEvent.Started,
        ListeningEvent.Finished(nextResult),
    )

    override suspend fun transcribe(audio: AudioBuffer, languageTag: String): TranscriptionResult =
        nextResult

    override fun cancel() {
        cancelCount++
    }
}

/** [TextToSpeechEngine] that records what John would have said. */
class FakeTextToSpeechEngine(
    override val displayName: String = "Fake TTS",
    override val runsLocally: Boolean = true,
    override val isReady: Boolean = true,
) : TextToSpeechEngine {

    val spoken = mutableListOf<String>()
    var stopCount: Int = 0
        private set

    override suspend fun speak(text: String, settings: SpeechSettings) {
        spoken += text
    }

    override fun stop() {
        stopCount++
    }

    override fun shutdown() = Unit
}

/** [WakeWordEngine] a test can fire by hand. */
class FakeWakeWordEngine(
    override val displayName: String = "Fake wake word",
    override val phrase: String = "Hey John",
    override val isAvailable: Boolean = true,
) : WakeWordEngine {

    private val events = MutableSharedFlow<WakeWordDetection>(extraBufferCapacity = 8)
    private var running = false

    override var sensitivity: Float = 0.5f

    override fun isRunning(): Boolean = running

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun detections(): Flow<WakeWordDetection> = events.asSharedFlow()

    /** Simulate the user saying the wake phrase. */
    suspend fun trigger(confidence: Float = 1f, timestampMillis: Long = 0) {
        events.emit(WakeWordDetection(phrase, confidence, timestampMillis))
    }
}
