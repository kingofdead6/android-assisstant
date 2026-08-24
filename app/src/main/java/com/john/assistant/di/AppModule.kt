package com.john.assistant.di

import android.content.Context
import androidx.room.Room
import com.john.assistant.core.util.AssistantLogger
import com.john.assistant.core.util.TimeSource
import com.john.assistant.data.database.ConversationDao
import com.john.assistant.data.database.JohnDatabase
import com.john.assistant.data.database.MemoryDao
import com.john.assistant.util.AndroidLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Storage, logging and the application-lifetime coroutine scope. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * The scope John's long-lived work runs in.
     *
     * `SupervisorJob` so one failing collector — a wake-word engine that throws
     * — does not tear down the assistant session alongside it.
     */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideTimeSource(): TimeSource = TimeSource.SYSTEM

    @Provides
    @Singleton
    fun provideLogger(logger: AndroidLogger): AssistantLogger = logger

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): JohnDatabase =
        Room.databaseBuilder(context, JohnDatabase::class.java, JohnDatabase.NAME)
            // No destructive migration: history and memory are the user's data,
            // and silently dropping them on a schema change is not acceptable.
            // Version 2 must ship a real migration.
            .build()

    @Provides
    fun provideConversationDao(database: JohnDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideMemoryDao(database: JohnDatabase): MemoryDao = database.memoryDao()
}
