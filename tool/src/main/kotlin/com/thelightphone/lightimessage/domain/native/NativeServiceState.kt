package com.thelightphone.lightimessage.domain.native

/**
 * Unix domain socket connection state for native service IPC. Follows state machine defined in
 * milestone-3.md § 5.1.
 */
sealed class NativeServiceState {
    /** No active connection. Initial state. */
    object Disconnected : NativeServiceState()

    /** Connection attempt in progress. */
    object Connecting : NativeServiceState()

    /** Unix domain socket is open and ready to send/receive. */
    object Connected : NativeServiceState()

    /**
     * Permanent failure after all retries exhausted.
     * @param error Human-readable error message
     */
    data class Failed(val error: String) : NativeServiceState()

    /**
     * Waiting before retry attempt after connection failure.
     * @param attempt Retry attempt number (1-based)
     * @param nextRetryIn Milliseconds until next retry
     */
    data class Reconnecting(val attempt: Int, val nextRetryIn: Long) : NativeServiceState()
}

/**
 * Activation progress reported by the native service's `ACTIVATION_STATUS` event.
 *
 * [status] is service-defined: "pending" while activation is in flight, "activated" (with
 * [handles] populated) on success, "failed" on terminal failure.
 */
data class ActivationStatus(val status: String, val handles: List<String>? = null)
