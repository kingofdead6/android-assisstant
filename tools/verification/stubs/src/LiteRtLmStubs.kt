package com.google.ai.edge.litertlm

/**
 * Stub of com.google.ai.edge.litertlm:litertlm-android for the offline
 * verifier. See DaggerStubs.kt for why these exist.
 *
 * These signatures mirror LiteRT-LM 0.16.1's Kotlin API closely enough to
 * type-check `LiteRtLmBackend` — the real artifact lives on Google's Maven,
 * which this script cannot reach. Keep them in step with the version pinned in
 * gradle/libs.versions.toml: a drifting stub would let a call that no longer
 * compiles against the real AAR pass verification.
 */
sealed class Backend {
    class CPU : Backend()

    class GPU : Backend()
}

class EngineConfig(
    val modelPath: String,
    val backend: Backend? = null,
    val cacheDir: String? = null,
)

class SamplerConfig(
    val topK: Int,
    val topP: Double,
    val temperature: Double,
    val seed: Int = 0,
)

class ConversationConfig(
    val samplerConfig: SamplerConfig? = null,
    val maxOutputToken: Int? = null,
)

sealed class Content {
    data class Text(val text: String) : Content()
}

class Contents(val contents: List<Content>)

class Message(val contents: Contents)

class Conversation : AutoCloseable {
    fun sendMessage(text: String): Message = throw UnsupportedOperationException("verification stub")

    override fun close() = throw UnsupportedOperationException("verification stub")
}

class Engine(config: EngineConfig) : AutoCloseable {
    fun initialize(): Unit = throw UnsupportedOperationException("verification stub")

    fun createConversation(config: ConversationConfig): Conversation =
        throw UnsupportedOperationException("verification stub")

    override fun close() = throw UnsupportedOperationException("verification stub")
}
