package com.thelightphone.lightimessage.push

import com.thelightphone.lightimessage.data.datastore.IPushRegistrationRepository
import com.thelightphone.lightimessage.data.datastore.PushRegistration
import com.thelightphone.lightimessage.data.entity.IncomingPushEntity
import com.thelightphone.lightimessage.data.repository.IPushProcessingRepository
import com.thelightphone.lightimessage.domain.codec.IMessageCodec
import com.thelightphone.lightimessage.domain.push.PushMessage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.mock

class PushRegistrationValidationTest {
    @Test
    fun `matching token is accepted before recording`() = runTest {
        val recording = RecordingRepository()
        val processor = processor(recording, RegistrationRepository(registration()))

        assertTrue(processor.process(push(token = "token-1"), distributorInstance = "remote"))
        assertTrue(recording.recorded.isNotEmpty())
    }

    @Test
    fun `mismatched token is rejected before recording`() = runTest {
        val recording = RecordingRepository()
        val processor = processor(recording, RegistrationRepository(registration()))

        assertFalse(processor.process(push(token = "wrong"), distributorInstance = "remote"))
        assertTrue(recording.recorded.isEmpty())
    }

    @Test
    fun `missing registration is rejected before recording`() = runTest {
        val recording = RecordingRepository()
        val processor = processor(recording, RegistrationRepository(null))

        assertFalse(processor.process(push(token = "token-1"), distributorInstance = "remote"))
        assertTrue(recording.recorded.isEmpty())
    }

    private fun processor(
            recording: RecordingRepository,
            registrations: IPushRegistrationRepository
    ) =
            PushProcessor(
                    database = mock(),
                    messageCodec = mock<IMessageCodec>(),
                    codecKeysProvider = { null },
                    pushRepository = recording,
                    pushRegistrationRepository = registrations,
            )

    private fun push(token: String) =
            PushMessage("push-1", "alice@example.com", 123L, byteArrayOf(1), "WAKE", token)

    private fun registration() =
            PushRegistration(
                    "id",
                    "org.example.distributor",
                    "https://push/remote",
                    "remote",
                    "token-1",
                    123L
            )

    private class RegistrationRepository(private val value: PushRegistration?) :
            IPushRegistrationRepository {
        override suspend fun saveRegistration(registration: PushRegistration) = Result.success(Unit)
        override suspend fun getRegistration(instance: String) = value
        override suspend fun deleteRegistration(instance: String) = Result.success(Unit)
    }

    private class RecordingRepository : IPushProcessingRepository {
        val recorded = mutableListOf<IncomingPushEntity>()
        override suspend fun recordPush(push: IncomingPushEntity): Result<Unit> {
            recorded += push
            return Result.success(Unit)
        }
        override suspend fun markProcessed(pushId: String) = Result.success(Unit)
        override suspend fun markFailed(pushId: String, failureReason: String) =
                Result.success(Unit)
        override suspend fun getUnprocessed(): List<IncomingPushEntity> = recorded
    }
}
