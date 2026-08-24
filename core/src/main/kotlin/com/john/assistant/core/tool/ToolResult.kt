package com.john.assistant.core.tool

/**
 * The only thing a tool is allowed to return.
 *
 * Tools never throw for expected conditions and never speak to the user
 * directly — they describe an outcome, and the orchestrator turns it into
 * speech, a permission request or a confirmation prompt.
 */
sealed interface ToolResult {

    /**
     * The action ran.
     *
     * @param message what John should say. Written as natural speech, because
     *   this is what gets spoken when response phrasing is set to direct.
     * @param data structured facts the model may use for a follow-up turn.
     * @param spoken set false for actions whose result is obvious on screen.
     */
    data class Success(
        val message: String,
        val data: Map<String, Any?> = emptyMap(),
        val spoken: Boolean = true,
    ) : ToolResult

    /**
     * The action did not run.
     *
     * @param recoverable true when retrying or rephrasing could work; false for
     *   hard limits ("this phone has no flashlight").
     */
    data class Failure(
        val message: String,
        val recoverable: Boolean = true,
        val cause: Throwable? = null,
    ) : ToolResult

    /** John needs a capability the user has not granted yet. */
    data class RequiresPermission(
        val permission: PermissionKey,
        val message: String = permission.rationale,
    ) : ToolResult

    /**
     * The tool understood the request but wants an explicit yes first.
     *
     * [retryArguments] is what will be re-executed on confirmation — it may
     * differ from the original arguments when the tool has resolved something
     * along the way (a contact name into a specific number, say).
     */
    data class RequiresConfirmation(
        val confirmationMessage: String,
        val retryArguments: ToolArguments,
    ) : ToolResult

    /**
     * The request was ambiguous. John asks and the answer is resolved against
     * [options] on the next turn.
     */
    data class NeedsClarification(
        val question: String,
        val options: List<ClarificationOption>,
    ) : ToolResult
}

/** One choice offered by [ToolResult.NeedsClarification]. */
data class ClarificationOption(
    /** What John reads out, e.g. "Mobile" or "YouTube Music". */
    val label: String,
    /** Arguments to re-run the tool with if this option is chosen. */
    val arguments: ToolArguments,
)
