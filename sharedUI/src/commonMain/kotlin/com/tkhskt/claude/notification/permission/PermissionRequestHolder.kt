package com.tkhskt.claude.notification.permission

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PendingRequest(
    val id: String,
    val request: PermissionRequest,
    /**
     * 0-based line offset of the diff snippet inside the target file, so the
     * popover can render line numbers in file coordinates instead of
     * snippet-local "1, 2, 3…". Computed by the JVM server (which reads
     * `file_path`) and 0 when no file is involved or the lookup failed.
     */
    val fileLineOffset: Int = 0,
    internal val deferred: CompletableDeferred<DecisionResult>,
)

enum class DecisionSource { USER, EXTERNAL }

data class DecisionResult(val decision: Decision, val source: DecisionSource)

/**
 * State holder for in-flight permission requests.
 *
 * Multiple requests can be pending simultaneously; the UI renders them as
 * tabs and the user picks Allow / Deny per tab. Each [submit] call has its
 * own [CompletableDeferred], so concurrent Ktor handlers don't block each
 * other. ID generation is the only place that needs synchronization.
 */
class PermissionRequestHolder {
    private val _pending = MutableStateFlow<List<PendingRequest>>(emptyList())
    val pending: StateFlow<List<PendingRequest>> = _pending.asStateFlow()

    /** ID of the tab the UI currently shows. Auto-selects the first arrival
     *  when it was previously null. Later arrivals don't steal selection. */
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val _popoverVisible = MutableStateFlow(false)
    val popoverVisible: StateFlow<Boolean> = _popoverVisible.asStateFlow()

    private val idMutex = Mutex()
    private var idCounter = 0L

    suspend fun submit(request: PermissionRequest, fileLineOffset: Int = 0): Decision {
        val id = idMutex.withLock {
            idCounter += 1
            "req-$idCounter"
        }
        val deferred = CompletableDeferred<DecisionResult>()
        val entry = PendingRequest(id, request, fileLineOffset, deferred)
        _pending.update { it + entry }
        _selectedId.update { current -> current ?: id }
        // Re-open the popover on every new arrival so the user notices.
        _popoverVisible.value = true
        val result: DecisionResult
        try {
            result = deferred.await()
        } finally {
            _pending.update { list -> list.filterNot { it.id == id } }
            _selectedId.update { current ->
                // If this tab was selected, fall back to the first remaining.
                if (current == id) _pending.value.firstOrNull()?.id else current
            }
        }
        // Auto-close only when the user themselves resolved the *last*
        // pending request. EXTERNAL resolutions (PostToolUse / UserPromptSubmit)
        // leave the popover open so the user sees what happened.
        if (result.source == DecisionSource.USER && _pending.value.isEmpty()) {
            _popoverVisible.value = false
        }
        return result.decision
    }

    /**
     * External resolution. Behavior depends on the hook event:
     *  - `PostToolUse`        → matches exactly one tab (session + tool + input) → ALLOW
     *  - `UserPromptSubmit`   → matches every tab from the same session (the
     *    user moved on, so all stale popovers in that session are abandoned) → DENY
     */
    fun resolveExternally(other: PermissionRequest): Boolean {
        val current = _pending.value
        if (current.isEmpty()) return false
        return when (other.hookEventName) {
            "UserPromptSubmit" -> {
                val matches = current.filter { it.request.sessionId == other.sessionId }
                if (matches.isEmpty()) return false
                matches.forEach {
                    it.deferred.complete(DecisionResult(Decision.DENY, DecisionSource.EXTERNAL))
                }
                true
            }
            else -> {
                val match = current.firstOrNull { matches(it.request, other) } ?: return false
                match.deferred.complete(DecisionResult(Decision.ALLOW, DecisionSource.EXTERNAL))
                true
            }
        }
    }

    private fun matches(a: PermissionRequest, b: PermissionRequest): Boolean =
        a.sessionId == b.sessionId &&
            a.toolName == b.toolName &&
            a.toolInput == b.toolInput

    fun allow(id: String) = complete(id, Decision.ALLOW)

    fun deny(id: String) = complete(id, Decision.DENY)

    fun selectTab(id: String) {
        if (_pending.value.any { it.id == id }) {
            _selectedId.value = id
        }
    }

    fun togglePopover() {
        _popoverVisible.update { !it }
    }

    fun setPopoverVisible(visible: Boolean) {
        _popoverVisible.value = visible
    }

    private fun complete(id: String, decision: Decision) {
        val entry = _pending.value.firstOrNull { it.id == id } ?: return
        entry.deferred.complete(DecisionResult(decision, DecisionSource.USER))
    }
}
