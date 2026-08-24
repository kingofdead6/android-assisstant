package com.john.assistant.ai.llm

import com.john.assistant.core.util.AssistantLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * llama.cpp, when its native library is present.
 *
 * The JNI surface below matches the one exposed by the `llama-android` bindings
 * in the llama.cpp repository. The library itself is **not** in this repo. If
 * `libllama-android.so` is not in the APK, [isSupported] is false and John
 * simply never uses this backend — no crash, no missing-symbol error at some
 * later moment, and settings shows that no model runtime is installed.
 *
 * To enable it, build llama.cpp for Android and drop the resulting `.so` into
 * `app/src/main/jniLibs/<abi>/`. See docs/local-ai.md.
 *
 * Inference is serialised by [inferenceLock]: llama.cpp contexts are not
 * thread-safe, and two concurrent generations on one context corrupt its KV
 * cache rather than failing cleanly.
 */
@Singleton
class LlamaCppBackend @Inject constructor(
    private val logger: AssistantLogger,
) : LlmBackend {

    override val name: String = "llama.cpp"

    override val supportedExtensions: Set<String> = setOf("gguf")

    override val isSupported: Boolean by lazy {
        runCatching { System.loadLibrary(LIBRARY_NAME) }
            .onFailure { logger.info(TAG, "No llama.cpp native library in this build") }
            .isSuccess
    }

    @Volatile
    private var contextHandle: Long = NULL_HANDLE

    private val inferenceLock = Mutex()

    override val isLoaded: Boolean get() = contextHandle != NULL_HANDLE

    override suspend fun load(modelFile: File, contextTokens: Int): Boolean {
        if (!isSupported) return false
        if (!modelFile.isFile) {
            logger.warn(TAG, "Model file does not exist: ${modelFile.name}")
            return false
        }

        return withContext(Dispatchers.Default) {
            inferenceLock.withLock {
                if (isLoaded) unloadLocked()

                val handle = runCatching { nativeLoadModel(modelFile.absolutePath, contextTokens) }
                    .getOrElse { error ->
                        logger.error(TAG, "Loading ${modelFile.name} failed", error)
                        NULL_HANDLE
                    }

                contextHandle = handle
                if (handle == NULL_HANDLE) {
                    // Overwhelmingly the out-of-memory case: a 4B model on a
                    // phone with 4 GB of RAM. Worth naming in the log.
                    logger.warn(TAG, "llama.cpp refused to load ${modelFile.name}; likely not enough memory")
                }
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
                val handle = contextHandle
                if (handle == NULL_HANDLE) return@withLock null

                runCatching {
                    nativeGenerate(
                        handle,
                        prompt,
                        maxTokens,
                        temperature,
                        topP,
                        stopSequences.toTypedArray(),
                    )
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
        val handle = contextHandle
        if (handle == NULL_HANDLE) return
        contextHandle = NULL_HANDLE
        runCatching { nativeFreeModel(handle) }
    }

    // --- JNI ------------------------------------------------------------
    // Declared but never called unless isSupported is true, which is only the
    // case once System.loadLibrary has succeeded.

    private external fun nativeLoadModel(path: String, contextTokens: Int): Long

    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopSequences: Array<String>,
    ): String?

    private external fun nativeFreeModel(handle: Long)

    private companion object {
        const val TAG = "LlamaCpp"
        const val LIBRARY_NAME = "llama-android"
        const val NULL_HANDLE = 0L
    }
}
