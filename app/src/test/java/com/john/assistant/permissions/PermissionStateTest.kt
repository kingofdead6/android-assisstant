package com.john.assistant.permissions

import com.john.assistant.core.tool.PermissionKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dashboard's button has to match how the permission is actually granted.
 * Offering "Allow" for accessibility, or a dialog after two refusals, produces
 * a control that does nothing — the most confusing possible outcome.
 */
class PermissionStateTest {

    @Test
    fun `a granted permission offers no action`() {
        assertEquals(PermissionAction.NONE, state(PermissionStatus.GRANTED).action)
        assertEquals(PermissionAction.NONE, state(PermissionStatus.NOT_REQUIRED).action)
    }

    @Test
    fun `a first refusal can still be requested`() {
        assertEquals(PermissionAction.REQUEST, state(PermissionStatus.DENIED).action)
    }

    @Test
    fun `a permanent refusal routes to app settings instead of a dialog`() {
        assertEquals(
            PermissionAction.OPEN_APP_SETTINGS,
            state(PermissionStatus.PERMANENTLY_DENIED).action,
        )
    }

    @Test
    fun `special access always routes to its settings screen`() {
        val accessibility = PermissionState(
            key = PermissionKey.ACCESSIBILITY,
            status = PermissionStatus.NEEDS_SETTINGS_VISIT,
            permission = PermissionCatalogue.forKey(PermissionKey.ACCESSIBILITY),
        )
        assertEquals(PermissionAction.OPEN_SETTINGS_SCREEN, accessibility.action)
    }

    @Test
    fun `usability covers both granted and not-required`() {
        assertEquals(true, PermissionStatus.GRANTED.isUsable)
        assertEquals(true, PermissionStatus.NOT_REQUIRED.isUsable)
        assertEquals(false, PermissionStatus.DENIED.isUsable)
        assertEquals(false, PermissionStatus.NEEDS_SETTINGS_VISIT.isUsable)
    }

    private fun state(status: PermissionStatus) = PermissionState(
        key = PermissionKey.MICROPHONE,
        status = status,
        permission = PermissionCatalogue.forKey(PermissionKey.MICROPHONE),
    )
}
