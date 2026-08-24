package com.john.assistant.data.preferences

/**
 * Which engine answers a turn.
 *
 * Before this existed the choice was inferred — "use the remote engine if it
 * happens to look ready" — which meant saving a Hugging Face model ID silently
 * took over routing, and clearing it silently handed it back. Neither was
 * something the user asked for. The selection is now explicit and stored, so
 * the UI shows what will actually run.
 */
enum class LlmBackendChoice {
    /** Whatever is usable, preferring the on-device model. The default. */
    AUTOMATIC,

    /** Force the on-device LiteRT-LM model. */
    LOCAL,

    /** Force the Hugging Face Inference API. */
    HUGGING_FACE,
    ;

    companion object {
        val DEFAULT = AUTOMATIC

        fun fromStoredName(raw: String?): LlmBackendChoice =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
