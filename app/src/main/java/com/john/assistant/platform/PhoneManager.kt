package com.john.assistant.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls and text messages.
 *
 * Two deliberately different postures:
 *
 *  - **Calling** places the call directly with `ACTION_CALL`, because a call is
 *    loud, obvious and instantly cancellable — the user knows the moment it
 *    happens, and stopping it is one tap.
 *  - **Texting** is the opposite: a sent message cannot be recalled and the
 *    recipient sees it before the user notices a mistake. So John confirms
 *    first (enforced by the tool's risk level, not here) and, if the user has
 *    not granted SEND_SMS, falls back to opening the messaging app with the
 *    message pre-filled — which needs no permission and leaves the send button
 *    to the user.
 */
@Singleton
class PhoneManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun hasTelephony(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    /** Place a call. Requires CALL_PHONE; the caller checks that first. */
    fun call(number: String): Boolean {
        val intent = Intent(Intent.ACTION_CALL, telUri(number))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Open the dialler with the number filled in. Needs no permission. */
    fun dial(number: String): Boolean {
        val intent = Intent(Intent.ACTION_DIAL, telUri(number))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * Send an SMS directly. Requires SEND_SMS.
     *
     * Long messages are split by the platform; sending the parts separately is
     * what `divideMessage` + `sendMultipartTextMessage` is for, and skipping it
     * silently truncates anything over one segment.
     */
    fun sendSms(number: String, body: String): Boolean = runCatching {
        val manager = smsManager() ?: return false
        val parts = manager.divideMessage(body)
        if (parts.size > 1) {
            manager.sendMultipartTextMessage(number, null, parts, null, null)
        } else {
            manager.sendTextMessage(number, null, body, null, null)
        }
        true
    }.getOrDefault(false)

    /** Open the user's messaging app with recipient and body pre-filled. */
    fun composeSms(number: String, body: String): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
            .putExtra("sms_body", body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? = runCatching {
        // getSystemService(SmsManager::class.java) exists from API 31; the static
        // accessor is deprecated there but is the only option on API 26–30.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }.getOrNull()

    private fun telUri(number: String): Uri = Uri.parse("tel:${Uri.encode(number)}")
}
