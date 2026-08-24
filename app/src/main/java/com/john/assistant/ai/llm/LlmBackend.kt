package com.john.assistant.ai.llm

import java.io.File

/**
 * A native inference runtime.
 *
 * This is the seam between John and whatever actually runs the weights. It is
 * deliberately tiny — load a file, produce text, free the memory — because
 * everything interesting (which tools exist, how the prompt is built, what the
 * output means) belongs above it, and because a small surface is what makes
 * llama.cpp, MediaPipe LLM Inference, ONNX Runtime and LiteRT interchangeable.
 *
 * Implementations are expected to be slow and blocking. Callers move them off
 * the main thread; nothing here does that for you.
 */
interface LlmBackend {

    /** Shown in settings, e.g. "llama.cpp". */
    val name: String

    /**
     * Whether this backend can run at all on this device.
     *
     * False when the native library is not bundled in the APK — which is the
     * default state of this repository, and is checked rather than assumed.
     */
    val isSupported: Boolean

    /** File extensions this backend accepts, e.g. `gguf`. */
    val supportedExtensions: Set<String>

    val isLoaded: Boolean

    /** @return true when the weights are resident and [generate] will work. */
    suspend fun load(modelFile: File, contextTokens: Int): Boolean

    /**
     * Run inference.
     *
     * @return the raw completion, or null if inference failed. Callers must not
     *   treat null as an empty answer — it means the model did not run.
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopSequences: List<String>,
    ): String?

    suspend fun unload()
}

/**
 * The backend used when no native runtime is present.
 *
 * This repository ships no inference `.so`: bundling one would add tens of
 * megabytes of prebuilt native code that nobody reading this could audit, and
 * the choice of runtime belongs to whoever builds the app. So the honest
 * default is a backend that reports [isSupported] as false, which makes
 * [LocalLlmEngine] report itself as not ready, which makes John fall back to
 * the deterministic command matcher — a fully working assistant, without a
 * model, saying so in settings.
 *
 * docs/local-ai.md describes exactly what to add to replace this.
 */
object UnavailableLlmBackend : LlmBackend {
    override val name: String = "None installed"
    override val isSupported: Boolean = false
    override val supportedExtensions: Set<String> = emptySet()
    override val isLoaded: Boolean = false

    override suspend fun load(modelFile: File, contextTokens: Int): Boolean = false

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopSequences: List<String>,
    ): String? = null

    override suspend fun unload() = Unit
}
