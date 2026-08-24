package com.john.assistant.di

import com.john.assistant.core.assistant.AssistantConfigProvider
import com.john.assistant.core.assistant.AssistantOrchestrator
import com.john.assistant.core.assistant.DeviceEnvironment
import com.john.assistant.core.assistant.PermissionGate
import com.john.assistant.core.conversation.ConversationContextManager
import com.john.assistant.core.llm.LlmEngine
import com.john.assistant.core.memory.MemoryStore
import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.ToolRegistry
import com.john.assistant.core.util.AssistantLogger
import com.john.assistant.core.util.TimeSource
import com.john.assistant.data.preferences.SettingsRepository
import com.john.assistant.permissions.PermissionManager
import com.john.assistant.platform.AndroidDeviceEnvironment
import com.john.assistant.tools.accessibility.NavigateScreenTool
import com.john.assistant.tools.accessibility.ReadScreenTool
import com.john.assistant.tools.accessibility.TapOnScreenTool
import com.john.assistant.tools.alarms.CreateReminderTool
import com.john.assistant.tools.alarms.SetAlarmTool
import com.john.assistant.tools.alarms.SetTimerTool
import com.john.assistant.tools.app.ListAppsTool
import com.john.assistant.tools.app.OpenAppTool
import com.john.assistant.tools.browser.OpenUrlTool
import com.john.assistant.tools.browser.SearchMapsTool
import com.john.assistant.tools.browser.SearchWebTool
import com.john.assistant.tools.calendar.CreateCalendarEventTool
import com.john.assistant.tools.calendar.ReadCalendarTool
import com.john.assistant.tools.github.GitHubNotificationsTool
import com.john.assistant.tools.github.ListRepositoriesTool
import com.john.assistant.tools.media.DecreaseVolumeTool
import com.john.assistant.tools.media.IncreaseVolumeTool
import com.john.assistant.tools.media.NextTrackTool
import com.john.assistant.tools.media.NowPlayingTool
import com.john.assistant.tools.media.PauseMediaTool
import com.john.assistant.tools.media.PlayMediaTool
import com.john.assistant.tools.media.PreviousTrackTool
import com.john.assistant.tools.media.ResumeMediaTool
import com.john.assistant.tools.media.SetVolumeTool
import com.john.assistant.tools.memory.ForgetMemoryTool
import com.john.assistant.tools.memory.RecallMemoryTool
import com.john.assistant.tools.memory.RememberFactTool
import com.john.assistant.tools.messaging.SendMessageTool
import com.john.assistant.tools.notifications.ReadNotificationsTool
import com.john.assistant.tools.phone.FindContactTool
import com.john.assistant.tools.phone.MakePhoneCallTool
import com.john.assistant.tools.system.GetBatteryTool
import com.john.assistant.tools.system.GetDateTool
import com.john.assistant.tools.system.GetDeviceStateTool
import com.john.assistant.tools.system.GetTimeTool
import com.john.assistant.tools.system.OpenCameraTool
import com.john.assistant.tools.system.OpenConnectivitySettingsTool
import com.john.assistant.tools.system.ToggleFlashlightTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Singleton

/**
 * The set of things John can do.
 *
 * This list *is* the capability boundary. A tool that is not constructed here
 * does not exist as far as the model is concerned: the registry will not
 * resolve its name, so a hallucinated call to it cannot execute. Adding a
 * capability to John means adding a line here, deliberately, in review — which
 * is exactly the property you want from the code that decides what an assistant
 * is allowed to do with someone's phone.
 */
@Module
@InstallIn(SingletonComponent::class)
object ToolModule {

    @Provides
    @Singleton
    fun provideToolRegistry(
        // apps and the web
        openApp: OpenAppTool,
        listApps: ListAppsTool,
        searchWeb: SearchWebTool,
        openUrl: OpenUrlTool,
        searchMaps: SearchMapsTool,
        // media
        playMedia: PlayMediaTool,
        pauseMedia: PauseMediaTool,
        resumeMedia: ResumeMediaTool,
        nextTrack: NextTrackTool,
        previousTrack: PreviousTrackTool,
        nowPlaying: NowPlayingTool,
        increaseVolume: IncreaseVolumeTool,
        decreaseVolume: DecreaseVolumeTool,
        setVolume: SetVolumeTool,
        // system
        getBattery: GetBatteryTool,
        getTime: GetTimeTool,
        getDate: GetDateTool,
        getDeviceState: GetDeviceStateTool,
        openSettings: OpenConnectivitySettingsTool,
        toggleFlashlight: ToggleFlashlightTool,
        openCamera: OpenCameraTool,
        // people
        makeCall: MakePhoneCallTool,
        findContact: FindContactTool,
        sendMessage: SendMessageTool,
        readNotifications: ReadNotificationsTool,
        // time
        setAlarm: SetAlarmTool,
        setTimer: SetTimerTool,
        createReminder: CreateReminderTool,
        readCalendar: ReadCalendarTool,
        createEvent: CreateCalendarEventTool,
        // memory
        rememberFact: RememberFactTool,
        recallMemory: RecallMemoryTool,
        forgetMemory: ForgetMemoryTool,
        // connected accounts, optional
        githubRepositories: ListRepositoriesTool,
        githubNotifications: GitHubNotificationsTool,
        // screen automation, optional and last
        readScreen: ReadScreenTool,
        tapOnScreen: TapOnScreenTool,
        navigateScreen: NavigateScreenTool,
    ): ToolRegistry {
        val tools: List<AssistantTool> = listOf(
            openApp, listApps, searchWeb, openUrl, searchMaps,
            playMedia, pauseMedia, resumeMedia, nextTrack, previousTrack, nowPlaying,
            increaseVolume, decreaseVolume, setVolume,
            getBattery, getTime, getDate, getDeviceState, openSettings,
            toggleFlashlight, openCamera,
            makeCall, findContact, sendMessage, readNotifications,
            setAlarm, setTimer, createReminder, readCalendar, createEvent,
            rememberFact, recallMemory, forgetMemory,
            githubRepositories, githubNotifications,
            readScreen, tapOnScreen, navigateScreen,
        )

        return ToolRegistry(tools)
    }

    @Provides
    @Singleton
    fun provideConversationContext(): ConversationContextManager = ConversationContextManager()

    @Provides
    @Singleton
    fun providePermissionGate(manager: PermissionManager): PermissionGate = manager

    @Provides
    @Singleton
    fun provideDeviceEnvironment(environment: AndroidDeviceEnvironment): DeviceEnvironment =
        environment

    @Provides
    @Singleton
    fun provideConfigProvider(settingsRepository: SettingsRepository): AssistantConfigProvider =
        AssistantConfigProvider { settingsRepository.current().toAssistantConfig() }

    @Provides
    @Singleton
    fun provideOrchestrator(
        registry: ToolRegistry,
        llm: LlmEngine,
        context: ConversationContextManager,
        configProvider: AssistantConfigProvider,
        permissions: PermissionGate,
        environment: DeviceEnvironment,
        memory: MemoryStore,
        timeSource: TimeSource,
        logger: AssistantLogger,
        settingsRepository: SettingsRepository,
        scope: CoroutineScope,
    ): AssistantOrchestrator {
        // Tools the user has switched off disappear from the registry, so the
        // model stops being offered them and stops suggesting them.
        settingsRepository.settings
            .onEach { settings ->
                registry.all().forEach { tool ->
                    registry.setEnabled(tool.name, tool.name !in settings.disabledTools)
                }
            }
            .launchIn(scope)

        return AssistantOrchestrator(
            registry = registry,
            llm = llm,
            context = context,
            configProvider = configProvider,
            permissions = permissions,
            environment = environment,
            memory = memory,
            timeSource = timeSource,
            logger = logger,
        )
    }
}
