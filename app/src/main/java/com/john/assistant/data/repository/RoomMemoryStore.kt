package com.john.assistant.data.repository

import com.john.assistant.core.memory.MemoryEntry
import com.john.assistant.core.memory.MemoryStore
import com.john.assistant.data.database.MemoryDao
import com.john.assistant.data.database.MemoryEntryEntity
import com.john.assistant.data.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Long-term memory on Room.
 *
 * The privacy contract is enforced here rather than trusted to callers: when
 * the user switches memory off, [remember] silently does nothing and
 * [promptLines] returns empty, so no code path can write a memory behind the
 * setting's back. Reads still work, so the user can review and delete what is
 * already stored after turning it off — switching memory off should not make
 * the existing memories invisible and undeletable.
 */
@Singleton
class RoomMemoryStore @Inject constructor(
    private val memoryDao: MemoryDao,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) : MemoryStore {

    /**
     * Mirrored into a StateFlow because [MemoryStore.isEnabled] is a plain
     * property on the core interface — the orchestrator asks it synchronously,
     * mid-turn, and cannot suspend to read DataStore.
     */
    private val enabled = settingsRepository.settings
        .map { it.memoryEnabled }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = true)

    override val isEnabled: Boolean get() = enabled.value

    override fun observeAll(): Flow<List<MemoryEntry>> =
        memoryDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun all(): List<MemoryEntry> = memoryDao.all().map { it.toDomain() }

    override suspend fun get(key: String): MemoryEntry? = memoryDao.get(key)?.toDomain()

    override suspend fun remember(entry: MemoryEntry): MemoryEntry {
        if (!isEnabled) return entry
        memoryDao.upsert(MemoryEntryEntity.fromDomain(entry))
        return entry
    }

    override suspend fun forget(key: String) {
        memoryDao.delete(key)
    }

    override suspend fun clear() {
        memoryDao.clear()
    }
}
