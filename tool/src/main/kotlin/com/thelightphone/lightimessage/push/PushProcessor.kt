package com.thelightphone.lightimessage.push

import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import com.thelightphone.lightimessage.data.database.ImessageDatabase
import com.thelightphone.lightimessage.data.entity.MessageEntity
import com.thelightphone.lightimessage.data.entity.ThreadEntity
import com.thelightphone.lightimessage.domain.auth.AuthManager
import com.thelightphone.lightimessage.domain.codec.IMessageCodec
import com.thelightphone.lightimessage.domain.push.PushMessage
import java.io.IOException
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Key material needed to decrypt an inbound envelope: the sender's X.509 certificate (signature
 * verification) and our own RSA private key (AES key unwrap). Produced once auth provisioning
 * completes; see AppServices.
 */
class CodecKeys(
        val senderCert: X509Certificate,
        val recipientKey: PrivateKey,
)

/**
 * Processes inbound push payloads end-to-end:
 * 1. Parse the JSON payload delivered by the SDK's push channel (UnifiedPush under the hood)
 * 2. Existence-based dedup against the local DB
 * 3. Decrypt the envelope via [IMessageCodec]
 * 4. Persist [MessageEntity] (and upsert the parent [ThreadEntity]) in a single Room transaction
 * 5. Send ACK to the relay (placeholder)
 *
 * Merges what used to be `PushReceiver` (BroadcastReceiver → parsing) and `PushProcessingWorker`
 * (WorkManager → decrypt/persist) in the pre-SDK codebase. The Light SDK delivers pushes straight
 * to the tool's entry point on an IO scope, so no receiver/worker hop is needed — or permitted,
 * since the sandbox blocks `android.content.BroadcastReceiver`.
 *
 * Spec: milestone-2.md § 4.4 (Native Push Notification).
 */
class PushProcessor(
        private val database: ImessageDatabase,
        private val messageCodec: IMessageCodec,
        private val authManager: AuthManager? = null,
        private val codecKeysProvider: () -> CodecKeys?,
) {
    /**
     * Entry point for a raw push payload (bytes as delivered by UnifiedPush).
     *
     * @return true if the payload was handled (persisted or a known duplicate); false on failure.
     */
    suspend fun process(rawPayload: ByteArray): Boolean {
        val pushMessage =
                try {
                    parsePushPayload(String(rawPayload, Charsets.UTF_8))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse push payload: ${e.message}", e)
                    return false
                }
        return process(pushMessage)
    }

    /**
     * Process a parsed push message. Idempotent: duplicate messageIds are dropped.
     */
    suspend fun process(push: PushMessage): Boolean {
        Log.d(TAG, "Processing push: messageId=${push.messageId}, sender=${push.sender}")

        return try {
            // 1. Existence-based dedup — if the message already lives in the DB, drop the push.
            if (database.messageDao().existsById(push.messageId)) {
                Log.d(TAG, "Duplicate message dropped: ${push.messageId}")
                return true
            }

            // 2. Resolve crypto material. Until auth provisioning completes there is no key
            //    material; the old WorkManager pipeline exhausted retries and dropped the push in
            //    this state, which we reproduce (loudly) here.
            val keys = codecKeysProvider()
            if (keys == null) {
                Log.e(
                        TAG,
                        "No codec key material available (auth not provisioned); dropping ${push.messageId}",
                )
                return false
            }

            // 3. Decrypt envelope. decodeEnvelope returns a MessagePayload directly — the body
            //    is a first-class field, so no JSON re-parse is needed.
            val payload =
                    messageCodec
                            .decodeEnvelope(push.envelope, keys.senderCert, keys.recipientKey)
                            .getOrElse { e ->
                                Log.e(TAG, "Failed to decode envelope for ${push.messageId}", e)
                                return false
                            }

            // 4. Derive threadId from sender and device address (own phone number). If the device
            //    address is not yet available from AuthManager (auth not complete), fall back to
            //    sender-only derivation with a warning.
            val deviceAddress = authManager?.getDeviceAddress()
            val threadId =
                    if (deviceAddress != null) {
                        Log.d(TAG, "Deriving threadId with both sender and device address")
                        deriveThreadId(push.sender, deviceAddress)
                    } else {
                        Log.w(
                                TAG,
                                "Device address not available from AuthManager; deriving threadId from sender only",
                        )
                        deriveThreadId(push.sender)
                    }

            // 5. Build the persisted entity.
            val messageEntity =
                    MessageEntity(
                            id = push.messageId,
                            threadId = threadId,
                            sender = push.sender,
                            body = payload.body,
                            timestamp = push.timestamp,
                            type = 0, // TEXT
                            isOutgoing = false,
                            status = STATUS_DELIVERED,
                            attachmentCount = 0,
                            rawEnvelope = push.envelope,
                    )

            // 6. Persist the message and upsert the parent thread in a single transaction so a
            //    partial write can't leave the DB in a state where the message row references a
            //    missing thread row (FK) or the thread preview drifts from the last message.
            database.withTransaction {
                val threadDao = database.threadDao()
                if (threadDao.existsById(threadId)) {
                    threadDao.updateLastMessage(threadId, payload.body, push.timestamp)
                } else {
                    val participantUris =
                            if (deviceAddress != null) {
                                "${push.sender}|$deviceAddress"
                            } else {
                                push.sender
                            }
                    threadDao.insert(
                            ThreadEntity(
                                    id = threadId,
                                    title = push.sender,
                                    lastMessage = payload.body,
                                    lastTimestamp = push.timestamp,
                                    participantUris = participantUris,
                            ),
                    )
                }
                database.messageDao().insert(messageEntity)
            }

            Log.d(TAG, "Persisted message: ${push.messageId}")

            // 7. Send ACK to relay (placeholder for future implementation).
            sendAckToRelay(push.messageId)

            true
        } catch (e: Exception) {
            when (e) {
                is IOException, is TimeoutException -> {
                    // Transient: the caller (entry point) can redeliver; the periodic background
                    // sync also re-requests missed messages from the relay.
                    Log.w(TAG, "Transient error processing push ${push.messageId}", e)
                }
                else -> {
                    // Anything else (SerializationException, NPE, SQLiteConstraintException, …) is
                    // structurally permanent — retrying will just re-hit the same fault.
                    Log.e(TAG, "Permanent error processing push ${push.messageId}", e)
                }
            }
            false
        }
    }

    /**
     * Parse JSON payload into PushMessage data class.
     *
     * Payload format (from rustpush):
     * ```json
     * {
     *   "message_id": "uuid",
     *   "sender": "user@icloud.com",
     *   "timestamp": 1234567890,
     *   "envelope": "base64-encoded-encrypted-bytes"
     * }
     * ```
     */
    private fun parsePushPayload(json: String): PushMessage {
        val dto = Json.decodeFromString(PushPayloadDto.serializer(), json)

        // Decode base64 envelope. Pin to NO_WRAP to match the rustpush emitter — DEFAULT would
        // silently accept newline-wrapped input and could mask corruption.
        val envelopeBytes =
                Base64.decode(dto.envelope, Base64.NO_WRAP)
                        ?: throw IllegalArgumentException("Invalid base64 envelope")

        return PushMessage(
                messageId = dto.message_id,
                sender = dto.sender,
                timestamp = dto.timestamp,
                envelope = envelopeBytes,
        )
    }

    /**
     * Derive a deterministic threadId from one or more participant URIs.
     *
     * Participants are sorted alphabetically before hashing so the ID is stable regardless of
     * message direction. Accepts 1..N participants — single-participant threads are used until the
     * device address is available via AuthManager.
     */
    private fun deriveThreadId(vararg participants: String): String {
        require(participants.isNotEmpty()) { "deriveThreadId requires at least one participant" }
        val combined = participants.sorted().joinToString("|")
        return UUID.nameUUIDFromBytes(combined.toByteArray()).toString()
    }

    /** Send ACK back to relay (placeholder for future implementation). */
    private fun sendAckToRelay(messageId: String) {
        // TODO: Send ACK via RelayService when protocol is defined
        Log.d(TAG, "ACK sent for: $messageId (placeholder)")
    }

    /** DTO for JSON payload deserialization (matches rustpush format). */
    @Serializable
    private data class PushPayloadDto(
            val message_id: String,
            val sender: String,
            val timestamp: Long,
            val envelope: String, // base64
    )

    companion object {
        private const val TAG = "PushProcessor"

        // Message status constants (from milestone-2.md)
        internal const val STATUS_DRAFT = 0
        internal const val STATUS_ENCRYPTED = 1
        internal const val STATUS_SENT = 2
        internal const val STATUS_DELIVERED = 3
        internal const val STATUS_READ = 4
        internal const val STATUS_FAILED = 5
    }
}
