package com.john.assistant.platform

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class BatteryState(val percent: Int, val isCharging: Boolean)

data class ConnectivityState(
    val isOnline: Boolean,
    val isWifi: Boolean,
    val isCellular: Boolean,
)

/**
 * Read-only facts about the device.
 *
 * Deliberately read-only, because on a modern Android the writes are not
 * available to an ordinary app:
 *
 *  - `WifiManager.setWifiEnabled` stopped working for third-party apps in
 *    Android 10 and returns false;
 *  - `BluetoothAdapter.enable()` was deprecated in Android 13 and is a no-op
 *    for apps without privileged permissions.
 *
 * So John reports state and offers to *open the right settings panel*, which is
 * what Android actually allows. [wifiSettingsIntent] and
 * [bluetoothEnableIntent] exist for exactly that. Pretending otherwise would
 * mean "turn on Bluetooth" silently doing nothing.
 */
@Singleton
class DeviceStateProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun battery(): BatteryState {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        // isCharging covers AC, USB and wireless. The sticky broadcast is the
        // fallback for devices whose BatteryManager property reads as unknown.
        val charging = manager?.isCharging ?: chargingFromBroadcast()

        return BatteryState(
            percent = percent.coerceIn(0, 100),
            isCharging = charging,
        )
    }

    fun connectivity(): ConnectivityState {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ConnectivityState(isOnline = false, isWifi = false, isCellular = false)

        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
            ?: return ConnectivityState(isOnline = false, isWifi = false, isCellular = false)

        return ConnectivityState(
            // VALIDATED, not just CONNECTED: a captive-portal Wi-Fi is
            // "connected" and cannot reach anything.
            isOnline = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        )
    }

    /** Null when Bluetooth permission is missing or the device has no radio. */
    fun isBluetoothOn(): Boolean? = runCatching {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter?.isEnabled
    }.getOrNull()

    fun deviceDescription(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"

    fun spokenTime(): String = TIME_FORMAT.format(Date())

    fun spokenDate(): String = DATE_FORMAT.format(Date())

    fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    /** The one-line context fact injected into the prompt. */
    fun contextFacts(): List<String> = listOf(
        "It is ${spokenDate()}, ${spokenTime()}.",
    )

    /**
     * The connectivity panel. On Android 10+ this is a bottom-sheet the user can
     * act on without leaving John; older versions get the full settings screen.
     */
    fun wifiSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * The system's "turn Bluetooth on?" dialog.
     *
     * This is the only supported way for a normal app to get Bluetooth enabled,
     * and it requires the user to confirm. There is no silent path.
     */
    fun bluetoothEnableIntent(): Intent =
        Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun bluetoothSettingsIntent(): Intent =
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("DEPRECATION")
    private fun chargingFromBroadcast(): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("h:mm a", Locale.getDefault())
        val DATE_FORMAT = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
    }
}
