package com.thelightphone.lightimessage.domain.native

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Wire-contract tests: the IPC message encoding must match the native service's
 * `native-service/src/protocol.rs` (serde internally-tagged `"type"` field, SCREAMING_SNAKE_CASE
 * variants) byte-for-byte. Guards against the Kotlin/Rust wire-format drift found in the
 * post-migration audit.
 */
class IpcProtocolTest {

    // ---- Commands (Kotlin -> service) ----

    @Test
    fun pingSerializesToContractFrame() {
        val encoded = ipcJson.encodeToString(IpcCommand.serializer(), IpcCommand.Ping)
        assertEquals("""{"type":"PING"}""", encoded)
    }

    @Test
    fun activateSerializesToContractFrame() {
        val encoded =
                ipcJson.encodeToString(
                        IpcCommand.serializer(),
                        IpcCommand.Activate("user@icloud.com", "pw", null),
                )
        assertEquals(
                """{"type":"ACTIVATE","apple_id":"user@icloud.com","password":"pw","2fa_code":null}""",
                encoded,
        )
    }

    @Test
    fun activateWithTwoFaCodeSerializesToContractFrame() {
        val encoded =
                ipcJson.encodeToString(
                        IpcCommand.serializer(),
                        IpcCommand.Activate("user@icloud.com", "pw", "123456"),
                )
        assertEquals(
                """{"type":"ACTIVATE","apple_id":"user@icloud.com","password":"pw","2fa_code":"123456"}""",
                encoded,
        )
    }

    @Test
    fun sendMessageSerializesToContractFrame() {
        val encoded =
                ipcJson.encodeToString(
                        IpcCommand.serializer(),
                        IpcCommand.SendMessage(
                                messageId = "msg-1",
                                recipients = listOf("tel:+15551234567"),
                                text = "hello",
                        ),
                )
        assertEquals(
                """{"type":"SEND_MESSAGE","message_id":"msg-1","recipients":["tel:+15551234567"],"text":"hello","attachments":[]}""",
                encoded,
        )
    }

    @Test
    fun getMessagesSerializesToContractFrame() {
        val encoded =
                ipcJson.encodeToString(IpcCommand.serializer(), IpcCommand.GetMessages(1234567890))
        assertEquals("""{"type":"GET_MESSAGES","since":1234567890}""", encoded)
    }

    // ---- Events (service -> Kotlin) ----

    @Test
    fun pongDeserializesFromContractFrame() {
        val event = ipcJson.decodeFromString(IpcEvent.serializer(), """{"type":"PONG"}""")
        assertEquals(IpcEvent.Pong, event)
    }

    @Test
    fun activationStatusDeserializesFromContractFrame() {
        val event =
                ipcJson.decodeFromString(
                        IpcEvent.serializer(),
                        """{"type":"ACTIVATION_STATUS","status":"activated","handles":["tel:+15551234567"]}""",
                )
        assertEquals(
                IpcEvent.ActivationStatusEvent(
                        status = "activated",
                        handles = listOf("tel:+15551234567"),
                ),
                event,
        )
    }

    @Test
    fun activationStatusWithoutHandlesDeserializes() {
        val event =
                ipcJson.decodeFromString(
                        IpcEvent.serializer(),
                        """{"type":"ACTIVATION_STATUS","status":"pending"}""",
                )
        assertEquals(IpcEvent.ActivationStatusEvent(status = "pending"), event)
        assertNull((event as IpcEvent.ActivationStatusEvent).handles)
    }

    @Test
    fun ackDeserializesFromContractFrame() {
        val event =
                ipcJson.decodeFromString(
                        IpcEvent.serializer(),
                        """{"type":"ACK","message_id":"msg-1"}""",
                )
        assertEquals(IpcEvent.Ack(messageId = "msg-1"), event)
    }

    @Test
    fun errorDeserializesFromContractFrame() {
        val event =
                ipcJson.decodeFromString(
                        IpcEvent.serializer(),
                        """{"type":"ERROR","message":"boom"}""",
                )
        assertEquals(IpcEvent.Error(message = "boom"), event)
    }

    @Test
    fun unknownFieldsAreIgnoredForForwardCompatibility() {
        val event =
                ipcJson.decodeFromString(
                        IpcEvent.serializer(),
                        """{"type":"PONG","future_field":true}""",
                )
        assertEquals(IpcEvent.Pong, event)
    }
}
