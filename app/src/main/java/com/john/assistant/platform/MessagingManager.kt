package com.john.assistant.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A messaging app John knows how to hand a message to. */
enum class MessagingChannel(
    val id: String,
    val displayName: String,
    val packageNames: List<String>,
) {
    SMS("sms", "text message", emptyList()),
    WHATSAPP("whatsapp", "WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b")),
    TELEGRAM("telegram", "Telegram", listOf("org.telegram.messenger", "org.telegram.messenger.web")),
    MESSENGER("messenger", "Messenger", listOf("com.facebook.orca")),
    SIGNAL("signal", "Signal", listOf("org.thoughtcrime.securesms")),
    ;

    companion object {
        fun fromId(raw: String): MessagingChannel? =
            entries.firstOrNull { it.id.equals(raw.trim(), ignoreCase = true) }

        val ids: List<String> get() = entries.map { it.id }
    }
}

/** How far John got with a message. */
sealed interface MessageOutcome {
    /** The message is gone — only SMS with SEND_SMS reaches this. */
    data object Sent : MessageOutcome

    /**
     * The app opened with the message filled in; the user taps send.
     *
     * This is not a lesser version of [Sent] to be papered over — the caller
     * must tell the user the message is *waiting*, or they will believe it went.
     */
    data class Composed(val channel: MessagingChannel) : MessageOutcome

    data class Failed(val reason: String) : MessageOutcome
}

/**
 * Messaging apps other than SMS.
 *
 * The honest position, which the docs repeat: **no third-party app can send a
 * WhatsApp, Telegram or Messenger message on the user's behalf.** None of them
 * publishes an API for it, and Android provides no general mechanism for one
 * app to operate another. What is genuinely available is a deep link that opens
 * the conversation with the text already typed, leaving one tap.
 *
 * John therefore does the most it legitimately can — pre-fill and open — and
 * reports [MessageOutcome.Composed] rather than claiming a send. Anything else
 * would require driving another app's UI through the accessibility service,
 * which is fragile, breaks on every redesign, and is not something an assistant
 * should do behind a user's back.
 */
@Singleton
class MessagingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val phoneManager: PhoneManager,
) {

    fun isAvailable(channel: MessagingChannel): Boolean = when (channel) {
        MessagingChannel.SMS -> phoneManager.hasTelephony()
        else -> installedPackage(channel) != null
    }

    fun availableChannels(): List<MessagingChannel> = MessagingChannel.entries.filter(::isAvailable)

    /**
     * @param allowDirectSend true only when the caller has SEND_SMS and the user
     *   has confirmed. Ignored by every channel except SMS.
     */
    fun send(
        channel: MessagingChannel,
        number: String?,
        body: String,
        allowDirectSend: Boolean,
    ): MessageOutcome = when (channel) {
        MessagingChannel.SMS -> sendSms(number, body, allowDirectSend)
        MessagingChannel.WHATSAPP -> composeWhatsApp(number, body)
        else -> composeViaShareIntent(channel, body)
    }

    private fun sendSms(number: String?, body: String, allowDirectSend: Boolean): MessageOutcome {
        if (number.isNullOrBlank()) return MessageOutcome.Failed("I don't have a number to text.")

        if (allowDirectSend && phoneManager.sendSms(number, body)) return MessageOutcome.Sent

        return if (phoneManager.composeSms(number, body)) {
            MessageOutcome.Composed(MessagingChannel.SMS)
        } else {
            MessageOutcome.Failed("I couldn't open your messaging app.")
        }
    }

    /**
     * WhatsApp's documented deep link.
     *
     * `wa.me` needs a number in international format with no punctuation. When
     * there is no number John opens WhatsApp's own contact picker instead,
     * which is as far as the deep link goes.
     */
    private fun composeWhatsApp(number: String?, body: String): MessageOutcome {
        val whatsapp = installedPackage(MessagingChannel.WHATSAPP)
            ?: return MessageOutcome.Failed("WhatsApp isn't installed on this phone.")

        val digits = number?.filter { it.isDigit() }.orEmpty()
        val uri = if (digits.isNotEmpty()) {
            Uri.parse("https://wa.me/$digits?text=${Uri.encode(body)}")
        } else {
            Uri.parse("https://wa.me/?text=${Uri.encode(body)}")
        }

        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(whatsapp)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return if (runCatching { context.startActivity(intent) }.isSuccess) {
            MessageOutcome.Composed(MessagingChannel.WHATSAPP)
        } else {
            MessageOutcome.Failed("I couldn't open WhatsApp.")
        }
    }

    /**
     * The generic route: a plain-text share targeted at one app.
     *
     * The recipient cannot be pre-selected this way — these apps expose no
     * contact parameter — so the user picks the chat. That limitation is real
     * and is reported honestly by returning [MessageOutcome.Composed].
     */
    private fun composeViaShareIntent(channel: MessagingChannel, body: String): MessageOutcome {
        val target = installedPackage(channel)
            ?: return MessageOutcome.Failed("${channel.displayName} isn't installed on this phone.")

        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .setPackage(target)
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return if (runCatching { context.startActivity(intent) }.isSuccess) {
            MessageOutcome.Composed(channel)
        } else {
            MessageOutcome.Failed("I couldn't open ${channel.displayName}.")
        }
    }

    private fun installedPackage(channel: MessagingChannel): String? =
        channel.packageNames.firstOrNull { packageName ->
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        }
}
