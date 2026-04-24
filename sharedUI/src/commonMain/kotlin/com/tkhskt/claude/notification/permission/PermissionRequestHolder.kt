package com.tkhskt.claude.notification.permission

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class PendingRequest(
    val request: PermissionRequest,
    internal val deferred: CompletableDeferred<Decision>,
)

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

    /** Emits the request that timed out (user never decided within [timeoutMs]). */
    private val _timeouts = MutableSharedFlow<PermissionRequest>(extraBufferCapacity = 8)
    val timeouts: SharedFlow<PermissionRequest> = _timeouts.asSharedFlow()

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
        val deferred = CompletableDeferred<Decision>()
        _pending.value = PendingRequest(request, deferred)
        _popoverVisible.value = true
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            _lastTimeout.value = request
            _timeouts.tryEmit(request)
            throw e
        } finally {
            _pending.value = null
            _popoverVisible.value = false
        }
    }

    fun dismissTimeout() {
        _lastTimeout.value = null
    }

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
        current.deferred.complete(decision)
    }

    companion object {
        // TEMPORARY: 15s for end-to-end timeout testing. Revert to 580_000L
        // (9m40s) after verifying the notification fires correctly.
        const val DEFAULT_TIMEOUT_MS: Long = 15_000L
    }
}
