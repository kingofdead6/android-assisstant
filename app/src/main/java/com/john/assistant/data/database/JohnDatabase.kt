package com.john.assistant.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * John's local store.
 *
 * Two tables and nothing else. Notifications, contacts, transcripts of screen
 * content and anything else John reads in passing are deliberately *not* here:
 * the only things that survive a turn are what the user said, what John
 * answered, and what the user explicitly asked John to remember.
 *
 * `exportSchema` is off because there is no migration history to test against
 * yet; turn it on with a schema directory before shipping a version 2.
 */
@Database(
    entities = [ConversationTurnEntity::class, MemoryEntryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class JohnDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao

    abstract fun memoryDao(): MemoryDao

    companion object {
        const val NAME = "john.db"
    }
}
