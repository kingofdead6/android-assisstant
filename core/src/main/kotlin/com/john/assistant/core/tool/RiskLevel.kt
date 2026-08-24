package com.john.assistant.core.tool

/**
 * How much damage a tool can do if John misunderstands.
 *
 * The level is declared by the tool itself and consumed by
 * [com.john.assistant.core.policy.ConfirmationPolicy], which decides whether
 * the user is asked before the action runs.
 */
enum class RiskLevel {
    /** Reversible, no side effects the user cares about: open an app, read the battery. */
    LOW,

    /** Visible to other people or hard to undo: send a message, create an event. */
    MEDIUM,

    /** Money, data loss, or anything John must never do on its own initiative. */
    HIGH,
    ;

    fun atLeast(other: RiskLevel): Boolean = ordinal >= other.ordinal
}
