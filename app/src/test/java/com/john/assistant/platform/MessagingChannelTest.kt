package com.john.assistant.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingChannelTest {

    @Test
    fun `resolves channel ids case insensitively`() {
        assertEquals(MessagingChannel.WHATSAPP, MessagingChannel.fromId("whatsapp"))
        assertEquals(MessagingChannel.WHATSAPP, MessagingChannel.fromId("WhatsApp"))
        assertEquals(MessagingChannel.SMS, MessagingChannel.fromId(" sms "))
    }

    @Test
    fun `an unknown channel is not silently mapped onto another`() {
        // Falling back to SMS here would send a text when the user said Signal.
        assertNull(MessagingChannel.fromId("carrier_pigeon"))
        assertNull(MessagingChannel.fromId(""))
    }

    @Test
    fun `every channel except SMS names at least one package`() {
        MessagingChannel.entries
            .filterNot { it == MessagingChannel.SMS }
            .forEach { assertTrue("${it.id} has no packages", it.packageNames.isNotEmpty()) }
    }

    @Test
    fun `the ids offered to the model match the enum`() {
        assertEquals(MessagingChannel.entries.map { it.id }, MessagingChannel.ids)
    }
}
