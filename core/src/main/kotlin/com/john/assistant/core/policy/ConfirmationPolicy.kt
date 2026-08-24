package com.john.assistant.core.policy

import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.RiskLevel

/**
 * Decides which actions John must ask about before doing.
 *
 * Configurable, with one rule that is not: [RiskLevel.HIGH] always requires an
 * explicit yes. A user can loosen confirmations for messaging; they cannot
 * configure John into doing something irreversible on its own initiative, and
 * neither can a model that talks its way into a `never_confirm` entry.
 */
data class ConfirmationPolicy(
    /** Confirm every tool at this risk level or above. */
    val confirmFrom: RiskLevel = RiskLevel.MEDIUM,
    /** Tool names that always ask, whatever their declared risk. */
    val alwaysConfirm: Set<String> = emptySet(),
    /** Tool names the user has explicitly waved through. Ignored for HIGH risk. */
    val neverConfirm: Set<String> = emptySet(),
) {

    fun requiresConfirmation(tool: AssistantTool): Boolean {
        if (tool.riskLevel == RiskLevel.HIGH) return true
        if (tool.name in alwaysConfirm) return true
        if (tool.name in neverConfirm) return false
        return tool.riskLevel.atLeast(confirmFrom)
    }

    companion object {
        /** Ask before anything with an outward-facing effect. The default. */
        val BALANCED = ConfirmationPolicy(confirmFrom = RiskLevel.MEDIUM)

        /** Ask about everything except plainly read-only actions. */
        val CAUTIOUS = ConfirmationPolicy(confirmFrom = RiskLevel.LOW)

        /** Only ask when John genuinely must. High risk still asks. */
        val RELAXED = ConfirmationPolicy(confirmFrom = RiskLevel.HIGH)
    }
}
