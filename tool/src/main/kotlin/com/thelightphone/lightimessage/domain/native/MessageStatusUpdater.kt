package com.thelightphone.lightimessage.domain.native

import com.thelightphone.lightimessage.data.dao.MessageDao

/** Applies delivery receipts from the native IPC channel to matching outgoing messages. */
class MessageStatusUpdater(private val messageDao: MessageDao) {
    suspend fun update(receipt: NativeEvent.DeliveryReceipt) {
        messageDao.markDelivered(receipt.messageId, receipt.deliveryReceiptAt)
    }
}
