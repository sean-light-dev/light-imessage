package com.thelightphone.lightimessage.domain.native

import com.thelightphone.lightimessage.data.dao.MessageDao
import com.thelightphone.lightimessage.data.dao.ThreadDao
import com.thelightphone.lightimessage.data.database.ImessageDatabase
import com.thelightphone.lightimessage.data.entity.IncomingPushEntity
import com.thelightphone.lightimessage.data.entity.MessageEntity
import com.thelightphone.lightimessage.data.entity.ThreadEntity
import com.thelightphone.lightimessage.data.repository.IPushProcessingRepository
import com.thelightphone.lightimessage.domain.codec.MessageCodec
import com.thelightphone.lightimessage.domain.codec.MessagePayload
import com.thelightphone.lightimessage.domain.codec.PlistCodec
import com.thelightphone.lightimessage.domain.crypto.CryptoEngine
import com.thelightphone.lightimessage.push.CodecKeys
import com.thelightphone.lightimessage.push.PushProcessor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NativeServiceClientEventTest {
    @Test
    fun unsolicitedEventsAreDemultiplexedWhileCommandAwaitsAck() = runBlocking {
        val socket = FakeIpcSocket()
        val client = clientFor(socket)
        val messageDao = mock<MessageDao>()
        val statusUpdater = MessageStatusUpdater(messageDao)
        try {
            assertTrue(client.connect().isSuccess)
            val send = async { client.sendMessage("m1", listOf("tel:+15551234567"), "hello") }
            assertEquals(
                    """{"type":"SEND_MESSAGE","message_id":"m1","recipients":["tel:+15551234567"],"text":"hello","attachments":[]}""",
                    socket.awaitCommand(),
            )

            socket.sendEvent(
                    """{"type":"MESSAGE_RECEIVED","message_id":"m2","sender":"alice@example.com","timestamp":123,"envelope":"AQI="}""",
            )
            socket.sendEvent(
                    """{"type":"DELIVERY_RECEIPT","message_id":"m1","delivery_receipt_at":456}""",
            )
            socket.sendEvent("""{"type":"ACK","message_id":"m1"}""")

            assertEquals("m1", send.await().getOrThrow())
            val events = withTimeout(2_000) { client.observeEvents().take(2).toList() }
            assertEquals(
                    listOf(
                            NativeEvent.MessageReceived(
                                    com.thelightphone.lightimessage.domain.push.PushMessage(
                                            "m2",
                                            "alice@example.com",
                                            123,
                                            byteArrayOf(1, 2),
                                    ),
                            ),
                            NativeEvent.DeliveryReceipt("m1", 456),
                    ),
                    events,
            )
            statusUpdater.update(events[1] as NativeEvent.DeliveryReceipt)
            verify(messageDao).markDelivered("m1", 456)
        } finally {
            client.disconnect()
            socket.close()
        }
    }

    @Test
    fun nativeMessageEventDecryptsAndPersistsThroughPushProcessor() = runBlocking {
        val crypto = CryptoEngine()
        val codec = MessageCodec(PlistCodec(), crypto)
        val (recipientPublicKey, recipientPrivateKey) = crypto.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = crypto.generateEcdsaKeyPair()
        val senderCertificate = createCertificate(senderPublicKey, senderPrivateKey)
        val envelope =
                codec.encodeEnvelope(
                                MessagePayload(
                                        "native-1",
                                        "alice@example.com",
                                        listOf("me@example.com"),
                                        "from native"
                                ),
                                recipientPublicKey,
                                senderPrivateKey,
                        )
                        .getOrThrow()
        val messageDao = mock<MessageDao>()
        val threadDao = mock<ThreadDao>()
        val database = mock<ImessageDatabase>()
        whenever(database.messageDao()).thenReturn(messageDao)
        whenever(database.threadDao()).thenReturn(threadDao)
        whenever(messageDao.existsById("native-1")).thenReturn(false)
        whenever(threadDao.existsById(any())).thenReturn(false)
        val pushRepository = RecordingPushRepository()
        val processor =
                PushProcessor(
                        database = database,
                        messageCodec = codec,
                        codecKeysProvider = { CodecKeys(senderCertificate, recipientPrivateKey) },
                        pushRepository = pushRepository,
                        transaction = { block -> block() },
                )
        val socket = FakeIpcSocket()
        val client = clientFor(socket)
        try {
            assertTrue(client.connect().isSuccess)
            val nextEvent = async { client.observeEvents().first() }
            socket.sendEvent(
                    """{"type":"MESSAGE_RECEIVED","message_id":"native-1","sender":"alice@example.com","timestamp":789,"envelope":"${java.util.Base64.getEncoder().encodeToString(envelope)}"}""",
            )

            val event = assertIs<NativeEvent.MessageReceived>(nextEvent.await())
            assertTrue(processor.processNative(event.message, receivedAt = 900L))

            val message = argumentCaptor<MessageEntity>()
            verify(messageDao).insert(message.capture())
            assertEquals("from native", message.firstValue.body)
            assertEquals("native-1", message.firstValue.id)
            assertEquals(3, message.firstValue.status)
            val thread = argumentCaptor<ThreadEntity>()
            verify(threadDao).insert(thread.capture())
            assertEquals("from native", thread.firstValue.lastMessage)
            assertEquals("native-service", pushRepository.recorded.single().distributorInstance)
            assertEquals(listOf("native-1"), pushRepository.processed)
        } finally {
            client.disconnect()
            socket.close()
        }
    }

    @Test
    fun brokenSocketReconnectsWithANewFixtureConnection() = runBlocking {
        val sockets = listOf(FakeIpcSocket(), FakeIpcSocket())
        val connectionCount = AtomicInteger()
        val client =
                NativeServiceClient(
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                        socketName = "fixture",
                        socketFactory = { sockets[connectionCount.getAndIncrement()] },
                )
        try {
            assertTrue(client.connect().isSuccess)
            sockets.first().close()

            val restored =
                    withTimeout(4_000) {
                        client.connectionState.first {
                            it == NativeServiceState.Connected && connectionCount.get() == 2
                        }
                    }

            assertEquals(NativeServiceState.Connected, restored)
            assertEquals(2, connectionCount.get())
        } finally {
            client.disconnect()
            sockets.forEach(FakeIpcSocket::close)
        }
    }

    @Test
    fun heartbeatIntervalRemainsThirtySeconds() {
        assertEquals(30_000L, NATIVE_HEARTBEAT_INTERVAL_MS)
    }

    private fun clientFor(socket: FakeIpcSocket) =
            NativeServiceClient(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    socketName = "fixture",
                    socketFactory = { socket },
            )

    private fun createCertificate(
            publicKey: java.security.PublicKey,
            privateKey: PrivateKey,
    ): X509Certificate {
        val subject = X500Name("CN=fixture-sender")
        val builder =
                JcaX509v3CertificateBuilder(
                        subject,
                        BigInteger.ONE,
                        Date(System.currentTimeMillis() - 1000),
                        Date(System.currentTimeMillis() + 86_400_000),
                        subject,
                        publicKey,
                )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(privateKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private class RecordingPushRepository : IPushProcessingRepository {
        val recorded = mutableListOf<IncomingPushEntity>()
        val processed = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()

        override suspend fun recordPush(push: IncomingPushEntity): Result<Unit> {
            recorded += push
            return Result.success(Unit)
        }

        override suspend fun markProcessed(pushId: String): Result<Unit> {
            processed += pushId
            return Result.success(Unit)
        }

        override suspend fun markFailed(pushId: String, failureReason: String): Result<Unit> {
            failed += pushId to failureReason
            return Result.success(Unit)
        }

        override suspend fun getUnprocessed(): List<IncomingPushEntity> = recorded
    }

    private class FakeIpcSocket : IpcSocketConnection {
        private val incoming = PipedInputStream(64 * 1024)
        private val serviceOutput = PipedOutputStream(incoming)
        private val commands = LinkedBlockingQueue<ByteArray>()

        override val inputStream: InputStream = incoming
        override val outputStream: OutputStream =
                object : OutputStream() {
                    override fun write(value: Int) {
                        commands.offer(byteArrayOf(value.toByte()))
                    }

                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        commands.offer(bytes.copyOfRange(offset, offset + length))
                    }
                }

        override fun connect() = Unit

        override fun close() {
            serviceOutput.close()
            incoming.close()
        }

        suspend fun awaitCommand(): String {
            val frame = withContext(Dispatchers.IO) { commands.poll(2, TimeUnit.SECONDS) }
            check(frame != null) { "client did not write a command" }
            val input = DataInputStream(ByteArrayInputStream(frame))
            val size = input.readInt()
            check(size == input.available()) { "invalid outgoing frame length $size" }
            return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
        }

        suspend fun sendEvent(json: String) {
            withContext(Dispatchers.IO) {
                val payload = json.toByteArray(Charsets.UTF_8)
                val frame = ByteArrayOutputStream(payload.size + 4)
                DataOutputStream(frame).use { output ->
                    output.writeInt(payload.size)
                    output.write(payload)
                }
                serviceOutput.write(frame.toByteArray())
                serviceOutput.flush()
            }
        }
    }
}
