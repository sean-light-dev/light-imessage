package com.thelightphone.lightimessage.domain.native

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class NativeServiceClientEventTest {
    @Test
    fun unsolicitedEventsAreDemultiplexedWhileCommandAwaitsAck() = runBlocking {
        val socket = FakeIpcSocket()
        val client =
                NativeServiceClient(
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                        socketName = "fixture",
                        socketFactory = { socket },
                )
        try {
            assertTrue(client.connect().isSuccess)
            val send = async { client.sendMessage("m1", listOf("tel:+15551234567"), "hello") }
            socket.awaitCommand()

            socket.sendEvent(
                    """{"type":"MESSAGE_RECEIVED","message_id":"m2","sender":"alice@example.com","timestamp":123,"envelope":"AQI="}""",
            )
            socket.sendEvent(
                    """{"type":"DELIVERY_RECEIPT","message_id":"m1","delivery_receipt_at":456}""",
            )
            socket.sendEvent("""{"type":"ACK","message_id":"m1"}""")

            assertEquals("m1", send.await().getOrThrow())
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
                    withTimeout(2_000) { client.observeEvents().take(2).toList() },
            )
        } finally {
            client.disconnect()
            socket.close()
        }
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

        suspend fun awaitCommand() {
            val command = withContext(Dispatchers.IO) { commands.poll(2, TimeUnit.SECONDS) }
            check(command != null) { "client did not write a command" }
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
