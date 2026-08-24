package com.john.assistant.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Where John's voice is currently going. */
data class AudioRouteState(
    val isBluetoothConnected: Boolean,
    val isWiredHeadsetConnected: Boolean,
    val deviceName: String?,
)

/**
 * Audio routing for headsets and earbuds.
 *
 * The important thing this class does is *not* fight Android. When Galaxy Buds
 * (or any other Bluetooth headset) are connected, the platform already routes
 * media and speech output to them; forcing a route would break the case it was
 * meant to fix. So John asks what is connected, reports it, and lets the
 * platform route.
 *
 * There is no Galaxy Buds API. Samsung publishes no public SDK for them, and
 * they appear to Android as an ordinary A2DP/HFP device — which is exactly what
 * makes them work here. Anything claiming Buds-specific control would be
 * inventing it.
 *
 * The one genuine choice is the *input* route while John is listening. A
 * headset microphone needs the communication device to be set, and the API for
 * that changed in Android 12; both paths are handled in [preferHeadsetMic].
 */
@Singleton
class AudioRouter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun state(): AudioRouteState {
        val outputs = outputDevices()

        val bluetooth = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        val wired = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

        return AudioRouteState(
            isBluetoothConnected = bluetooth != null,
            isWiredHeadsetConnected = wired != null,
            // productName needs BLUETOOTH_CONNECT on Android 12+; without it the
            // platform returns the device model rather than throwing.
            deviceName = (bluetooth ?: wired)?.productName?.toString(),
        )
    }

    /**
     * Ask the platform to capture from a headset microphone if one exists.
     *
     * @return true when a headset mic was selected. False means John will
     *   record from the phone's own microphone, which still works.
     */
    fun preferHeadsetMic(): Boolean {
        val manager = audioManager ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val headset = manager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: return false
            runCatching { manager.setCommunicationDevice(headset) }.getOrDefault(false)
        } else {
            // Pre-Android 12 the only lever is the SCO link, and it is
            // best-effort: the headset decides whether to accept it.
            @Suppress("DEPRECATION")
            runCatching {
                manager.startBluetoothSco()
                manager.isBluetoothScoOn = true
                true
            }.getOrDefault(false)
        }
    }

    /** Undo [preferHeadsetMic]. Always call this once listening finishes. */
    fun releaseHeadsetMic() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                manager.isBluetoothScoOn = false
                manager.stopBluetoothSco()
            }
        }
    }

    /**
     * Attributes for John's own speech.
     *
     * USAGE_ASSISTANT is the correct usage and is what tells the platform to
     * duck music rather than talk over it. It exists from Android 9; older
     * devices fall back to USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, which ducks
     * the same way.
     */
    fun speechAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setUsage(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                AudioAttributes.USAGE_ASSISTANT
            } else {
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            },
        )
        .build()

    private fun outputDevices(): List<AudioDeviceInfo> = runCatching {
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList().orEmpty()
    }.getOrDefault(emptyList())
}
