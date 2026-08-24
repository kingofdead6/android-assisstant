package com.john.assistant.ai.model

import com.john.assistant.core.llm.ChatTemplate

/** What kind of model this is. */
enum class ModelKind { LANGUAGE, SPEECH_TO_TEXT, TEXT_TO_SPEECH, WAKE_WORD }

/**
 * One model John can use.
 *
 * Sizes and RAM figures are shown to the user *before* anything downloads. That
 * is the whole point of this record: a 4B model is a two-gigabyte download and
 * will not run on a 4 GB phone, and the user should learn that from a screen
 * rather than from a failed load after a long wait on mobile data.
 */
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val kind: ModelKind,
    /** Download size in megabytes. */
    val sizeMb: Int,
    /** Rough peak RAM while loaded, in megabytes. */
    val requiredRamMb: Int,
    val fileName: String,
    /** Where the weights come from. Empty for models John cannot fetch itself. */
    val downloadUrl: String,
    val template: ChatTemplate = ChatTemplate.PLAIN,
    val contextTokens: Int = 2048,
    val licence: String,
    val notes: String = "",
) {
    /** Whether this device plausibly has the memory. Advisory, not a gate. */
    fun fitsIn(deviceRamMb: Long): Boolean = deviceRamMb >= requiredRamMb * RAM_HEADROOM

    private companion object {
        /** The OS and other apps need room too; loading to the limit thrashes. */
        const val RAM_HEADROOM = 2
    }
}

/**
 * The models John suggests.
 *
 * Nothing here is bundled and nothing downloads on its own — this is a menu,
 * and the user picks from it. The entries are chosen for actually running on a
 * phone: 1–4B parameters, quantised, with permissive licences.
 *
 * URLs are left empty deliberately. Model repositories move, and a stale
 * hardcoded link that 404s mid-download is worse than a screen that asks the
 * user to paste the URL they want. The model manager accepts a user-supplied
 * URL or a file picked from storage.
 */
object ModelCatalogue {

    val LANGUAGE_MODELS = listOf(
        ModelDescriptor(
            id = "qwen2.5-1.5b-instruct-q4",
            displayName = "Qwen 2.5 1.5B Instruct (Q4)",
            kind = ModelKind.LANGUAGE,
            sizeMb = 1_100,
            requiredRamMb = 1_600,
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            downloadUrl = "",
            template = ChatTemplate.CHAT_ML,
            contextTokens = 4096,
            licence = "Apache 2.0",
            notes = "The best size/quality trade-off for tool selection on a mid-range phone.",
        ),
        ModelDescriptor(
            id = "qwen2.5-3b-instruct-q4",
            displayName = "Qwen 2.5 3B Instruct (Q4)",
            kind = ModelKind.LANGUAGE,
            sizeMb = 2_000,
            requiredRamMb = 2_800,
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            downloadUrl = "",
            template = ChatTemplate.CHAT_ML,
            contextTokens = 4096,
            licence = "Apache 2.0",
            notes = "Noticeably better at unusual phrasing. Needs 6 GB of RAM to be comfortable.",
        ),
        ModelDescriptor(
            id = "gemma-2-2b-it-q4",
            displayName = "Gemma 2 2B Instruct (Q4)",
            kind = ModelKind.LANGUAGE,
            sizeMb = 1_600,
            requiredRamMb = 2_200,
            fileName = "gemma-2-2b-it-q4_k_m.gguf",
            downloadUrl = "",
            template = ChatTemplate.GEMMA,
            contextTokens = 4096,
            licence = "Gemma Terms of Use",
            notes = "Check Google's Gemma terms before shipping this in a product.",
        ),
        ModelDescriptor(
            id = "llama-3.2-1b-instruct-q4",
            displayName = "Llama 3.2 1B Instruct (Q4)",
            kind = ModelKind.LANGUAGE,
            sizeMb = 800,
            requiredRamMb = 1_200,
            fileName = "llama-3.2-1b-instruct-q4_k_m.gguf",
            downloadUrl = "",
            template = ChatTemplate.LLAMA3,
            contextTokens = 4096,
            licence = "Llama 3.2 Community Licence",
            notes = "The lightest option. Fast, but drops arguments on longer commands.",
        ),
        ModelDescriptor(
            id = "phi-3.5-mini-instruct-q4",
            displayName = "Phi 3.5 Mini Instruct (Q4)",
            kind = ModelKind.LANGUAGE,
            sizeMb = 2_300,
            requiredRamMb = 3_000,
            fileName = "phi-3.5-mini-instruct-q4.gguf",
            downloadUrl = "",
            template = ChatTemplate.PHI,
            contextTokens = 4096,
            licence = "MIT",
            notes = "Strong at structured output; the heaviest of these on a phone.",
        ),
    )

    val SPEECH_MODELS = listOf(
        ModelDescriptor(
            id = "whisper-tiny-en",
            displayName = "Whisper Tiny (English)",
            kind = ModelKind.SPEECH_TO_TEXT,
            sizeMb = 75,
            requiredRamMb = 200,
            fileName = "ggml-tiny.en.bin",
            downloadUrl = "",
            licence = "MIT",
            notes = "Needs a whisper.cpp build; see docs/local-ai.md.",
        ),
        ModelDescriptor(
            id = "whisper-base-en",
            displayName = "Whisper Base (English)",
            kind = ModelKind.SPEECH_TO_TEXT,
            sizeMb = 142,
            requiredRamMb = 350,
            fileName = "ggml-base.en.bin",
            downloadUrl = "",
            licence = "MIT",
            notes = "Clearly better than Tiny on accented speech.",
        ),
    )

    fun all(): List<ModelDescriptor> = LANGUAGE_MODELS + SPEECH_MODELS

    fun byId(id: String): ModelDescriptor? = all().firstOrNull { it.id == id }

    fun forKind(kind: ModelKind): List<ModelDescriptor> = all().filter { it.kind == kind }
}
