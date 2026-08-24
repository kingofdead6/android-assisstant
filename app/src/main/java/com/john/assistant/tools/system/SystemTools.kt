package com.john.assistant.tools.system

import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.ParameterType
import com.john.assistant.core.tool.PermissionKey
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolParameter
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.tool.ToolResult
import com.john.assistant.platform.AudioRouter
import com.john.assistant.platform.DeviceStateProvider
import com.john.assistant.platform.FlashlightController
import com.john.assistant.platform.IntentLauncher
import com.john.assistant.platform.WebLauncher
import javax.inject.Inject
import javax.inject.Singleton

/** "What's my battery percentage?" */
@Singleton
class GetBatteryTool @Inject constructor(
    private val deviceState: DeviceStateProvider,
) : AssistantTool {

    override val name = "get_battery"
    override val description = "Report the battery level and whether the phone is charging."
    override val examples = listOf("what's my battery", "am I charging")

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val battery = deviceState.battery()

        val message = when {
            battery.isCharging -> "You're at ${battery.percent} percent and charging."
            battery.percent <= LOW_BATTERY -> "You're at ${battery.percent} percent. Worth charging."
            else -> "You're at ${battery.percent} percent."
        }

        return ToolResult.Success(
            message = message,
            data = mapOf("percent" to battery.percent, "charging" to battery.isCharging),
        )
    }

    private companion object {
        const val LOW_BATTERY = 20
    }
}

/** "What time is it?" */
@Singleton
class GetTimeTool @Inject constructor(
    private val deviceState: DeviceStateProvider,
) : AssistantTool {
    override val name = "get_time"
    override val description = "Say the current time."
    override val examples = listOf("what time is it", "tell me the time")

    override suspend fun execute(arguments: ToolArguments): ToolResult =
        ToolResult.Success("It's ${deviceState.spokenTime()}.")
}

/** "What's the date?" */
@Singleton
class GetDateTool @Inject constructor(
    private val deviceState: DeviceStateProvider,
) : AssistantTool {
    override val name = "get_date"
    override val description = "Say today's date."
    override val examples = listOf("what's the date", "what day is it")

    override suspend fun execute(arguments: ToolArguments): ToolResult =
        ToolResult.Success("It's ${deviceState.spokenDate()}.")
}

/**
 * "Is Bluetooth on?" / "Am I online?"
 *
 * Read-only, and deliberately so — see [DeviceStateProvider]. Android does not
 * let an ordinary app switch Wi-Fi or Bluetooth on; [OpenConnectivitySettingsTool]
 * is the honest alternative.
 */
@Singleton
class GetDeviceStateTool @Inject constructor(
    private val deviceState: DeviceStateProvider,
    private val audioRouter: AudioRouter,
) : AssistantTool {

    override val name = "get_device_state"

    override val description =
        "Report connectivity: Wi-Fi, mobile data, Bluetooth, and connected headphones."

    override val parameters = ToolParameters.of(
        ToolParameter(
            name = "aspect",
            type = ParameterType.STRING,
            description = "Which detail the user asked about.",
            allowedValues = listOf("network", "bluetooth", "audio", "device", "all"),
        ),
    )

    override val requiredPermissions = setOf(PermissionKey.BLUETOOTH)

    override val examples = listOf("am I online", "is bluetooth on", "what are my buds connected to")

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val aspect = arguments.string("aspect", "all")
        val connectivity = deviceState.connectivity()
        val bluetooth = deviceState.isBluetoothOn()
        val route = audioRouter.state()

        val parts = buildList {
            if (aspect == "network" || aspect == "all") {
                add(
                    when {
                        !connectivity.isOnline -> "You're offline"
                        connectivity.isWifi -> "You're on Wi-Fi"
                        connectivity.isCellular -> "You're on mobile data"
                        else -> "You're online"
                    },
                )
            }
            if (aspect == "bluetooth" || aspect == "all") {
                add(
                    when (bluetooth) {
                        true -> "Bluetooth is on"
                        false -> "Bluetooth is off"
                        // Null means John cannot see the radio, which is not the
                        // same as it being off.
                        null -> "I can't check Bluetooth without permission"
                    },
                )
            }
            if (aspect == "audio" || aspect == "all") {
                route.deviceName?.let { add("audio is going to $it") }
            }
            if (aspect == "device") {
                add("This is a ${deviceState.deviceDescription()}")
            }
        }

        return ToolResult.Success(
            message = parts.joinToString(", ").replaceFirstChar { it.uppercase() } + ".",
            data = mapOf(
                "online" to connectivity.isOnline,
                "wifi" to connectivity.isWifi,
                "bluetooth" to (bluetooth ?: false),
            ),
        )
    }
}

/**
 * "Turn on Bluetooth."
 *
 * Opens the relevant settings panel rather than claiming to flip the switch.
 * `WifiManager.setWifiEnabled` has been inert for third-party apps since
 * Android 10 and `BluetoothAdapter.enable()` since Android 13, so a tool that
 * promised otherwise would silently do nothing.
 */
@Singleton
class OpenConnectivitySettingsTool @Inject constructor(
    private val deviceState: DeviceStateProvider,
    private val intentLauncher: IntentLauncher,
) : AssistantTool {

    override val name = "open_settings"

    override val description =
        "Open an Android settings screen, for example to turn Wi-Fi or Bluetooth on or off."

    override val parameters = ToolParameters.of(
        ToolParameter(
            name = "screen",
            type = ParameterType.STRING,
            description = "Which settings screen to open.",
            required = true,
            allowedValues = listOf("wifi", "bluetooth", "settings"),
        ),
    )

    override val examples = listOf("turn on bluetooth", "open wifi settings")

    override fun describeAction(arguments: ToolArguments): String =
        "open ${arguments.string("screen", "settings")} settings"

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val screen = arguments.string("screen", "settings")

        val intent = when (screen) {
            "wifi" -> deviceState.wifiSettingsIntent()
            "bluetooth" -> deviceState.bluetoothEnableIntent()
            else -> deviceState.settingsIntent()
        }

        val started = intentLauncher.start(intent)

        return if (started) {
            ToolResult.Success(
                message = when (screen) {
                    "bluetooth" -> "Android has to ask you before turning Bluetooth on — tap allow."
                    "wifi" -> "Here are your Wi-Fi settings."
                    else -> "Here are your settings."
                },
            )
        } else {
            ToolResult.Failure("I couldn't open that settings screen.")
        }
    }
}

/** "Turn on the flashlight." */
@Singleton
class ToggleFlashlightTool @Inject constructor(
    private val flashlight: FlashlightController,
) : AssistantTool {

    override val name = "toggle_flashlight"

    override val description = "Turn the flashlight on or off."

    override val parameters = ToolParameters.of(
        ToolParameter(
            name = "state",
            type = ParameterType.STRING,
            description = "Whether to turn it on or off. Omit to toggle.",
            allowedValues = listOf("on", "off"),
        ),
    )

    override val examples = listOf("turn on the flashlight", "torch off")

    override fun describeAction(arguments: ToolArguments): String =
        "turn the flashlight ${arguments.string("state", "on")}"

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        if (!flashlight.hasFlashlight()) {
            return ToolResult.Failure("This phone doesn't have a flashlight.", recoverable = false)
        }

        val result = when (arguments.string("state")) {
            "on" -> flashlight.setEnabled(true)
            "off" -> flashlight.setEnabled(false)
            else -> flashlight.toggle()
        } ?: return ToolResult.Failure(
            "I couldn't use the flashlight — something else may be using the camera.",
        )

        return ToolResult.Success(
            message = if (result) "Flashlight on." else "Flashlight off.",
            data = mapOf("on" to result),
            spoken = false,
        )
    }
}

/** "Open the camera." */
@Singleton
class OpenCameraTool @Inject constructor(
    private val webLauncher: WebLauncher,
) : AssistantTool {

    override val name = "open_camera"

    override val description =
        "Open the camera app. John cannot take the photo itself; the user presses the shutter."

    override val examples = listOf("open the camera", "let me take a photo")

    override fun describeAction(arguments: ToolArguments) = "open the camera"

    override suspend fun execute(arguments: ToolArguments): ToolResult =
        if (webLauncher.openCamera()) {
            ToolResult.Success("Camera's open.")
        } else {
            ToolResult.Failure("I couldn't open a camera app.")
        }
}
