package com.thelightphone.lightimessage.sync

import android.util.Log
import com.thelightphone.lightimessage.data.repository.IMessageRepository
import com.thelightphone.lightimessage.di.AppServices
import com.thelightphone.lightimessage.domain.relay.IRelayService
import com.thelightphone.lightimessage.domain.relay.MessageId
import com.thelightphone.lightimessage.domain.relay.OutgoingMessage
import com.thelightphone.lightimessage.domain.relay.RelayConnectionState
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import kotlinx.coroutines.flow.firstOrNull

const val BACKGROUND_SYNC_JOB_KEY = "background-sync"

/**
 * Periodic background sync job. Runs (by default every 15 minutes, the WorkManager minimum the
 * SDK forwards to) to:
 * 1. Verify relay connection health
 * 2. Retry undelivered messages
 * 3. Request sync from relay (triggers the relay to push pending messages)
 *
 * Migrated from the pre-SDK `BackgroundSyncWorker` (a `CoroutineWorker` with DI via a custom
 * `WorkerFactory`) to the SDK's `@LightJob` model: the handler is a top-level val, so
 * dependencies come from the [AppServices] locator built from the supplied `SealedLightContext`.
 *
 * Spec: milestone-2.md § TASK_011 (Background Sync Worker); ADR-008 (WorkManager — the SDK wraps
 * WorkManager in LightWork, which is what schedules this job).
 */
@LightJob(BACKGROUND_SYNC_JOB_KEY)
val backgroundSync: LightJobHandler = handler@{ lightContext, _ ->
    val services = AppServices.get(lightContext)
    val relayService = services.relayService
    val messageRepository = services.messageRepository

    try {
        performSync(relayService, messageRepository)
    } catch (e: Exception) {
        // Unexpected errors: retry transient (network/IO) failures, fail permanent ones.
        if (isTransient(e)) {
            Log.w(TAG, "Transient error during sync, retrying", e)
            LightJobResult.Retry
        } else {
            Log.e(TAG, "Fatal error during sync", e)
            LightJobResult.Error(mapOf(KEY_ERROR to (e.message ?: "unknown")))
        }
    }
}

private const val TAG = "BackgroundSyncJob"
private const val KEY_ERROR = "error"

private suspend fun performSync(
        relayService: IRelayService,
        messageRepository: IMessageRepository,
): LightJobResult {
    // 1. Check relay connection state and reconnect if needed
    if (relayService.connectionState.value !is RelayConnectionState.Connected) {
        Log.w(TAG, "Relay not connected, checking connection state...")
        val connectResult = attemptRelayConnection(relayService)
        if (connectResult !is LightJobResult.Success) {
            Log.w(TAG, "Failed to establish relay connection, retrying later")
            return connectResult
        }
    }
    Log.i(TAG, "Relay connection state: ${relayService.connectionState.value}")

    // 2. Retry undelivered messages
    val undeliveredResult = retryUndeliveredMessages(relayService, messageRepository)
    if (undeliveredResult !is LightJobResult.Success) {
        Log.w(TAG, "Error during undelivered message retry")
        return undeliveredResult
    }

    // 3. Request sync from relay (triggers relay to push pending messages)
    val syncResult = relayService.requestSync()
    if (syncResult.isFailure) {
        Log.w(TAG, "Failed to request sync from relay, retrying later", syncResult.exceptionOrNull())
        return LightJobResult.Retry
    }
    Log.i(TAG, "Requested sync from relay")

    Log.i(TAG, "Sync completed successfully")
    return LightJobResult.Success()
}

/**
 * Retry undelivered messages by querying the message repository and resending each message via
 * the relay service. Errors on individual messages are logged but do not abort the sync.
 */
private suspend fun retryUndeliveredMessages(
        relayService: IRelayService,
        messageRepository: IMessageRepository,
): LightJobResult {
    return try {
        // firstOrNull() guards against a flow that never emits (safer than first()).
        val undeliveredMessages =
                messageRepository.getUndeliveredMessages().firstOrNull() ?: emptyList()
        Log.i(TAG, "Found ${undeliveredMessages.size} undelivered messages")

        for (message in undeliveredMessages) {
            val envelope = message.rawEnvelope
            if (envelope == null) {
                Log.w(TAG, "Skipping message ${message.id}: no raw envelope")
                continue
            }

            try {
                val outgoing =
                        OutgoingMessage(
                                recipient = message.sender,
                                payload = envelope,
                                messageId = MessageId(message.id),
                        )
                val sendResult = relayService.sendMessage(outgoing)
                if (sendResult.isSuccess) {
                    // Mark message as delivered
                    val markResult =
                            messageRepository.markAsDelivered(
                                    message.id,
                                    System.currentTimeMillis(),
                            )
                    if (markResult.isSuccess) {
                        Log.i(TAG, "Message ${message.id} resent and marked as delivered")
                    } else {
                        Log.w(
                                TAG,
                                "Message ${message.id} sent but failed to update status",
                                markResult.exceptionOrNull(),
                        )
                    }
                } else {
                    // Log error but continue with next message (transient error on this
                    // specific message)
                    Log.w(TAG, "Failed to send message ${message.id}", sendResult.exceptionOrNull())
                }
            } catch (e: Exception) {
                // Log error but continue with next message
                Log.w(TAG, "Exception sending message ${message.id}", e)
            }
        }
        LightJobResult.Success()
    } catch (e: Exception) {
        // Distinguish between transient (network/IO) and fatal errors by type.
        if (isTransient(e)) {
            Log.w(TAG, "Transient error while retrieving undelivered messages", e)
            LightJobResult.Retry
        } else {
            Log.e(TAG, "Fatal error while retrieving undelivered messages", e)
            LightJobResult.Error(mapOf(KEY_ERROR to (e.message ?: "unknown")))
        }
    }
}

/**
 * Health-check the relay connection. The [IRelayService.connect] method requires a
 * [com.thelightphone.lightimessage.domain.relay.RelayEndpoint] which this job does not have
 * access to; reconnection with backoff is already handled internally by [IRelayService] on
 * failure. Here we simply observe the current state and translate it to a job result.
 */
private fun attemptRelayConnection(relayService: IRelayService): LightJobResult {
    return try {
        when (relayService.connectionState.value) {
            is RelayConnectionState.Connected -> LightJobResult.Success()
            else -> LightJobResult.Retry
        }
    } catch (e: Exception) {
        Log.w(TAG, "Error checking relay connection", e)
        LightJobResult.Retry
    }
}

/**
 * Classify a throwable as transient (retryable) vs permanent. Uses type checks rather than
 * fragile message-substring matching so localized exception messages don't break classification.
 */
private fun isTransient(e: Throwable): Boolean =
        when (e) {
            is java.net.SocketTimeoutException,
            is java.net.UnknownHostException,
            is java.io.IOException,
            is java.util.concurrent.TimeoutException,
                -> true

            else -> false
        }
