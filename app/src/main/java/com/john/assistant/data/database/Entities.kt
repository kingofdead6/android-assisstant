package com.john.assistant.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.john.assistant.core.conversation.ConversationTurn
import com.john.assistant.core.conversation.TurnOutcome
import com.john.assistant.core.memory.MemoryCategory
import com.john.assistant.core.memory.MemoryEntry
import com.john.assistant.core.memory.MemorySource

/**
 * One exchange, as stored.
 *
 * History is the record of what John did, so it holds the tool name and outcome
 * as well as the words. That is what lets the history screen show *why* a turn
 * failed rather than only what was said.
 */
@Entity(tableName = "conversation_turns", indices = [Index("timestampMillis")])
data class ConversationTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val userText: String,
    val assistantText: String,
    val toolName: String?,
    val outcome: String,
) {
    fun toDomain(): ConversationTurn = ConversationTurn(
        id = id,
        timestampMillis = timestampMillis,
        userText = userText,
        assistantText = assistantText,
        toolName = toolName,
        outcome = runCatching { TurnOutcome.valueOf(outcome) }.getOrDefault(TurnOutcome.SPOKEN),
    )

    companion object {
        fun fromDomain(turn: ConversationTurn) = ConversationTurnEntity(
            id = turn.id,
            timestampMillis = turn.timestampMillis,
            userText = turn.userText,
            assistantText = turn.assistantText,
            toolName = turn.toolName,
            outcome = turn.outcome.name,
        )
    }
}

/**
 * One remembered item.
 *
 * [key] is the primary key rather than a generated id: remembering "my music
 * app is Spotify" twice should update the answer, not accumulate two of them.
 */
@Entity(tableName = "memory_entries")
data class MemoryEntryEntity(
    @PrimaryKey val key: String,
    val value: String,
    val category: String,
    val source: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun toDomain(): MemoryEntry = MemoryEntry(
        key = key,
        value = value,
        category = runCatching { MemoryCategory.valueOf(category) }
            .getOrDefault(MemoryCategory.FACT),
        source = runCatching { MemorySource.valueOf(source) }
            .getOrDefault(MemorySource.EXPLICIT),
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

    companion object {
        fun fromDomain(entry: MemoryEntry) = MemoryEntryEntity(
            key = entry.key,
            value = entry.value,
            category = entry.category.name,
            source = entry.source.name,
            createdAtMillis = entry.createdAtMillis,
            updatedAtMillis = entry.updatedAtMillis,
        )
    }
}
