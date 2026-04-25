package com.tkhskt.claude.notification.permission

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PendingRequest(
    val request: PermissionRequest,
    internal val deferred: CompletableDeferred<DecisionResult>,
)

enum class DecisionSource { USER, EXTERNAL }

data class DecisionResult(val decision: Decision, val source: DecisionSource)

class PermissionRequestHolder {
    private val _pending = MutableStateFlow<PendingRequest?>(null)
    val pending: StateFlow<PendingRequest?> = _pending.asStateFlow()

    private val _popoverVisible = MutableStateFlow(false)
    val popoverVisible: StateFlow<Boolean> = _popoverVisible.asStateFlow()

    private val submitMutex = Mutex()

    /**
     * Suspends until the user (or an external signal — see [resolveExternally])
     * resolves the request. There is no built-in time bound: a popover with no
     * resolution simply remains pending until the user decides, the popover
     * is dismissed via Allow/Deny, or `UserPromptSubmit` clears it when the
     * user types their next prompt.
     */
    suspend fun submit(request: PermissionRequest): Decision = submitMutex.withLock {
        val deferred = CompletableDeferred<DecisionResult>()
        _pending.value = PendingRequest(request, deferred)
        _popoverVisible.value = true
        try {
            val result = deferred.await()
            // Only USER decisions close the popover. EXTERNAL resolutions (the
            // tool already finished elsewhere) just clear pending so the popover
            // a user is watching transitions to the empty state in place.
            if (result.source == DecisionSource.USER) {
                _popoverVisible.value = false
            }
            result.decision
        } finally {
            _pending.value = null
        }
    }

    /**
     * Resolves the in-flight submit when [other] refers to the same request the
     * popover is currently waiting on. The deferred is *completed* with a
     * synthetic decision derived from the hook event:
     *
     * - `PostToolUse`        → `ALLOW` (tool executed)
     * - `UserPromptSubmit`   → `DENY`  (user abandoned the popover)
     */
    fun resolveExternally(other: PermissionRequest): Boolean {
        val current = _pending.value ?: return false
        val a = current.request
        val matched = when (other.hookEventName) {
            // `UserPromptSubmit` is session-level (no tool_name / tool_input).
            // Semantics: "the user moved on to typing a new prompt, so any
            // popover still pending is stale" — DENY-resolve.
            "UserPromptSubmit" -> a.sessionId == other.sessionId
            else -> matches(a, other)
        }
        if (!matched) return false
        val decision = when (other.hookEventName) {
            "UserPromptSubmit" -> Decision.DENY
            else -> Decision.ALLOW
        }
        current.deferred.complete(DecisionResult(decision, DecisionSource.EXTERNAL))
        return true
    }

    private fun matches(a: PermissionRequest, b: PermissionRequest): Boolean =
        a.sessionId == b.sessionId &&
            a.toolName == b.toolName &&
            a.toolInput == b.toolInput

    fun allow() = complete(Decision.ALLOW)

    fun deny() = complete(Decision.DENY)

    fun togglePopover() {
        _popoverVisible.update { !it }
    }

    fun setPopoverVisible(visible: Boolean) {
        _popoverVisible.value = visible
    }

    private fun complete(decision: Decision) {
        val current = _pending.value ?: return
        current.deferred.complete(DecisionResult(decision, DecisionSource.USER))
    }
}
