package com.john.assistant.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.john.assistant.core.assistant.AssistantConfig
import com.john.assistant.core.llm.LlmOptions
import com.john.assistant.core.policy.ConfirmationPolicy
import com.john.assistant.core.prompt.SystemPrompts
import com.john.assistant.core.speech.AudioRoute
import com.john.assistant.core.speech.SpeechSettings
import com.john.assistant.core.tool.RiskLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "john_settings")

/** Everything the settings screens can change, in one snapshot. */
data class JohnSettings(
    // AI
    val systemPrompt: String = SystemPrompts.DEFAULT,
    val temperature: Float = 0.2f,
    val maxResponseTokens: Int = 256,
    val activeModelId: String? = null,
    val phraseResultsWithLlm: Boolean = false,

    // Voice
    val wakeWordEnabled: Boolean = false,
    val wakePhrase: String = "Hey John",
    val wakeWordSensitivity: Float = 0.5f,
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val voiceId: String? = null,
    val languageTag: String = "en-US",
    val audioRoute: AudioRoute = AudioRoute.AUTOMATIC,
    val speakResponses: Boolean = true,

    // Privacy
    val memoryEnabled: Boolean = true,
    val historyEnabled: Boolean = true,
    val historyRetentionDays: Int = 30,

    // Automation
    val confirmFrom: RiskLevel = RiskLevel.MEDIUM,
    val alwaysConfirmTools: Set<String> = emptySet(),
    val neverConfirmTools: Set<String> = emptySet(),
    val disabledTools: Set<String> = emptySet(),
    val backgroundOperationEnabled: Boolean = false,
) {
    fun toAssistantConfig(): AssistantConfig = AssistantConfig(
        systemPrompt = systemPrompt,
        llmOptions = LlmOptions(temperature = temperature, maxTokens = maxResponseTokens),
        confirmationPolicy = ConfirmationPolicy(
            confirmFrom = confirmFrom,
            alwaysConfirm = alwaysConfirmTools,
            neverConfirm = neverConfirmTools,
        ),
        phraseResultsWithLlm = phraseResultsWithLlm,
        useMemory = memoryEnabled,
    )

    fun toSpeechSettings(): SpeechSettings = SpeechSettings(
        speechRate = speechRate,
        pitch = speechPitch,
        voiceId = voiceId,
        languageTag = languageTag,
        route = audioRoute,
    )
}

/**
 * Settings, backed by DataStore.
 *
 * Read as a flow so a change takes effect on the next thing the user says
 * rather than on the next app launch — turning confirmations off should not
 * require restarting the assistant.
 *
 * IOException is swallowed into defaults rather than thrown: a corrupt
 * preferences file must not stop John from working, and a broken setting is a
 * far smaller problem than an assistant that will not start.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val settings: Flow<JohnSettings> = context.dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::read)

    suspend fun current(): JohnSettings = settings.first()

    suspend fun update(transform: (JohnSettings) -> JohnSettings) {
        val updated = transform(current())
        context.dataStore.edit { preferences -> write(preferences, updated) }
    }

    /** Convenience for the many single-switch settings rows. */
    suspend fun setWakeWordEnabled(enabled: Boolean) = update { it.copy(wakeWordEnabled = enabled) }

    suspend fun setMemoryEnabled(enabled: Boolean) = update { it.copy(memoryEnabled = enabled) }

    suspend fun setHistoryEnabled(enabled: Boolean) = update { it.copy(historyEnabled = enabled) }

    suspend fun setToolEnabled(toolName: String, enabled: Boolean) = update { settings ->
        settings.copy(
            disabledTools = if (enabled) {
                settings.disabledTools - toolName
            } else {
                settings.disabledTools + toolName
            },
        )
    }

    suspend fun setActiveModel(modelId: String?) = update { it.copy(activeModelId = modelId) }

    /** Restore every setting to its default. Does not touch history or memory. */
    suspend fun resetToDefaults() {
        context.dataStore.edit { it.clear() }
    }

    private fun read(preferences: Preferences): JohnSettings {
        val defaults = JohnSettings()
        return JohnSettings(
            systemPrompt = preferences[Keys.SYSTEM_PROMPT] ?: defaults.systemPrompt,
            temperature = preferences[Keys.TEMPERATURE] ?: defaults.temperature,
            maxResponseTokens = preferences[Keys.MAX_TOKENS] ?: defaults.maxResponseTokens,
            activeModelId = preferences[Keys.ACTIVE_MODEL],
            phraseResultsWithLlm = preferences[Keys.PHRASE_WITH_LLM] ?: defaults.phraseResultsWithLlm,

            wakeWordEnabled = preferences[Keys.WAKE_WORD_ENABLED] ?: defaults.wakeWordEnabled,
            wakePhrase = preferences[Keys.WAKE_PHRASE] ?: defaults.wakePhrase,
            wakeWordSensitivity = preferences[Keys.WAKE_SENSITIVITY] ?: defaults.wakeWordSensitivity,
            speechRate = preferences[Keys.SPEECH_RATE] ?: defaults.speechRate,
            speechPitch = preferences[Keys.SPEECH_PITCH] ?: defaults.speechPitch,
            voiceId = preferences[Keys.VOICE_ID],
            languageTag = preferences[Keys.LANGUAGE_TAG] ?: defaults.languageTag,
            audioRoute = preferences[Keys.AUDIO_ROUTE]
                ?.let { raw -> runCatching { AudioRoute.valueOf(raw) }.getOrNull() }
                ?: defaults.audioRoute,
            speakResponses = preferences[Keys.SPEAK_RESPONSES] ?: defaults.speakResponses,

            memoryEnabled = preferences[Keys.MEMORY_ENABLED] ?: defaults.memoryEnabled,
            historyEnabled = preferences[Keys.HISTORY_ENABLED] ?: defaults.historyEnabled,
            historyRetentionDays = preferences[Keys.HISTORY_RETENTION_DAYS]
                ?: defaults.historyRetentionDays,

            confirmFrom = preferences[Keys.CONFIRM_FROM]
                ?.let { raw -> runCatching { RiskLevel.valueOf(raw) }.getOrNull() }
                ?: defaults.confirmFrom,
            alwaysConfirmTools = preferences[Keys.ALWAYS_CONFIRM].orEmpty(),
            neverConfirmTools = preferences[Keys.NEVER_CONFIRM].orEmpty(),
            disabledTools = preferences[Keys.DISABLED_TOOLS].orEmpty(),
            backgroundOperationEnabled = preferences[Keys.BACKGROUND_ENABLED]
                ?: defaults.backgroundOperationEnabled,
        )
    }

    private fun write(preferences: androidx.datastore.preferences.core.MutablePreferences, settings: JohnSettings) {
        preferences[Keys.SYSTEM_PROMPT] = settings.systemPrompt
        preferences[Keys.TEMPERATURE] = settings.temperature
        preferences[Keys.MAX_TOKENS] = settings.maxResponseTokens
        settings.activeModelId?.let { preferences[Keys.ACTIVE_MODEL] = it }
            ?: preferences.remove(Keys.ACTIVE_MODEL)
        preferences[Keys.PHRASE_WITH_LLM] = settings.phraseResultsWithLlm

        preferences[Keys.WAKE_WORD_ENABLED] = settings.wakeWordEnabled
        preferences[Keys.WAKE_PHRASE] = settings.wakePhrase
        preferences[Keys.WAKE_SENSITIVITY] = settings.wakeWordSensitivity
        preferences[Keys.SPEECH_RATE] = settings.speechRate
        preferences[Keys.SPEECH_PITCH] = settings.speechPitch
        settings.voiceId?.let { preferences[Keys.VOICE_ID] = it } ?: preferences.remove(Keys.VOICE_ID)
        preferences[Keys.LANGUAGE_TAG] = settings.languageTag
        preferences[Keys.AUDIO_ROUTE] = settings.audioRoute.name
        preferences[Keys.SPEAK_RESPONSES] = settings.speakResponses

        preferences[Keys.MEMORY_ENABLED] = settings.memoryEnabled
        preferences[Keys.HISTORY_ENABLED] = settings.historyEnabled
        preferences[Keys.HISTORY_RETENTION_DAYS] = settings.historyRetentionDays

        preferences[Keys.CONFIRM_FROM] = settings.confirmFrom.name
        preferences[Keys.ALWAYS_CONFIRM] = settings.alwaysConfirmTools
        preferences[Keys.NEVER_CONFIRM] = settings.neverConfirmTools
        preferences[Keys.DISABLED_TOOLS] = settings.disabledTools
        preferences[Keys.BACKGROUND_ENABLED] = settings.backgroundOperationEnabled
    }

    private fun emptyPreferences() = androidx.datastore.preferences.core.emptyPreferences()

    private object Keys {
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val ACTIVE_MODEL = stringPreferencesKey("active_model")
        val PHRASE_WITH_LLM = booleanPreferencesKey("phrase_with_llm")

        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val WAKE_PHRASE = stringPreferencesKey("wake_phrase")
        val WAKE_SENSITIVITY = floatPreferencesKey("wake_sensitivity")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val SPEECH_PITCH = floatPreferencesKey("speech_pitch")
        val VOICE_ID = stringPreferencesKey("voice_id")
        val LANGUAGE_TAG = stringPreferencesKey("language_tag")
        val AUDIO_ROUTE = stringPreferencesKey("audio_route")
        val SPEAK_RESPONSES = booleanPreferencesKey("speak_responses")

        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        val HISTORY_RETENTION_DAYS = intPreferencesKey("history_retention_days")

        val CONFIRM_FROM = stringPreferencesKey("confirm_from")
        val ALWAYS_CONFIRM = stringSetPreferencesKey("always_confirm")
        val NEVER_CONFIRM = stringSetPreferencesKey("never_confirm")
        val DISABLED_TOOLS = stringSetPreferencesKey("disabled_tools")
        val BACKGROUND_ENABLED = booleanPreferencesKey("background_enabled")
    }
}
