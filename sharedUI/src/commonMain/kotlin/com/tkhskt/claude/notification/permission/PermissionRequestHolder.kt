package com.tkhskt.claude.notification.permission

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class PendingRequest(
    val request: PermissionRequest,
    internal val deferred: CompletableDeferred<DecisionResult>,
)

enum class DecisionSource { USER, EXTERNAL }

data class DecisionResult(val decision: Decision, val source: DecisionSource)

class PermissionRequestHolder(
    /**
     * Upper bound on how long [submit] waits for the user. Must be strictly
     * shorter than the hook script's `curl --max-time` so the server responds
     * (or cleans up) before the client gives up and abandons the socket.
     */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val _pending = MutableStateFlow<PendingRequest?>(null)
    val pending: StateFlow<PendingRequest?> = _pending.asStateFlow()

    private val _popoverVisible = MutableStateFlow(false)
    val popoverVisible: StateFlow<Boolean> = _popoverVisible.asStateFlow()

    /**
     * Sticky record of the most recent request that timed out. Cleared when a
     * new request arrives or when the user explicitly dismisses it via the
     * popover. Used by the UI to render a "previous request timed out" state
     * after reopening the popover.
     */
    private val _lastTimeout = MutableStateFlow<PermissionRequest?>(null)
    val lastTimeout: StateFlow<PermissionRequest?> = _lastTimeout.asStateFlow()

    private val submitMutex = Mutex()

    /**
     * Throws [TimeoutCancellationException] when the user hasn't decided
     * within [timeoutMs]. In all exit paths — decision, timeout, or cancellation —
     * `_pending` and `_popoverVisible` are reset so the mutex releases promptly
     * and the next queued request can take over.
     */
    suspend fun submit(request: PermissionRequest): Decision = submitMutex.withLock {
        _lastTimeout.value = null
        val deferred = CompletableDeferred<DecisionResult>()
        _pending.value = PendingRequest(request, deferred)
        _popoverVisible.value = true
        try {
            val result = try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                // Timeout: surface in TimeoutView, flip tray color, close popover.
                _lastTimeout.value = request
                _popoverVisible.value = false
                throw e
            }
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

    fun dismissTimeout() {
        _lastTimeout.value = null
    }

    /**
     * Resolves the in-flight submit when [other] refers to the same request the
     * popover is currently waiting on. The deferred is *completed* (not cancelled)
     * with a synthetic decision derived from the hook event so the
     * `PermissionRequest` HTTP response carries a definitive decision and Claude
     * does not re-fire the prompt:
     *
     * - `PostToolUse`         → `ALLOW` (tool executed successfully)
     * - `PostToolUseFailure`  → `DENY`  (tool failed, most often a permission deny)
     *
     * Returns whether a resolution actually happened. `_popoverVisible` is left
     * untouched; only `_pending` is cleared (in `submit`'s finally) so the popover
     * the user may currently be looking at transitions to the empty state.
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

    companion object {
        // 60s — short enough that a popover stuck after a Claude-Code terminal
        // deny (which fires NO hook event in any direction — verified against
        // Claude Code's hook lifecycle) clears in a minute. Must stay under
        // the hook script's `curl --max-time` so we time out and the client
        // gets a response before the socket dies.
        const val DEFAULT_TIMEOUT_MS: Long = 60_000L
    }
}
