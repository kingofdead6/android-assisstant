package com.john.assistant.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.john.assistant.core.util.AssistantLogger
import com.john.assistant.platform.AccessibilityBridge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The optional screen-assistance service.
 *
 * It does almost nothing on its own. [onAccessibilityEvent] deliberately
 * ignores everything: John does not watch the screen, it reads the screen when
 * a tool asks and not otherwise. The service exists to give
 * [AccessibilityBridge] a handle it can use on demand.
 *
 * That is the difference between an assistant that *can* read the screen and
 * one that *is* reading it. Users granting accessibility access are trusting an
 * app with everything they look at, and the least this app can do is not
 * subscribe to the firehose.
 *
 * Android creates this service, so it registers itself with the bridge rather
 * than being injected into it.
 */
@AndroidEntryPoint
class JohnAccessibilityService : AccessibilityService() {

    @Inject lateinit var bridge: AccessibilityBridge

    @Inject lateinit var logger: AssistantLogger

    override fun onServiceConnected() {
        super.onServiceConnected()
        logger.info(TAG, "Screen assistance enabled")
        bridge.attach(this)
    }

    /** Intentionally empty — John pulls, it does not subscribe. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        logger.info(TAG, "Screen assistance disabled")
        bridge.detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        bridge.detach()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "Accessibility"
    }
}
