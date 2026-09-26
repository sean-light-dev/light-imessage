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
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
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

    @Test
    fun `WAKE schedules background sync and marks push processed`() = runTest {
        val repository = RecordingRepository()
        var syncRequested = false
        val processor = processor(repository, backgroundSync = { syncRequested = true })

        assertTrue(processor.process(push("wake-1", "WAKE")))

        assertTrue(syncRequested)
        assertEquals(emptyList(), repository.failed)
        assertEquals("WAKE", repository.recorded.single().type)
    }

    @Test
    fun `READ_RECEIPT marks matching message read with receipt timestamp`() = runTest {
        val messageDao = mock<MessageDao>()
        val database = mock<ImessageDatabase>()
        whenever(database.messageDao()).thenReturn(messageDao)
        val repository = RecordingRepository()
        val processor = processor(repository, database = database)

        assertTrue(processor.process(push("message-1", "READ_RECEIPT", timestamp = 789L)))

        verify(messageDao).markRead("message-1", 789L)
        assertTrue(repository.failed.isEmpty())
    }

    @Test
    fun `TYPING publishes indicator for the sender thread and marks push processed`() = runTest {
        val repository = RecordingRepository()
        var thread: String? = null
        var sender: String? = null
        val processor =
                processor(
                        repository,
                        typingIndicator = { routedThread, routedSender ->
                            thread = routedThread
                            sender = routedSender
                        },
                )

        assertTrue(processor.process(push("typing-1", "TYPING", sender = "alice@example.com")))

        assertEquals("alice@example.com", sender)
        assertTrue(thread != null)
        assertTrue(repository.failed.isEmpty())
    }

    @Test
    fun `unknown type is recorded as failed without touching message processing`() = runTest {
        val messageDao = mock<MessageDao>()
        val database = mock<ImessageDatabase>()
        whenever(database.messageDao()).thenReturn(messageDao)
        val repository = RecordingRepository()
        val processor = processor(repository, database = database)

        assertFalse(processor.process(push("unknown-1", "FUTURE_TYPE")))

        assertEquals("Unknown push type: FUTURE_TYPE", repository.failed.single().second)
        verify(messageDao, org.mockito.kotlin.never()).existsById(any())
    }

    @Test
    fun `absent JSON type is recorded as failed without touching message processing`() = runTest {
        val messageDao = mock<MessageDao>()
        val database = mock<ImessageDatabase>()
        whenever(database.messageDao()).thenReturn(messageDao)
        val repository = RecordingRepository()
        val processor = processor(repository, database = database)
        val raw =
                """{"message_id":"absent-1","sender":"alice@example.com","timestamp":123,"envelope":"AQI="}""".toByteArray()

        assertFalse(processor.process(raw))

        assertEquals("Missing push type", repository.failed.single().second)
        assertEquals("UNKNOWN", repository.recorded.single().type)
        verify(messageDao, org.mockito.kotlin.never()).existsById(any())
    }

    private fun processor(
            repository: RecordingRepository,
            database: ImessageDatabase = mock(),
            backgroundSync: suspend () -> Unit = {},
            typingIndicator: suspend (String, String) -> Unit = { _, _ -> },
    ) =
            PushProcessor(
                    database = database,
                    messageCodec = mock<IMessageCodec>(),
                    codecKeysProvider = { null },
                    pushRepository = repository,
                    backgroundSync = backgroundSync,
                    typingIndicator = typingIndicator,
            )

    private fun push(
            id: String,
            type: String,
            sender: String = "alice@example.com",
            timestamp: Long = 123L,
    ) = PushMessage(id, sender, timestamp, byteArrayOf(1, 2), type)

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
