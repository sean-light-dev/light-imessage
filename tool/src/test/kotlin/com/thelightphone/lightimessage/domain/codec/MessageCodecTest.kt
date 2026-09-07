package com.thelightphone.lightimessage.domain.codec

import com.thelightphone.lightimessage.domain.crypto.CryptoEngine
import com.thelightphone.lightimessage.testing.TestCertificate
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import kotlin.test.*
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Comprehensive unit tests for MessageCodec. Tests envelope encryption/decryption with AES-256-GCM,
 * RSA-2048-OAEP key wrapping, and ECDSA signing. Target: 100% code coverage.
 */
class MessageCodecTest {
    private lateinit var messageCodec: MessageCodec
    private lateinit var cryptoEngine: CryptoEngine

    @BeforeTest
    fun setUp() {
        cryptoEngine = CryptoEngine()
        val plistCodec = PlistCodec()
        messageCodec = MessageCodec(plistCodec, cryptoEngine)
    }

    // ========== Basic Envelope Encode/Decode ==========

    @Test
    fun testEncodeDecodeEnvelopeSimple() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
            MessagePayload(
                messageId = "msg-001",
                sender = "alice@example.com",
                recipients = listOf("bob@example.com"),
                body = "Hello, Bob!",
            )

        val encodeResult =
            messageCodec.encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
        assertTrue(encodeResult.isSuccess, "Encoding must succeed")

        val envelope = encodeResult.getOrThrow()
        assertNotNull(envelope, "Envelope must not be null")
        assertFalse(envelope.isEmpty(), "Envelope must not be empty")

        val decodeResult = messageCodec.decodeEnvelope(envelope, senderCert, recipientPrivateKey)
        assertTrue(decodeResult.isSuccess, "Decoding must succeed")

        val decodedPayload = decodeResult.getOrThrow()
        assertEquals(payload.messageId, decodedPayload.messageId, "Message ID must match")
        assertEquals(payload.sender, decodedPayload.sender, "Sender must match")
        assertEquals(payload.recipients, decodedPayload.recipients, "Recipients must match")
        assertEquals(payload.body, decodedPayload.body, "Body must match")
    }

    @Test
    fun testEncodeDecodeEnvelopeWithMetadata() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
            MessagePayload(
                messageId = "msg-002",
                sender = "alice@example.com",
                recipients = listOf("bob@example.com", "carol@example.com"),
                body = "Message with metadata",
                metadata = mapOf("priority" to "high", "thread-id" to "thread-123"),
            )

        val encodeResult =
            messageCodec.encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
        assertTrue(encodeResult.isSuccess, "Encoding must succeed")

        val decodeResult =
            messageCodec.decodeEnvelope(
                encodeResult.getOrThrow(),
                senderCert,
                recipientPrivateKey,
            )
        assertTrue(decodeResult.isSuccess, "Decoding must succeed")

        val decodedPayload = decodeResult.getOrThrow()
        assertEquals(payload.metadata, decodedPayload.metadata, "Metadata must match")
    }

    @Test
    fun testEncodeDecodeEnvelopeWithAttachments() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val attachmentKey = ByteArray(32) { it.toByte() }
        val attachment =
            MessagePayload.AttachmentInfo(
                id = "att-001",
                mimeType = "image/png",
                url = "https://example.com/image.png",
                size = 10240,
                encryptionKey = attachmentKey,
            )

        val payload =
            MessagePayload(
                messageId = "msg-003",
                sender = "alice@example.com",
                recipients = listOf("bob@example.com"),
                body = "Message with attachment",
                attachments = listOf(attachment),
            )

        val encodeResult =
            messageCodec.encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
        assertTrue(encodeResult.isSuccess, "Encoding must succeed")

        val decodeResult =
            messageCodec.decodeEnvelope(
                encodeResult.getOrThrow(),
                senderCert,
                recipientPrivateKey,
            )
        assertTrue(decodeResult.isSuccess, "Decoding must succeed")

        val decodedPayload = decodeResult.getOrThrow()
        assertEquals(
            payload.attachments.size,
            decodedPayload.attachments.size,
            "Attachments must match",
        )

        val decodedAttachment = decodedPayload.attachments[0]
        assertEquals(attachment.id, decodedAttachment.id, "Attachment ID must match")
        assertEquals(
            attachment.mimeType,
            decodedAttachment.mimeType,
            "Attachment MIME type must match",
        )
        assertEquals(attachment.url, decodedAttachment.url, "Attachment URL must match")
        assertEquals(attachment.size, decodedAttachment.size, "Attachment size must match")
        assertContentEquals(
            attachment.encryptionKey,
            decodedAttachment.encryptionKey,
            "Attachment encryption key must match",
        )
    }

    @Test
    fun testEncodeDecodeEnvelopeMultipleRecipients() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
            MessagePayload(
                messageId = "msg-004",
                sender = "alice@example.com",
                recipients =
                    listOf("bob@example.com", "carol@example.com", "dave@example.com"),
                body = "Group message",
            )

        val encodeResult =
            messageCodec.encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
        assertTrue(encodeResult.isSuccess, "Encoding must succeed")

        val decodeResult =
            messageCodec.decodeEnvelope(
                encodeResult.getOrThrow(),
                senderCert,
                recipientPrivateKey,
            )
        assertTrue(decodeResult.isSuccess, "Decoding must succeed")

        val decodedPayload = decodeResult.getOrThrow()
        assertEquals(3, decodedPayload.recipients.size, "Recipients count must match")
        assertTrue(
            decodedPayload.recipients.contains("bob@example.com"),
            "Recipients must contain all addresses",
        )
        assertTrue(
            decodedPayload.recipients.contains("carol@example.com"),
            "Recipients must contain all addresses",
        )
        assertTrue(
            decodedPayload.recipients.contains("dave@example.com"),
            "Recipients must contain all addresses",
        )
    }

    // ========== Signature Verification ==========

    @Test
    fun testSignatureVerification() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
            MessagePayload(
                messageId = "msg-005",
                sender = "alice@example.com",
                recipients = listOf("bob@example.com"),
                body = "Signed message",
            )

        val encodeResult =
            messageCodec.encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
        val envelope = encodeResult.getOrThrow()

        val decodeResult = messageCodec.decodeEnvelope(envelope, senderCert, recipientPrivateKey)
        assertTrue(decodeResult.isSuccess, "Decoding with correct sender cert must succeed")
    }

    @Test
    fun testSignatureVerificationWithWrongKey() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val (wrongPublicKey, _) = cryptoEngine.generateEcdsaKeyPair()

        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)
        val wrongCert =
            createTestCertificate(wrongPublicKey, cryptoEngine.generateEcdsaKeyPair().second)

        val payload =
            MessagePayload(
                messageId = "msg-006",
                sender = "alice@example.com",
                recipients = listOf("bob@example.com"),
                body = "Message",
            )

        val envelope =
            messageCodec
                .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                .getOrThrow()
        val decodeResult = messageCodec.decodeEnvelope(envelope, wrongCert, recipientPrivateKey)

        assertTrue(decodeResult.isFailure, "Decoding with wrong sender cert must fail")
    }

    // ========== Tampering Detection ==========

    @Test
    fun testRejectTamperedCiphertext() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
            MessagePayload(
                messageId = "msg-007",
                sender = "alice@example.com",
                recipients = listOf("bob@example.com"),
                body = "Tamper test",
            )

        val encodeResult =
            messageCodec.encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
        val envelope = encodeResult.getOrThrow()

        // Tamper with the envelope
        val tamperedEnvelope = envelope.copyOf()
        if (tamperedEnvelope.size > 100) {
            tamperedEnvelope[100] = (tamperedEnvelope[100].toInt() xor 0xFF).toByte()
        }

        val decodeResult =
            messageCodec.decodeEnvelope(tamperedEnvelope, senderCert, recipientPrivateKey)
        assertTrue(decodeResult.isFailure, "Decoding tampered envelope must fail")
    }

    @Test
    fun testRejectInvalidSignature() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val (otherPublicKey, otherPrivateKey) = cryptoEngine.generateEcdsaKeyPair()

        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
            MessagePayload(
                messageId = "msg-008",
                sender = "alice@example.com",
                recipients = listOf("bob@example.com"),
                body = "Invalid signature test",
            )

        // Encode with one key
        val envelope =
            messageCodec
                .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                .getOrThrow()

        // Try to decode with different sender certificate
        val wrongCert = createTestCertificate(otherPublicKey, otherPrivateKey)
        val decodeResult = messageCodec.decodeEnvelope(envelope, wrongCert, recipientPrivateKey)

        assertTrue(decodeResult.isFailure, "Decoding with mismatched signature must fail")
    }

    @Test
    fun testRejectUnsignedMessage() {
        val (_, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        // Create a malformed envelope without proper signature
        val malformedEnvelope =
            byteArrayOf(
                'b'.code.toByte(),
                'p'.code.toByte(),
                'l'.code.toByte(),
                'i'.code.toByte(),
                's'.code.toByte(),
                't'.code.toByte(),
                '0'.code.toByte(),
                '0'.code.toByte()
            ) + ByteArray(100)

        val decodeResult =
            messageCodec.decodeEnvelope(malformedEnvelope, senderCert, recipientPrivateKey)
        assertTrue(decodeResult.isFailure, "Decoding unsigned/malformed message must fail")
    }

    // ========== Metadata Preservation ==========

    @Test
    fun testMetadataPreservation() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val metadata =
            mapOf(
                "priority" to "high",
                "thread-id" to "thread-456",
                "custom-field" to "custom-value",
                "empty" to "",
            )

        val payload =
            MessagePayload(
                messageId = "msg-009",
                sender = "alice@example.com",
                recipients = listOf("bob@example.com"),
                body = "Metadata test",
                metadata = metadata,
            )

        val envelope =
            messageCodec
                .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                .getOrThrow()
        val decodedPayload =
            messageCodec.decodeEnvelope(envelope, senderCert, recipientPrivateKey).getOrThrow()

        assertEquals(metadata, decodedPayload.metadata, "All metadata must be preserved")
    }

    @Test
    fun testAttachmentMetadata() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val attachments =
            listOf(
                MessagePayload.AttachmentInfo(
                    id = "att-001",
                    mimeType = "image/jpeg",
                    url = "https://example.com/photo.jpg",
                    size = 204800,
                    encryptionKey = ByteArray(32),
                ),
                MessagePayload.AttachmentInfo(
                    id = "att-002",
                    mimeType = "video/mp4",
                    url = "https://example.com/video.mp4",
                    size = 5242880,
                    encryptionKey = ByteArray(32) { it.toByte() },
                ),
            )

        val payload =
            MessagePayload(
                messageId = "msg-010",
                sender = "alice@example.com",
                recipients = listOf("bob@example.com"),
                body = "Multiple attachments",
                attachments = attachments,
            )

        val envelope =
            messageCodec
                .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                .getOrThrow()
        val decodedPayload =
            messageCodec.decodeEnvelope(envelope, senderCert, recipientPrivateKey).getOrThrow()

        assertEquals(
            attachments.size,
            decodedPayload.attachments.size,
            "All attachments must be preserved",
        )

        for (i in attachments.indices) {
            assertEquals(
                attachments[i].id,
                decodedPayload.attachments[i].id,
                "Attachment ID must match",
            )
            assertEquals(
                attachments[i].mimeType,
                decodedPayload.attachments[i].mimeType,
                "Attachment MIME type must match",
            )
            assertEquals(
                attachments[i].url,
                decodedPayload.attachments[i].url,
                "Attachment URL must match",
            )
            assertEquals(
                attachments[i].size,
                decodedPayload.attachments[i].size,
                "Attachment size must match",
            )
        }
    }

    // ========== Edge Cases ==========

    @Test
    fun testEnvelopeWithEmptyBody() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
            MessagePayload(
                messageId = "msg-011",
                sender = "alice@example.com",
                recipients = listOf("bob@example.com"),
                body = "",
            )

        val envelope =
            messageCodec
                .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                .getOrThrow()
        val decodedPayload =
            messageCodec.decodeEnvelope(envelope, senderCert, recipientPrivateKey).getOrThrow()

        assertEquals("", decodedPayload.body, "Empty body must be preserved")
    }

    @Test
    fun testEnvelopeWithLargeBody() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val largeBody = "A".repeat(100000) // 100 KB

        val payload =
            MessagePayload(
                messageId = "msg-012",
                sender = "alice@example.com",
                recipients = listOf("bob@example.com"),
                body = largeBody,
            )

        val envelope =
            messageCodec
                .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                .getOrThrow()
        val decodedPayload =
            messageCodec.decodeEnvelope(envelope, senderCert, recipientPrivateKey).getOrThrow()

        assertEquals(largeBody, decodedPayload.body, "Large body must be preserved")
    }

    @Test
    fun testEnvelopeWithUnicodeBody() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val unicodeBody = "Hello 世界 🌍 مرحبا שלום"

        val payload =
            MessagePayload(
                messageId = "msg-013",
                sender = "alice@example.com",
                recipients = listOf("bob@example.com"),
                body = unicodeBody,
            )

        val envelope =
            messageCodec
                .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                .getOrThrow()
        val decodedPayload =
            messageCodec.decodeEnvelope(envelope, senderCert, recipientPrivateKey).getOrThrow()

        assertEquals(unicodeBody, decodedPayload.body, "Unicode body must be preserved")
    }

    @Test
    fun testEnvelopeWithNoRecipients() {
        val (recipientPublicKey, recipientPrivateKey) = cryptoEngine.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = cryptoEngine.generateEcdsaKeyPair()
        val senderCert = createTestCertificate(senderPublicKey, senderPrivateKey)

        val payload =
            MessagePayload(
                messageId = "msg-014",
                sender = "alice@example.com",
                recipients = emptyList(),
                body = "No recipients",
            )

        val envelope =
            messageCodec
                .encodeEnvelope(payload, recipientPublicKey, senderPrivateKey)
                .getOrThrow()
        val decodedPayload =
            messageCodec.decodeEnvelope(envelope, senderCert, recipientPrivateKey).getOrThrow()

        assertEquals(0, decodedPayload.recipients.size, "Empty recipients list must be preserved")
    }

    // ========== Plist Encoding/Decoding ==========

    @Test
    fun testEncodePlistSimple() {
        val value = PlistDict(mapOf("test" to PlistString("value")))
        val encodeResult = messageCodec.encodePlist(value)

        assertTrue(encodeResult.isSuccess, "Encoding must succeed")
        assertFalse(encodeResult.getOrThrow().isEmpty(), "Encoded plist must not be empty")
    }

    @Test
    fun testDecodePlistSimple() {
        val value = PlistDict(linkedMapOf("test" to PlistString("value")))
        val encoded = messageCodec.encodePlist(value).getOrThrow()
        val decodeResult = messageCodec.decodePlist(encoded)

        assertTrue(decodeResult.isSuccess, "Decoding must succeed")
        assertEquals(value, decodeResult.getOrNull(), "Decoded plist must match original")
    }

    @Test
    fun testEncodePlistAllTypes() {
        val value =
            PlistDict(
                linkedMapOf(
                    "array" to PlistArray(listOf(PlistInteger(1L), PlistInteger(2L))),
                    "bool" to PlistBoolean(true),
                    "data" to PlistData(byteArrayOf(1, 2, 3)),
                    "dict" to PlistDict(linkedMapOf("nested" to PlistString("value"))),
                    "float" to PlistFloat(3.14),
                    "int" to PlistInteger(42L),
                    "null" to PlistNull,
                    "string" to PlistString("test"),
                ),
            )

        val encoded = messageCodec.encodePlist(value).getOrThrow()
        val decoded = messageCodec.decodePlist(encoded).getOrThrow()

        assertEquals(value, decoded, "All types must roundtrip")
    }

    // ========== Helper Functions ==========

    /**
     * Creates a certificate carrying [publicKey]. The old suite generated a self-signed cert
     * via BouncyCastle signed with [privateKey]; BouncyCastle is unavailable in this sandbox
     * and `MessageCodec` only ever reads `certificate.publicKey`, so a stub is sufficient
     * ([privateKey] is kept for signature compatibility and ignored).
     */
    private fun createTestCertificate(
        publicKey: PublicKey,
        @Suppress("UNUSED_PARAMETER") privateKey: PrivateKey,
    ): X509Certificate = TestCertificate(publicKey)
}
