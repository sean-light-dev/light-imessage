package com.thelightphone.lightimessage.domain.native

import com.thelightphone.lightimessage.domain.push.PushMessage

/** Unsolicited events delivered by the trusted native-service IPC channel. */
sealed interface NativeEvent {
    /** An inbound encrypted message to decrypt and persist through the normal message pipeline. */
    data class MessageReceived(val message: PushMessage) : NativeEvent

    /** A delivery receipt for an outgoing message. */
    data class DeliveryReceipt(
            val messageId: String,
            val deliveryReceiptAt: Long,
    ) : NativeEvent
}
