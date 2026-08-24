package com.john.assistant.data.repository

import com.john.assistant.core.conversation.ConversationTurn
import com.john.assistant.data.database.ConversationDao
import com.john.assistant.data.database.ConversationTurnEntity
import com.john.assistant.data.preferences.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conversation history.
 *
 * Two user-facing promises live here:
 *
 *  - **History off means nothing is written.** [record] returns early rather
 *    than writing a row that the history screen then hides.
 *  - **Retention is enforced, not advertised.** [pruneExpired] runs on every
 *    app start and after every turn, so a thirty-day setting really does delete
 *    at thirty days.
 */
@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val settingsRepository: SettingsRepository,
) {

    fun observeHistory(limit: Int = DEFAULT_LIMIT): Flow<List<ConversationTurn>> =
        conversationDao.observeRecent(limit).map { entities -> entities.map { it.toDomain() } }

    suspend fun record(turn: ConversationTurn) {
        if (!settingsRepository.current().historyEnabled) return
        conversationDao.insert(ConversationTurnEntity.fromDomain(turn))
    }

    suspend fun recent(limit: Int = DEFAULT_LIMIT): List<ConversationTurn> =
        conversationDao.recent(limit).map { it.toDomain() }

    suspend fun delete(id: Long) = conversationDao.delete(id)

    suspend fun clear() = conversationDao.clear()

    suspend fun count(): Int = conversationDao.count()

    /** Delete anything past the retention window. Zero days means keep forever. */
    suspend fun pruneExpired(nowMillis: Long = System.currentTimeMillis()) {
        val days = settingsRepository.current().historyRetentionDays
        if (days <= 0) return
        conversationDao.deleteOlderThan(nowMillis - days * DAY_MILLIS)
    }

    private companion object {
        const val DEFAULT_LIMIT = 200
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
