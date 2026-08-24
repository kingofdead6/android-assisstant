package com.john.assistant.platform

import com.john.assistant.core.assistant.DeviceEnvironment
import com.john.assistant.permissions.PermissionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The situational facts the orchestrator folds into each prompt.
 *
 * Deliberately small. Every line added here is paid for on every single turn,
 * in tokens and latency, on a model with a few thousand of them to spend — so
 * only facts that change John's *decision* belong here. The battery level does
 * not: it is a tool result, requested when asked about.
 *
 * What does belong: the time (so "remind me at 7" resolves to the right seven),
 * whether the phone is online (so John does not pick a tool that will fail),
 * and what the user is listening through (so "play music" behaves sensibly with
 * earbuds in).
 */
@Singleton
class AndroidDeviceEnvironment @Inject constructor(
    private val deviceState: DeviceStateProvider,
    private val audioRouter: AudioRouter,
    private val permissionManager: PermissionManager,
) : DeviceEnvironment {

    override suspend fun isOnline(): Boolean = deviceState.connectivity().isOnline

    override suspend fun isAccessibilityEnabled(): Boolean =
        permissionManager.isAccessibilityServiceEnabled()

    override suspend fun facts(): List<String> = buildList {
        addAll(deviceState.contextFacts())

        val route = audioRouter.state()
        if (route.isBluetoothConnected) {
            add("Audio is playing through ${route.deviceName ?: "Bluetooth headphones"}.")
        }

        if (!deviceState.connectivity().isOnline) {
            add("The phone has no internet connection.")
        }
    }
}
