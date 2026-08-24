package com.john.assistant.core.assistant

import com.john.assistant.core.tool.PermissionKey

/**
 * Whether John is allowed to use a capability.
 *
 * The Android implementation folds together runtime permissions, special access
 * (notification listener, accessibility, exact alarms) and role state behind
 * this one question, so the pipeline never grows Android-shaped branches.
 */
fun interface PermissionGate {
    suspend fun isGranted(permission: PermissionKey): Boolean

    companion object {
        /** Everything granted. Used in tests and in the JVM core. */
        val ALLOW_ALL = PermissionGate { true }
    }
}

/**
 * Facts about the device that change what John should say or choose.
 *
 * Kept deliberately small: only things that affect *decisions*. Battery level,
 * for instance, is a tool result, not context — it does not belong in every prompt.
 */
interface DeviceEnvironment {

    suspend fun isOnline(): Boolean

    suspend fun isAccessibilityEnabled(): Boolean

    /** One-liners injected into the prompt, e.g. "It is Sunday 23 August, 21:40." */
    suspend fun facts(): List<String>

    companion object {
        val OFFLINE_UNKNOWN: DeviceEnvironment = object : DeviceEnvironment {
            override suspend fun isOnline(): Boolean = true
            override suspend fun isAccessibilityEnabled(): Boolean = false
            override suspend fun facts(): List<String> = emptyList()
        }
    }
}
