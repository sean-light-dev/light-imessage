package com.thelightphone.lightimessage.domain.native

import com.thelightphone.lightimessage.data.dao.MessageDao
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class MessageStatusUpdaterTest {
    @Test
    fun deliveryReceiptUpdatesMatchingMessageWithReceiptTimestamp() = runTest {
        val messageDao = mock<MessageDao>()
        val updater = MessageStatusUpdater(messageDao)

        updater.update(NativeEvent.DeliveryReceipt("outgoing-1", 456L))

        verify(messageDao).markDelivered("outgoing-1", 456L)
    }
}
