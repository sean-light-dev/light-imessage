package com.thelightphone.lightimessage.data.repository

import com.thelightphone.lightimessage.data.dao.PushDao
import com.thelightphone.lightimessage.data.entity.IncomingPushEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PushProcessingRepositoryTest {
    @Test
    fun `duplicate recording is idempotent`() = runTest {
        val dao = InMemoryPushDao()
        val repository = PushProcessingRepository(dao)
        val push = push("push-1")

        assertTrue(repository.recordPush(push).isSuccess)
        assertTrue(repository.recordPush(push.copy(payloadBase64 = "different")).isSuccess)

        assertEquals(listOf(push), repository.getUnprocessed())
    }

    @Test
    fun `unprocessed query excludes processed rows`() = runTest {
        val dao = InMemoryPushDao()
        val repository = PushProcessingRepository(dao)
        repository.recordPush(push("pending"))
        repository.recordPush(push("done"))

        repository.markProcessed("done")

        assertEquals(listOf("pending"), repository.getUnprocessed().map { it.id })
    }

    @Test
    fun `marking a missing receipt fails`() = runTest {
        val repository = PushProcessingRepository(InMemoryPushDao())

        assertTrue(repository.markProcessed("missing").isFailure)
        assertTrue(repository.markFailed("missing", "failure").isFailure)
    }

    @Test
    fun `failure reason is retained`() = runTest {
        val dao = InMemoryPushDao()
        val repository = PushProcessingRepository(dao)
        repository.recordPush(push("failed"))

        repository.markFailed("failed", "decrypt failed")

        assertEquals("decrypt failed", dao.rows.single().failureReason)
        assertEquals(listOf("failed"), repository.getUnprocessed().map { it.id })
    }

    private fun push(id: String) =
            IncomingPushEntity(
                    id = id,
                    type = "MESSAGE_DELIVERY",
                    payloadBase64 = "payload",
                    distributorInstance = "test",
                    receivedAt = 1L,
            )

    private class InMemoryPushDao : PushDao {
        val rows = mutableListOf<IncomingPushEntity>()

        override suspend fun insert(push: IncomingPushEntity): Long {
            if (rows.any { it.id == push.id }) return -1L
            rows += push
            return 1L
        }

        override suspend fun getUnprocessed(): List<IncomingPushEntity> =
                rows.filter { !it.processed }.sortedBy { it.receivedAt }

        override suspend fun markProcessed(id: String): Int {
            val index = rows.indexOfFirst { it.id == id }
            if (index < 0) return 0
            rows[index] = rows[index].copy(processed = true, failureReason = null)
            return 1
        }

        override suspend fun markFailed(id: String, failureReason: String): Int {
            val index = rows.indexOfFirst { it.id == id }
            if (index < 0) return 0
            rows[index] = rows[index].copy(processed = false, failureReason = failureReason)
            return 1
        }
    }
}
