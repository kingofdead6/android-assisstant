package com.john.assistant.core.tool

/**
 * Capabilities a tool may need before it is allowed to run.
 *
 * These are deliberately *John's* permission vocabulary, not Android's. The
 * mapping from a [PermissionKey] to one or more `android.permission.*` strings,
 * special access screens (notification listener, accessibility, exact alarms)
 * or role requests lives in the Android layer. Keeping the vocabulary here lets
 * the whole decision pipeline — and its tests — stay free of the Android SDK.
 */
enum class PermissionKey(
    /** Short label shown in the permissions dashboard. */
    val label: String,
    /** Spoken/first-person explanation of why John is asking. */
    val rationale: String,
) {
    MICROPHONE(
        label = "Microphone",
        rationale = "I need the microphone to hear the wake word and your commands.",
    ),
    POST_NOTIFICATIONS(
        label = "Show notifications",
        rationale = "I show a notification while I'm listening in the background.",
    ),
    NOTIFICATION_ACCESS(
        label = "Notification access",
        rationale = "I need notification access to read and summarise your notifications.",
    ),
    PHONE_CALL(
        label = "Phone",
        rationale = "I need phone permission to place calls for you.",
    ),
    CONTACTS(
        label = "Contacts",
        rationale = "I need contacts to work out who you mean when you say a name.",
    ),
    SMS(
        label = "Send SMS",
        rationale = "I need SMS permission to send text messages on your behalf.",
    ),
    CALENDAR_READ(
        label = "Read calendar",
        rationale = "I need calendar access to tell you what's coming up.",
    ),
    CALENDAR_WRITE(
        label = "Add calendar events",
        rationale = "I need calendar access to create events for you.",
    ),
    CAMERA(
        label = "Camera",
        rationale = "I need the camera to take a photo.",
    ),
    BLUETOOTH(
        label = "Bluetooth",
        rationale = "I need Bluetooth access to see what's connected, such as your earbuds.",
    ),
    EXACT_ALARM(
        label = "Alarms & reminders",
        rationale = "I need alarm permission to schedule reminders at an exact time.",
    ),
    ACCESSIBILITY(
        label = "Accessibility service",
        rationale = "Some apps have no public API. With accessibility I can read the " +
            "screen and tap for you. This is optional and off by default.",
    ),
    NETWORK(
        label = "Internet",
        rationale = "This feature talks to an online service, so it needs a connection.",
    ),
    ;

    companion object {
        fun fromName(raw: String): PermissionKey? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}
