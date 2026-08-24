package com.john.assistant.platform

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The torch.
 *
 * `setTorchMode` needs no permission — a pleasant exception. It does throw when
 * the camera is in use by another app, which is the common real-world failure
 * and is reported as such rather than swallowed.
 *
 * Android has no "is the torch on" query that survives another app changing it,
 * so state is tracked through the torch callback rather than assumed.
 */
@Singleton
class FlashlightController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val cameraManager: CameraManager?
        get() = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val torchOn = AtomicBoolean(false)
    private var callbackRegistered = false

    val isOn: Boolean get() = torchOn.get()

    fun hasFlashlight(): Boolean = flashCameraId() != null

    /** @return the new state, or null when there is no usable torch. */
    fun setEnabled(enabled: Boolean): Boolean? {
        val manager = cameraManager ?: return null
        val cameraId = flashCameraId() ?: return null

        ensureCallbackRegistered(manager)

        return runCatching {
            manager.setTorchMode(cameraId, enabled)
            torchOn.set(enabled)
            enabled
        }.getOrNull()
    }

    fun toggle(): Boolean? = setEnabled(!isOn)

    /**
     * Keeps [isOn] honest when something else — a quick-settings tile, the
     * camera app — changes the torch behind John's back.
     */
    private fun ensureCallbackRegistered(manager: CameraManager) {
        if (callbackRegistered) return
        runCatching {
            manager.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId == flashCameraId()) torchOn.set(enabled)
                    }

                    override fun onTorchModeUnavailable(cameraId: String) {
                        if (cameraId == flashCameraId()) torchOn.set(false)
                    }
                },
                null,
            )
            callbackRegistered = true
        }
    }

    /** The first camera that actually has a flash — usually, but not always, the back one. */
    private fun flashCameraId(): String? = runCatching {
        val manager = cameraManager ?: return null
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()
}
