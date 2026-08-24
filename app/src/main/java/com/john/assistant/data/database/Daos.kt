package com.john.assistant.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversation_turns ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ConversationTurnEntity>>

    @Query("SELECT * FROM conversation_turns ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ConversationTurnEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(turn: ConversationTurnEntity): Long

    @Query("DELETE FROM conversation_turns WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM conversation_turns")
    suspend fun clear()

    /** Used by the auto-delete setting; retention is enforced, not just offered. */
    @Query("DELETE FROM conversation_turns WHERE timestampMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    @Query("SELECT COUNT(*) FROM conversation_turns")
    suspend fun count(): Int
}

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memory_entries ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<MemoryEntryEntity>>

    @Query("SELECT * FROM memory_entries ORDER BY updatedAtMillis DESC")
    suspend fun all(): List<MemoryEntryEntity>

    @Query("SELECT * FROM memory_entries WHERE key = :key LIMIT 1")
    suspend fun get(key: String): MemoryEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MemoryEntryEntity)

    @Query("DELETE FROM memory_entries WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM memory_entries")
    suspend fun clear()
}
