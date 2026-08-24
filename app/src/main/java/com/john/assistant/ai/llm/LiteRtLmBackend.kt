package com.john.assistant.ai.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.john.assistant.core.util.AssistantLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT-LM — Google's on-device inference runtime.
 *
 * Chosen over llama.cpp because it ships its native libraries inside the AAR:
 * there is no NDK toolchain, no CMake and no hand-written JNI shim to keep in
 * step with an upstream C++ API. Google has put MediaPipe LLM Inference into
 * maintenance mode and points Android projects here.
 *
 * Everything runs on the device. The only network this backend's model ever
 * touches is the one-off download of the `.litertlm` file, which is
 * [com.john.assistant.ai.model.ModelManager]'s job, not this class's.
 *
 * Inference is serialised by [inferenceLock]: an engine is not safe to drive
 * from two coroutines at once, and concurrent generations corrupt decoder state
 * rather than failing cleanly.
 */
@Singleton
class LiteRtLmBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AssistantLogger,
) : LlmBackend {

    override val name: String = "LiteRT-LM"

    override val supportedExtensions: Set<String> = setOf("litertlm")

    /**
     * Whether the LiteRT-LM classes are actually in this APK.
     *
     * The dependency can be stripped by a build variant or shrunk away, and a
     * capability that is absent must be discovered here — while the answer is
     * still a boolean — rather than as a NoClassDefFoundError on the first
     * utterance, long after John has told the user it is ready.
     */
    override val isSupported: Boolean by lazy {
        runCatching {
            Class.forName(ENGINE_CLASS, false, javaClass.classLoader)
            System.loadLibrary(NATIVE_LIBRARY)
        }.onFailure { error ->
            logger.warn(
                TAG,
                "LiteRT-LM runtime unavailable: ${error.message ?: error::class.java.name}",
                error,
            )
        }.isSuccess
    }

    @Volatile
    private var engine: Engine? = null

    private val inferenceLock = Mutex()

    override val isLoaded: Boolean get() = engine != null

    override suspend fun load(modelFile: File, contextTokens: Int): Boolean {
        if (!isSupported) return false
        if (!modelFile.isFile) {
            logger.warn(TAG, "Model file does not exist: ${modelFile.name}")
            return false
        }

        return withContext(Dispatchers.Default) {
            inferenceLock.withLock {
                if (engine != null) unloadLocked()

                // initialize() reads a multi-gigabyte bundle and can take ten
                // seconds; it is never called from the main thread.
                val opened = try {
                    Engine(
                        EngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = Backend.CPU(),
                            cacheDir = context.cacheDir.path,
                        ),
                    ).also { it.initialize() }
                } catch (error: UnsatisfiedLinkError) {
                    logger.error(
                        TAG,
                        "LiteRT-LM native library failed for ${modelFile.absolutePath}: " +
                            (error.message ?: error.toString()),
                        error,
                    )
                    null
                } catch (error: Throwable) {
                    // Overwhelmingly the out-of-memory case: a model whose
                    // working set does not fit this phone. A backend never
                    // throws — the engine stays null, LocalLlmEngine reports
                    // itself not ready, and the deterministic matcher answers.
                    logger.warn(
                        TAG,
                        "LiteRT-LM could not load ${modelFile.absolutePath}; " +
                            (error.message ?: error::class.java.name),
                        error,
                    )
                    null
                }

                engine = opened
                isLoaded
            }
        }
    }

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopSequences: List<String>,
    ): String? {
        if (!isLoaded) return null

        return withContext(Dispatchers.Default) {
            inferenceLock.withLock {
                val current = engine ?: return@withLock null

                runCatching {
                    // A fresh conversation per call, deliberately. The
                    // orchestrator passes the whole history it wants the model
                    // to see on every turn, so keeping LiteRT-LM's KV cache
                    // alive across turns would replay that history twice.
                    //
                    // No systemInstruction either: the system prompt is already
                    // inside `prompt`, and setting it here would state it twice.
                    current.createConversation(
                        ConversationConfig(
                            samplerConfig = SamplerConfig(
                                // topK has no default in this API and 0 would
                                // disable sampling entirely; John's options
                                // only expose topP, so this is a neutral width
                                // that leaves nucleus sampling in charge.
                                topK = DEFAULT_TOP_K,
                                topP = topP.toDouble(),
                                temperature = temperature.toDouble(),
                            ),
                            maxOutputToken = maxTokens,
                        ),
                    ).use { conversation ->
                        truncateAtStop(conversation.sendMessage(prompt).textContent(), stopSequences)
                    }
                }.getOrElse { error ->
                    logger.error(TAG, "Inference failed", error)
                    null
                }
            }
        }
    }

    override suspend fun unload() {
        withContext(Dispatchers.Default) {
            inferenceLock.withLock { unloadLocked() }
        }
    }

    private fun unloadLocked() {
        val active = engine ?: return
        engine = null
        runCatching { active.close() }
    }

    /**
     * The text parts of a reply, concatenated.
     *
     * A [Message] carries a list of [Content] — a multimodal reply can hold
     * images or tool responses alongside the words. John only ever asks for
     * text, but filtering by type rather than assuming a single text part is
     * what keeps a future audio-capable model from crashing this cast.
     */
    private fun Message.textContent(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /**
     * Cut the completion at the first stop sequence.
     *
     * The Kotlin API has no stop-sequence option, so the template's stop tokens
     * have to be honoured here. [com.john.assistant.core.llm.ToolCallParser] is
     * strict, and one trailing control token on otherwise valid JSON is the
     * difference between an executed command and a spoken shrug.
     */
    private fun truncateAtStop(raw: String, stopSequences: List<String>): String {
        val cut = stopSequences
            .mapNotNull { stop -> raw.indexOf(stop).takeIf { it >= 0 } }
            .minOrNull()
            ?: return raw.trim()

        return raw.substring(0, cut).trim()
    }

    private companion object {
        const val TAG = "LiteRtLm"
        const val ENGINE_CLASS = "com.google.ai.edge.litertlm.Engine"
        const val NATIVE_LIBRARY = "litertlm_jni"
        const val DEFAULT_TOP_K = 40
    }
}
