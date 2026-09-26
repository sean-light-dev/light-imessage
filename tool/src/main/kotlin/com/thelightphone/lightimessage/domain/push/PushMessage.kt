package com.thelightphone.lightimessage.domain.push

/**
 * Data class representing a parsed UnifiedPush notification payload.
 *
 * Push payloads are JSON-formatted and delivered by the rustpush service. Spec: milestone-2.md §
 * 4.4 (Native Push Notification)
 *
 * @param messageId UUID-formatted message identifier (for deduplication)
 * @param sender iMessage address (tel: or mailto:) of the sender
 * @param timestamp Unix epoch milliseconds (UTC)
 * @param envelope Base64-decoded encrypted message envelope (AES-GCM encrypted)
 */
data class PushMessage(
        val messageId: String,
        val sender: String,
        val timestamp: Long,
        val envelope: ByteArray,
        /**
         * Null is retained for JSON payloads that omit type; direct legacy callers default to
         * delivery.
         */
        val type: String? = "MESSAGE_DELIVERY",
        /** Token supplied by the distributor/bridge for registration validation. */
        val distributorToken: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PushMessage) return false
        if (messageId != other.messageId) return false
        if (sender != other.sender) return false
        if (timestamp != other.timestamp) return false
        if (!envelope.contentEquals(other.envelope)) return false
        if (type != other.type) return false
        if (distributorToken != other.distributorToken) return false
        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + sender.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + envelope.contentHashCode()
        result = 31 * result + (type?.hashCode() ?: 0)
        result = 31 * result + (distributorToken?.hashCode() ?: 0)
        return result
    }
}
