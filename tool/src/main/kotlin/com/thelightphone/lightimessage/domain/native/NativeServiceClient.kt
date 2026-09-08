package com.thelightphone.lightimessage.domain.native

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Unix domain socket IPC client for the rustpush native service.
 *
 * Wire contract (must match `native-service/src/protocol.rs`):
 * - Transport: abstract-namespace AF_UNIX socket `rustpush_ipc`.
 * - Framing: 4-byte big-endian length header + UTF-8 JSON payload, max frame 16 MiB.
 * - Messages: serde internally-tagged JSON (`"type"` in SCREAMING_SNAKE_CASE). The service
 *   answers every command with exactly one event, in order, so commands are serialized through
 *   a single request lock — there are no correlation IDs on the wire.
 * - Heartbeat: `PING` every 30s; a missing `PONG` within 5s triggers reconnect with exponential
 *   backoff (1s, 2s, 4s, 8s, 16s, 32s cap; 5 attempts before terminal `Failed`).
 *
 * Spec: milestone-3.md § 4.1–4.5, § 5.1, § 6.2–6.5.
 */
class NativeServiceClient(
        private val scope: CoroutineScope,
        private val socketName: String = DEFAULT_SOCKET_NAME,
) : INativeServiceClient {

    private val _connectionState =
            MutableStateFlow<NativeServiceState>(NativeServiceState.Disconnected)
    override val connectionState: StateFlow<NativeServiceState> = _connectionState

    private var ipcSocket: LocalSocket? = null
    private val socketMutex = Mutex() // guards ipcSocket ref (assign/close)

    /** Serializes commands: the service answers one event per command, in order. */
    private val requestMutex = Mutex()

    /** Awaits the response to the in-flight command (at most one, under [requestMutex]). */
    @Volatile private var pendingResponse: CompletableDeferred<IpcEvent>? = null

    private val reconnectPolicy: ReconnectPolicy =
            ReconnectPolicy(maxAttempts = 5, baseDelayMs = 1000)

    private val reconnectAttempt = AtomicInteger(0)
    private var keepaliveJob: Job? = null
    private var readLoopJob: Job? = null
    private var reconnectJob: Job? = null

    private val json = ipcJson

    private companion object {
        private const val TAG = "NativeServiceClient"

        /** Abstract-namespace socket name; must match `socket.rs`'s `DEFAULT_SOCKET_NAME`. */
        private const val DEFAULT_SOCKET_NAME = "rustpush_ipc"

        /** Matches `protocol.rs`'s `MAX_FRAME_LEN`. */
        private const val MAX_FRAME_SIZE = 16 * 1024 * 1024
        private const val IPC_TIMEOUT_MS = 10_000L // 10 seconds
        private const val HEARTBEAT_INTERVAL_MS = 30_000L // 30 seconds
        private const val PONG_TIMEOUT_MS = 5_000L // 5 seconds
    }

    override suspend fun connect(): Result<Unit> {
        _connectionState.emit(NativeServiceState.Connecting)
        reconnectAttempt.set(0)
        return try {
            performConnect()
            Result.success(Unit)
        } catch (e: Exception) {
            // Surface failure to caller; schedule reconnect in background.
            reconnectJob?.cancel()
            reconnectJob = scope.launch { onSocketFailure(e) }
            Result.failure(e)
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        return try {
            reconnectJob?.cancel()
            reconnectJob = null
            keepaliveJob?.cancel()
            keepaliveJob = null
            readLoopJob?.cancel()
            readLoopJob = null
            pendingResponse?.cancel()
            pendingResponse = null

            socketMutex.withLock {
                ipcSocket?.close()
                ipcSocket = null
            }

            _connectionState.emit(NativeServiceState.Disconnected)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun activate(
            appleId: String,
            password: String,
            twoFaCode: String?,
    ): Result<ActivationStatus> {
        return try {
            when (
                    val event =
                            sendCommand(
                                    IpcCommand.Activate(appleId, password, twoFaCode),
                                    IPC_TIMEOUT_MS,
                            )
            ) {
                is IpcEvent.ActivationStatusEvent ->
                        Result.success(ActivationStatus(event.status, event.handles))
                is IpcEvent.Error ->
                        Result.failure(IOException("Native service error: ${event.message}"))
                else -> Result.failure(IOException("Unexpected response to ACTIVATE"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendMessage(
            messageId: String,
            recipients: List<String>,
            text: String,
            attachments: List<String>,
    ): Result<String> {
        return try {
            when (
                    val event =
                            sendCommand(
                                    IpcCommand.SendMessage(
                                            messageId,
                                            recipients,
                                            text,
                                            attachments,
                                    ),
                                    IPC_TIMEOUT_MS,
                            )
            ) {
                is IpcEvent.Ack -> Result.success(event.messageId)
                is IpcEvent.Error ->
                        Result.failure(IOException("Native service error: ${event.message}"))
                else -> Result.failure(IOException("Unexpected response to SEND_MESSAGE"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getMessages(sinceEpochMs: Long): Result<Unit> {
        return try {
            when (
                    val event =
                            sendCommand(IpcCommand.GetMessages(sinceEpochMs), IPC_TIMEOUT_MS)
            ) {
                is IpcEvent.Error ->
                        Result.failure(IOException("Native service error: ${event.message}"))
                // The success response shape is a Rust-side TODO (protocol.rs has no message
                // event variant yet); any non-error event means the command was accepted.
                else -> Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Perform Unix domain socket connection. Throws on failure so the caller can decide whether to
     * surface the error or schedule a reconnect.
     */
    private suspend fun performConnect() {
        if (!reconnectPolicy.shouldRetry(reconnectAttempt.get())) {
            val error = "Max reconnect attempts (${reconnectPolicy.maxAttempts}) exhausted"
            _connectionState.emit(NativeServiceState.Failed(error))
            throw IOException(error)
        }

        val socket = LocalSocket()
        val address = LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)

        socket.connect(address) // throws on failure
        socketMutex.withLock { ipcSocket = socket }

        // Start the read loop BEFORE emitting Connected so callers cannot race a
        // send-before-reader-is-up window and lose the response.
        val readLoopReady = CompletableDeferred<Unit>()
        startReadLoop(readLoopReady)
        readLoopReady.await()

        _connectionState.emit(NativeServiceState.Connected)
        reconnectAttempt.set(0)

        startHeartbeat()
    }

    /** Called when socket connection fails. Triggers reconnection with backoff. */
    private suspend fun onSocketFailure(t: Throwable) {
        keepaliveJob?.cancel()
        keepaliveJob = null
        readLoopJob?.cancel()
        readLoopJob = null
        pendingResponse?.cancel()
        pendingResponse = null

        socketMutex.withLock {
            ipcSocket?.close()
            ipcSocket = null
        }

        val currentAttempt = reconnectAttempt.get()
        if (reconnectPolicy.shouldRetry(currentAttempt)) {
            val delayMs = reconnectPolicy.getDelayMs(currentAttempt)
            val nextAttempt = reconnectAttempt.incrementAndGet()

            _connectionState.emit(
                    NativeServiceState.Reconnecting(attempt = nextAttempt, nextRetryIn = delayMs),
            )

            reconnectJob?.cancel()
            reconnectJob =
                    scope.launch {
                        delay(delayMs)
                        if (_connectionState.value is NativeServiceState.Reconnecting) {
                            try {
                                performConnect()
                            } catch (e: Exception) {
                                onSocketFailure(e)
                            }
                        }
                    }
        } else {
            val error = "Socket failure after ${reconnectPolicy.maxAttempts} attempts: ${t.message}"
            _connectionState.emit(NativeServiceState.Failed(error))
        }
    }

    /**
     * Send a command and wait for its response event (with timeout). The service answers every
     * command with exactly one event in order, so commands are fully serialized through
     * [requestMutex]: write the frame, then hold the lock until the response lands.
     */
    private suspend fun sendCommand(command: IpcCommand, timeoutMs: Long): IpcEvent {
        requestMutex.withLock {
            val deferred = CompletableDeferred<IpcEvent>()
            pendingResponse = deferred
            try {
                writeFrame(json.encodeToString(IpcCommand.serializer(), command))
                // Cooperative suspend — cancellable by the outer timeout.
                return withTimeoutOrNull(timeoutMs) { deferred.await() }
                        ?: throw IOException("IPC timeout")
            } finally {
                pendingResponse = null
            }
        }
    }

    /** Write IPC frame to socket: 4-byte big-endian length + JSON payload. */
    private suspend fun writeFrame(jsonString: String) {
        val socket = ipcSocket ?: throw IOException("Socket not connected")

        val jsonBytes = jsonString.toByteArray(StandardCharsets.UTF_8)
        if (jsonBytes.size > MAX_FRAME_SIZE) {
            throw IOException("Message too large: ${jsonBytes.size} > $MAX_FRAME_SIZE")
        }

        val frame = frameData(jsonBytes)
        // LocalSocket.outputStream is non-null in practice — fail loudly if it isn't.
        socket.outputStream!!.write(frame)
        socket.outputStream!!.flush()
    }

    /**
     * Read IPC frame from socket: 4-byte big-endian length + JSON payload. Called only from the
     * single-consumer read loop, so no synchronization is needed.
     */
    private fun readFrame(): IpcEvent {
        val socket = ipcSocket ?: throw IOException("Socket not connected")
        val input = socket.inputStream ?: throw IOException("No input stream")

        val lenBytes = ByteArray(4)
        var totalRead = 0
        while (totalRead < 4) {
            val n = input.read(lenBytes, totalRead, 4 - totalRead)
            if (n == -1) throw EOFException("socket closed reading frame length")
            totalRead += n
        }

        val length = lenBytes.toInt()
        if (length <= 0 || length > MAX_FRAME_SIZE) {
            throw IOException("Invalid frame length: $length")
        }

        val payload = ByteArray(length)
        var bodyRead = 0
        while (bodyRead < length) {
            val n = input.read(payload, bodyRead, length - bodyRead)
            if (n == -1) throw EOFException("socket closed reading frame body ($bodyRead/$length)")
            bodyRead += n
        }

        val jsonString = String(payload, StandardCharsets.UTF_8)
        return json.decodeFromString(IpcEvent.serializer(), jsonString)
    }

    /** Add 4-byte big-endian length prefix to data. */
    private fun frameData(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size + 4)
        val writer = DataOutputStream(output)
        writer.writeInt(data.size)
        writer.write(data)
        writer.flush()
        return output.toByteArray()
    }

    /** Start keepalive: `PING` every 30s; a `PONG` timeout (5s) triggers reconnect. */
    private fun startHeartbeat() {
        keepaliveJob?.cancel()
        keepaliveJob =
                scope.launch {
                    while (coroutineContext.isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        try {
                            sendCommand(IpcCommand.Ping, PONG_TIMEOUT_MS)
                        } catch (e: Exception) {
                            Log.e(TAG, "Keepalive ping failed: ${e.message}")
                            onSocketFailure(e)
                            break
                        }
                    }
                }
    }

    /**
     * Start read loop to receive incoming events from the socket. Completes [ready] as soon as the
     * loop coroutine is running so callers can be sure the reader is up before emitting Connected.
     */
    private fun startReadLoop(ready: CompletableDeferred<Unit>) {
        readLoopJob?.cancel()
        readLoopJob =
                scope.launch {
                    ready.complete(Unit)
                    try {
                        while (coroutineContext.isActive) {
                            val event = readFrame()
                            // Route to the in-flight command. An event with no waiter is late
                            // (arrived after a timeout) — log and drop. Log the type only: event
                            // payloads may carry message content.
                            val delivered = pendingResponse?.complete(event) == true
                            if (!delivered) {
                                Log.w(TAG, "Dropping late or unsolicited IPC event")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Read loop failed: ${e.message}")
                        onSocketFailure(e)
                    }
                }
    }
}

/**
 * JSON codec for the IPC wire format. Shared by [NativeServiceClient] and the contract tests in
 * `IpcProtocolTest` so the wire format is tested exactly as used.
 */
internal val ipcJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    // serde_json always emits struct fields (e.g. `"2fa_code": null`); encode defaults so the
    // Kotlin frames match the Rust encoder byte-for-byte.
    encodeDefaults = true
}

/** Commands sent to the native service. Wire format must match `protocol.rs`'s `Command`. */
@Serializable
internal sealed class IpcCommand {
    /** Heartbeat probe. Expects [IpcEvent.Pong]. */
    @Serializable @SerialName("PING") data object Ping : IpcCommand()

    /** One-time Apple ID activation. [twoFaCode] is present on the 2FA round trip. */
    @Serializable
    @SerialName("ACTIVATE")
    data class Activate(
            @SerialName("apple_id") val appleId: String,
            val password: String,
            @SerialName("2fa_code") val twoFaCode: String? = null,
    ) : IpcCommand()

    /** Send an outgoing iMessage. */
    @Serializable
    @SerialName("SEND_MESSAGE")
    data class SendMessage(
            @SerialName("message_id") val messageId: String,
            val recipients: List<String>,
            val text: String,
            val attachments: List<String> = emptyList(),
    ) : IpcCommand()

    /** Fetch messages received since [since] (Unix epoch milliseconds). */
    @Serializable @SerialName("GET_MESSAGES") data class GetMessages(val since: Long) : IpcCommand()
}

/** Events received from the native service. Wire format must match `protocol.rs`'s `Event`. */
@Serializable
internal sealed class IpcEvent {
    /** Heartbeat reply to [IpcCommand.Ping]. */
    @Serializable @SerialName("PONG") data object Pong : IpcEvent()

    /** Progress of an in-flight activation. */
    @Serializable
    @SerialName("ACTIVATION_STATUS")
    data class ActivationStatusEvent(
            val status: String,
            val handles: List<String>? = null,
    ) : IpcEvent()

    /** An outgoing message was accepted for delivery. */
    @Serializable
    @SerialName("ACK")
    data class Ack(@SerialName("message_id") val messageId: String) : IpcEvent()

    /** A command failed or is not yet supported. */
    @Serializable @SerialName("ERROR") data class Error(val message: String) : IpcEvent()
}

/** Backoff policy for socket reconnection attempts (reuses RelayService pattern). */
data class ReconnectPolicy(
        val maxAttempts: Int = 5,
        val baseDelayMs: Long = 1000, // 1s initial backoff
        val maxDelayMs: Long = 32000, // 32s cap
) {
    /**
     * Compute delay in milliseconds for a given retry attempt. Uses formula: baseDelayMs * (2 ^
     * attempt), capped at maxDelayMs.
     */
    fun getDelayMs(attempt: Int): Long {
        val exponent = attempt.coerceAtMost(5)
        val delay = baseDelayMs * (1L shl exponent)
        return delay.coerceAtMost(maxDelayMs)
    }

    /** Check if retry is allowed for a given attempt. */
    fun shouldRetry(attempt: Int): Boolean = attempt < maxAttempts
}

/** Convert 4-byte array to big-endian Int. */
private fun ByteArray.toInt(): Int {
    return ((this[0].toInt() and 0xFF) shl 24) or
            ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or
            (this[3].toInt() and 0xFF)
}
