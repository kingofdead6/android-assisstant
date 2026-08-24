package com.john.assistant.core.memory

import kotlinx.coroutines.flow.Flow

/** What kind of thing John remembered, so the user can review it by group. */
enum class MemoryCategory {
    /** "My music app is Spotify." Steers tool choice. */
    PREFERENCE,

    /** "My office is on Rue Ben Boulaid." Plain recall. */
    FACT,

    /** A user-defined phrase mapped to a tool call. */
    CUSTOM_COMMAND,
}

/** How a memory got there — shown in the memory screen so nothing is a surprise. */
enum class MemorySource {
    /** The user said "remember that…". */
    EXPLICIT,

    /** John inferred it from repeated behaviour. Off unless the user opts in. */
    INFERRED,

    /** A setting the user changed in the UI. */
    SETTING,
}

/**
 * One remembered item.
 *
 * [key] is normalised (lowercase, underscores) so lookups are stable;
 * [value] is stored verbatim as the user said it.
 */
data class MemoryEntry(
    val id: Long = 0,
    val key: String,
    val value: String,
    val category: MemoryCategory = MemoryCategory.FACT,
    val source: MemorySource = MemorySource.EXPLICIT,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
)

/**
 * Long-term memory.
 *
 * Implemented over Room in the app module. The contract lives here so the
 * orchestrator can consult memory without knowing about Android.
 *
 * Two properties the implementation must honour, because the privacy screen
 * promises them:
 *  - nothing is written unless [isEnabled] is true;
 *  - [clear] really deletes, rather than hiding.
 */
interface MemoryStore {

    val isEnabled: Boolean

    fun observeAll(): Flow<List<MemoryEntry>>

    suspend fun all(): List<MemoryEntry>

    suspend fun get(key: String): MemoryEntry?

    suspend fun remember(entry: MemoryEntry): MemoryEntry

    suspend fun forget(key: String)

    suspend fun clear()

    /** Compact lines injected into the prompt. Bounded — the window is small. */
    suspend fun promptLines(limit: Int = 12): List<String> =
        if (!isEnabled) emptyList()
        else all()
            .sortedByDescending { it.updatedAtMillis }
            .take(limit)
            .map { "${it.key.replace('_', ' ')}: ${it.value}" }

    companion object {
        /** Used when the user has memory switched off entirely. */
        val DISABLED: MemoryStore = NoOpMemoryStore
    }
}

private object NoOpMemoryStore : MemoryStore {
    override val isEnabled: Boolean = false
    override fun observeAll(): Flow<List<MemoryEntry>> = kotlinx.coroutines.flow.flowOf(emptyList())
    override suspend fun all(): List<MemoryEntry> = emptyList()
    override suspend fun get(key: String): MemoryEntry? = null
    override suspend fun remember(entry: MemoryEntry): MemoryEntry = entry
    override suspend fun forget(key: String) = Unit
    override suspend fun clear() = Unit
}
