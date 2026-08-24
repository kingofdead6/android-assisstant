package com.john.assistant.platform

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/** What John managed to do on screen. */
sealed interface ScreenAction {
    data object Done : ScreenAction
    data class Failed(val reason: String) : ScreenAction
    data object NotEnabled : ScreenAction
}

/**
 * The optional last resort: reading and tapping the screen.
 *
 * Everything else John does goes through a real API. This exists for the cases
 * where there is genuinely no other route — an app with no intent, no media
 * session and no public interface at all.
 *
 * Three properties are deliberate:
 *
 *  - **Off unless the user turns it on.** The service is declared in the
 *    manifest but does nothing until enabled in Android's accessibility
 *    settings, which cannot be done from inside John.
 *  - **Pull, not push.** The service subscribes to almost nothing; John reads
 *    the window when a tool asks and not otherwise. There is no background
 *    stream of screen contents, and nothing is stored.
 *  - **Honest about failure.** UI automation breaks whenever the target app
 *    redesigns. Every method here can return [ScreenAction.Failed], and callers
 *    say so out loud rather than silently doing nothing.
 *
 * The service registers itself here on connect because Android constructs it,
 * not Hilt — a service instance cannot be injected into a singleton graph, so
 * the reference goes the other way.
 */
@Singleton
class AccessibilityBridge @Inject constructor() {

    // Weak so a destroyed service can be collected; the system may recreate it
    // at any time and a strong reference would leak the old instance.
    private var serviceRef: WeakReference<AccessibilityService>? = null

    val isConnected: Boolean get() = service() != null

    fun attach(service: AccessibilityService) {
        serviceRef = WeakReference(service)
    }

    fun detach() {
        serviceRef = null
    }

    /**
     * Visible text in the foreground window.
     *
     * @return null when the service is not enabled — distinct from an empty
     *   list, which means the window genuinely has no readable text.
     */
    fun readScreenText(limit: Int = MAX_NODES): List<String>? {
        val root = service()?.rootInActiveWindow ?: return null
        val collected = LinkedHashSet<String>()

        traverse(root, limit) { node ->
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) collected += text
            else node.contentDescription?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { collected += it }
        }

        return collected.toList()
    }

    /**
     * Tap the first clickable element matching [text].
     *
     * Matching walks up from the matched node: the text of a button is usually
     * on a child view, while the click handler is on an ancestor, so clicking
     * the node that holds the text alone fails on most real layouts.
     */
    fun clickByText(text: String): ScreenAction {
        val root = service()?.rootInActiveWindow ?: return ScreenAction.NotEnabled
        val query = text.trim()
        if (query.isEmpty()) return ScreenAction.Failed("I don't know what to tap.")

        val matches = runCatching { root.findAccessibilityNodeInfosByText(query) }
            .getOrNull()
            .orEmpty()

        if (matches.isEmpty()) {
            return ScreenAction.Failed("I couldn't find \"$query\" on the screen.")
        }

        for (match in matches) {
            var candidate: AccessibilityNodeInfo? = match
            var depth = 0
            while (candidate != null && depth < MAX_ANCESTOR_WALK) {
                if (candidate.isClickable && candidate.isEnabled) {
                    val clicked = runCatching {
                        candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrDefault(false)
                    if (clicked) return ScreenAction.Done
                }
                candidate = candidate.parent
                depth++
            }
        }

        return ScreenAction.Failed("I found \"$query\" but couldn't tap it.")
    }

    /** Type into the focused editable field. */
    fun setTextInFocusedField(text: String): ScreenAction {
        val root = service()?.rootInActiveWindow ?: return ScreenAction.NotEnabled

        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return ScreenAction.Failed("No text field is focused.")

        val arguments = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }

        val ok = runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }.getOrDefault(false)

        return if (ok) ScreenAction.Done else ScreenAction.Failed("I couldn't type that.")
    }

    /** Back, home, recents — the buttons, without needing to find them. */
    fun performGlobalAction(action: GlobalAction): ScreenAction {
        val service = service() ?: return ScreenAction.NotEnabled
        val ok = runCatching { service.performGlobalAction(action.code) }.getOrDefault(false)
        return if (ok) ScreenAction.Done else ScreenAction.Failed("I couldn't do that.")
    }

    private fun service(): AccessibilityService? = serviceRef?.get()

    /**
     * Breadth-first walk with a hard node budget.
     *
     * A deep or cyclic hierarchy would otherwise stall the assistant while it
     * walked thousands of nodes; the budget bounds the work regardless of what
     * the foreground app is doing.
     */
    private inline fun traverse(
        root: AccessibilityNodeInfo,
        limit: Int,
        visit: (AccessibilityNodeInfo) -> Unit,
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < limit) {
            val node = queue.removeFirst()
            visited++
            visit(node)

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
    }

    private companion object {
        const val MAX_NODES = 400
        const val MAX_ANCESTOR_WALK = 6
    }
}

/** The system navigation actions an accessibility service may perform. */
enum class GlobalAction(val code: Int) {
    BACK(AccessibilityService.GLOBAL_ACTION_BACK),
    HOME(AccessibilityService.GLOBAL_ACTION_HOME),
    RECENTS(AccessibilityService.GLOBAL_ACTION_RECENTS),
    NOTIFICATIONS(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
}
