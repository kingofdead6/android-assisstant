package com.john.assistant.di

import com.john.assistant.ai.llm.LlamaCppBackend
import com.john.assistant.ai.llm.LlmBackend
import com.john.assistant.ai.llm.LocalLlmEngine
import com.john.assistant.ai.stt.AndroidSpeechRecognizerEngine
import com.john.assistant.ai.tts.AndroidTextToSpeechEngine
import com.john.assistant.ai.wakeword.SpeechRecognizerWakeWordEngine
import com.john.assistant.core.llm.CompositeLlmEngine
import com.john.assistant.core.llm.LlmEngine
import com.john.assistant.core.llm.rules.RuleBasedLlmEngine
import com.john.assistant.core.memory.MemoryStore
import com.john.assistant.core.speech.SpeechToTextEngine
import com.john.assistant.core.speech.TextToSpeechEngine
import com.john.assistant.core.speech.WakeWordEngine
import com.john.assistant.core.util.AssistantLogger
import com.john.assistant.core.util.TimeSource
import com.john.assistant.data.repository.RoomMemoryStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Which AI engines John actually runs.
 *
 * Every binding here is an interface from `:core`, so replacing an engine —
 * Whisper for the platform recogniser, Piper for the platform voice, Porcupine
 * for the wake word — is a one-line change in this file and nothing else.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    /**
     * The deterministic matcher, always present.
     *
     * Not a fallback bolted on for emergencies — it is the first thing every
     * utterance meets, and it answers most of them without inference.
     */
    @Provides
    @Singleton
    fun provideRuleBasedEngine(timeSource: TimeSource): RuleBasedLlmEngine =
        RuleBasedLlmEngine(timeSource)

    /**
     * The native runtime.
     *
     * [LlamaCppBackend] reports itself unsupported when its `.so` is absent,
     * which is this repository's default. Swap this binding to plug in
     * MediaPipe LLM Inference, ONNX Runtime or LiteRT.
     */
    @Provides
    @Singleton
    fun provideLlmBackend(backend: LlamaCppBackend): LlmBackend = backend

    /**
     * Deterministic first, model second.
     *
     * Ordering is the whole design: "pause" never waits on a transformer, and a
     * model that is missing, loading or broken cannot stop John working.
     */
    @Provides
    @Singleton
    fun provideLlmEngine(
        ruleBased: RuleBasedLlmEngine,
        local: LocalLlmEngine,
        logger: AssistantLogger,
    ): LlmEngine = CompositeLlmEngine(fast = ruleBased, fallback = local, logger = logger)

    @Provides
    @Singleton
    fun provideSpeechToText(engine: AndroidSpeechRecognizerEngine): SpeechToTextEngine = engine

    @Provides
    @Singleton
    fun provideTextToSpeech(engine: AndroidTextToSpeechEngine): TextToSpeechEngine = engine

    @Provides
    @Singleton
    fun provideWakeWord(engine: SpeechRecognizerWakeWordEngine): WakeWordEngine = engine

    @Provides
    @Singleton
    fun provideMemoryStore(store: RoomMemoryStore): MemoryStore = store
}
