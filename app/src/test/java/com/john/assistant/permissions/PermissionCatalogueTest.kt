package com.john.assistant.permissions

import android.Manifest
import android.os.Build
import com.john.assistant.core.tool.PermissionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue is pure logic over an API level, so it is tested without a
 * device — which matters, because "does this permission need asking for on
 * Android 11?" is exactly the kind of question that is wrong in production and
 * invisible in a manual test on one phone.
 */
class PermissionCatalogueTest {

    @Test
    fun `microphone is a runtime permission on every supported version`() {
        listOf(Build.VERSION_CODES.O, Build.VERSION_CODES.S, Build.VERSION_CODES.TIRAMISU)
            .forEach { sdk ->
                val permission = PermissionCatalogue.forKey(PermissionKey.MICROPHONE, sdk)
                assertTrue(permission is Permission.Runtime)
                assertEquals(
                    listOf(Manifest.permission.RECORD_AUDIO),
                    (permission as Permission.Runtime).manifestPermissions,
                )
            }
    }

    @Test
    fun `notifications only need asking from Android 13`() {
        assertTrue(
            PermissionCatalogue.forKey(PermissionKey.POST_NOTIFICATIONS, Build.VERSION_CODES.S)
                is Permission.Implicit,
        )
        assertTrue(
            PermissionCatalogue.forKey(
                PermissionKey.POST_NOTIFICATIONS,
                Build.VERSION_CODES.TIRAMISU,
            ) is Permission.Runtime,
        )
    }

    @Test
    fun `bluetooth only needs asking from Android 12`() {
        assertTrue(
            PermissionCatalogue.forKey(PermissionKey.BLUETOOTH, Build.VERSION_CODES.R)
                is Permission.Implicit,
        )

        val onAndroid12 = PermissionCatalogue.forKey(PermissionKey.BLUETOOTH, Build.VERSION_CODES.S)
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_CONNECT),
            (onAndroid12 as Permission.Runtime).manifestPermissions,
        )
    }

    @Test
    fun `exact alarms become a settings visit from Android 12`() {
        assertTrue(
            PermissionCatalogue.forKey(PermissionKey.EXACT_ALARM, Build.VERSION_CODES.R)
                is Permission.Implicit,
        )
        assertTrue(
            PermissionCatalogue.forKey(PermissionKey.EXACT_ALARM, Build.VERSION_CODES.S)
                is Permission.SpecialAccess,
        )
    }

    @Test
    fun `notification access and accessibility are never runtime dialogs`() {
        // There is no API to request either. Treating them as runtime
        // permissions produces a button that silently does nothing.
        listOf(PermissionKey.NOTIFICATION_ACCESS, PermissionKey.ACCESSIBILITY).forEach { key ->
            val permission = PermissionCatalogue.forKey(key, Build.VERSION_CODES.TIRAMISU)
            assertTrue("$key should be special access", permission is Permission.SpecialAccess)
        }
    }

    @Test
    fun `every key has an entry and a rationale`() {
        PermissionKey.entries.forEach { key ->
            assertEquals(key, PermissionCatalogue.forKey(key, Build.VERSION_CODES.TIRAMISU).key)
            assertTrue("$key has no rationale", key.rationale.isNotBlank())
            assertTrue("$key has no label", key.label.isNotBlank())
        }
    }
}
