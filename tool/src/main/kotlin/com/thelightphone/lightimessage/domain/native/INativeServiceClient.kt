package com.thelightphone.lightimessage.domain.native

import kotlinx.coroutines.flow.StateFlow

/**
 * Client interface for communicating with the rustpush native service via Unix domain socket IPC.
 *
 * Wire contract: `native-service/src/protocol.rs` — length-prefixed JSON frames (4-byte
 * big-endian length + UTF-8 JSON) carrying serde internally-tagged messages (`"type"` in
 * SCREAMING_SNAKE_CASE). Commands: `PING`, `ACTIVATE`, `SEND_MESSAGE`, `GET_MESSAGES`. Events:
 * `PONG`, `ACTIVATION_STATUS`, `ACK`, `ERROR`. The service answers every command with exactly one
 * event, in order — there are no correlation IDs; requests are serialized through a single
 * request lock.
 *
 * Spec: milestone-3.md § 4.1–4.5 (deployment, activation, send, receive, heartbeat).
 */
interface INativeServiceClient {
    /**
     * Current connection state as a StateFlow for reactive UI updates. Initial value is
     * Disconnected.
     */
    val connectionState: StateFlow<NativeServiceState>

    /**
     * Establish the socket connection to the native service (abstract namespace, name
     * `rustpush_ipc`). Emits state = Connecting immediately, then Connected on socket open. On
     * failure, retries with exponential backoff (1s, 2s, 4s, 8s, 16s, 32s cap).
     *
     * @return Result.success if socket opens; Result.failure if all retries exhausted
     */
    suspend fun connect(): Result<Unit>

    /**
     * Close socket connection cleanly. Emits state = Disconnected. Cancels any pending reconnect
     * timers, the keepalive heartbeat, and any in-flight command.
     *
     * @return Result.success
     */
    suspend fun disconnect(): Result<Unit>

    /**
     * Drive one-time Apple ID activation in the native service (`ACTIVATE`). [twoFaCode] is sent
     * on the second round trip once the user has supplied the 2FA challenge.
     *
     * @return Result.success with the latest [ActivationStatus]; Result.failure on timeout,
     * transport error, or an `ERROR` event from the service
     */
    suspend fun activate(
            appleId: String,
            password: String,
            twoFaCode: String? = null,
    ): Result<ActivationStatus>

    /**
     * Send an outgoing iMessage via the native service (`SEND_MESSAGE`).
     *
     * @return Result.success with the acknowledged messageId; Result.failure on timeout,
     * transport error, or an `ERROR` event from the service
     */
    suspend fun sendMessage(
            messageId: String,
            recipients: List<String>,
            text: String,
            attachments: List<String> = emptyList(),
    ): Result<String>

    /**
     * Ask the native service for messages received since [sinceEpochMs] (`GET_MESSAGES`). The
     * success-path response shape is a Rust-side TODO (the service currently answers `ERROR`);
     * today this only proves out the transport.
     *
     * @return Result.success if the service accepted the command; Result.failure otherwise
     */
    suspend fun getMessages(sinceEpochMs: Long): Result<Unit>
}
