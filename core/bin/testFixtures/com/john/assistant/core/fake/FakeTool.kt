package com.john.assistant.core.fake

import com.john.assistant.core.assistant.DeviceEnvironment
import com.john.assistant.core.assistant.PermissionGate
import com.john.assistant.core.memory.MemoryEntry
import com.john.assistant.core.memory.MemoryStore
import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.PermissionKey
import com.john.assistant.core.tool.RiskLevel
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.tool.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A tool whose behaviour a test dictates, and which records how it was called. */
class FakeTool(
    override val name: String,
    override val description: String = "Fake tool",
    override val parameters: ToolParameters = ToolParameters.NONE,
    override val riskLevel: RiskLevel = RiskLevel.LOW,
    override val requiredPermissions: Set<PermissionKey> = emptySet(),
    override val worksOffline: Boolean = true,
    private val action: String = "do the fake thing",
    var result: ToolResult = ToolResult.Success("Done."),
) : AssistantTool {

    val invocations = mutableListOf<ToolArguments>()

    /** Set to have [execute] throw, to prove the orchestrator survives it. */
    var throwOnExecute: Throwable? = null

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        invocations += arguments
        throwOnExecute?.let { throw it }
        return result
    }

    override fun describeAction(arguments: ToolArguments): String = action
}

/** Grants only the listed permissions. */
class FakePermissionGate(granted: Set<PermissionKey> = emptySet()) : PermissionGate {
    var granted: MutableSet<PermissionKey> = granted.toMutableSet()
    override suspend fun isGranted(permission: PermissionKey): Boolean = permission in granted
}

/** Controllable [DeviceEnvironment]. */
class FakeEnvironment(
    var online: Boolean = true,
    var accessibility: Boolean = false,
    var facts: List<String> = emptyList(),
) : DeviceEnvironment {
    override suspend fun isOnline(): Boolean = online
    override suspend fun isAccessibilityEnabled(): Boolean = accessibility
    override suspend fun facts(): List<String> = facts
}

/** In-memory [MemoryStore]. */
class FakeMemoryStore(override val isEnabled: Boolean = true) : MemoryStore {

    private val entries = MutableStateFlow<List<MemoryEntry>>(emptyList())

    override fun observeAll(): Flow<List<MemoryEntry>> = entries

    override suspend fun all(): List<MemoryEntry> = entries.value

    override suspend fun get(key: String): MemoryEntry? = entries.value.firstOrNull { it.key == key }

    override suspend fun remember(entry: MemoryEntry): MemoryEntry {
        if (!isEnabled) return entry
        entries.value = entries.value.filterNot { it.key == entry.key } + entry
        return entry
    }

    override suspend fun forget(key: String) {
        entries.value = entries.value.filterNot { it.key == key }
    }

    override suspend fun clear() {
        entries.value = emptyList()
    }
}
