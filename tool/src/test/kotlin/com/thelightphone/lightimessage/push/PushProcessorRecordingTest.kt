package com.thelightphone.lightimessage.push

import com.thelightphone.lightimessage.data.dao.MessageDao
import com.thelightphone.lightimessage.data.database.ImessageDatabase
import com.thelightphone.lightimessage.data.entity.IncomingPushEntity
import com.thelightphone.lightimessage.data.repository.IPushProcessingRepository
import com.thelightphone.lightimessage.domain.codec.IMessageCodec
import com.thelightphone.lightimessage.domain.push.PushMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PushProcessorRecordingTest {
    @Test
    fun `missing codec keys records a failure reason`() = runTest {
        val messageDao = mock<MessageDao>()
        whenever(messageDao.existsById("push-1")).thenReturn(false)
        val database = mock<ImessageDatabase>()
        whenever(database.messageDao()).thenReturn(messageDao)
        val repository = RecordingRepository()
        val processor =
                PushProcessor(
                        database = database,
                        messageCodec = mock<IMessageCodec>(),
                        codecKeysProvider = { null },
                        pushRepository = repository,
                )

        assertFalse(
                processor.process(
                        PushMessage("push-1", "tel:+15551234567", 123L, byteArrayOf(1, 2)),
                        distributorInstance = "distributor-1",
                        receivedAt = 456L,
                ),
        )
        assertEquals("push-1", repository.recorded.single().id)
        assertEquals("MESSAGE_DELIVERY", repository.recorded.single().type)
        assertEquals("distributor-1", repository.recorded.single().distributorInstance)
        assertEquals(456L, repository.recorded.single().receivedAt)
        assertEquals("No codec key material available", repository.failed.single().second)
    }

    private class RecordingRepository : IPushProcessingRepository {
        val recorded = mutableListOf<IncomingPushEntity>()
        val failed = mutableListOf<Pair<String, String>>()

        override suspend fun recordPush(push: IncomingPushEntity): Result<Unit> {
            recorded += push
            return Result.success(Unit)
        }

        override suspend fun markProcessed(pushId: String): Result<Unit> = Result.success(Unit)

        override suspend fun markFailed(pushId: String, failureReason: String): Result<Unit> {
            failed += pushId to failureReason
            return Result.success(Unit)
        }

        override suspend fun getUnprocessed(): List<IncomingPushEntity> = recorded
    }
}
