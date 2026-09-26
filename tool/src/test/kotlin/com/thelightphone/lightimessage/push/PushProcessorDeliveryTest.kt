package com.thelightphone.lightimessage.push

import com.thelightphone.lightimessage.data.dao.MessageDao
import com.thelightphone.lightimessage.data.dao.ThreadDao
import com.thelightphone.lightimessage.data.database.ImessageDatabase
import com.thelightphone.lightimessage.data.datastore.IPushRegistrationRepository
import com.thelightphone.lightimessage.data.entity.MessageEntity
import com.thelightphone.lightimessage.data.entity.ThreadEntity
import com.thelightphone.lightimessage.domain.codec.MessageCodec
import com.thelightphone.lightimessage.domain.codec.MessagePayload
import com.thelightphone.lightimessage.domain.codec.PlistCodec
import com.thelightphone.lightimessage.domain.crypto.CryptoEngine
import com.thelightphone.lightimessage.domain.push.PushMessage
import java.math.BigInteger
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PushProcessorDeliveryTest {
    @Test
    fun `MESSAGE_DELIVERY decrypts fixture envelope and persists message`() = runTest {
        val crypto = CryptoEngine()
        val codec = MessageCodec(PlistCodec(), crypto)
        val (recipientPublicKey, recipientPrivateKey) = crypto.generateRsaKeyPair()
        val (senderPublicKey, senderPrivateKey) = crypto.generateEcdsaKeyPair()
        val senderCertificate = createCertificate(senderPublicKey, senderPrivateKey)
        val payload =
                MessagePayload(
                        "message-4",
                        "alice@example.com",
                        listOf("me@example.com"),
                        "known body"
                )
        val envelope =
                codec.encodeEnvelope(payload, recipientPublicKey, senderPrivateKey).getOrThrow()
        val messageDao = mock<MessageDao>()
        val threadDao = mock<ThreadDao>()
        whenever(messageDao.existsById("message-4")).thenReturn(false)
        whenever(threadDao.existsById(any())).thenReturn(false)
        val database = mock<ImessageDatabase>()
        whenever(database.messageDao()).thenReturn(messageDao)
        whenever(database.threadDao()).thenReturn(threadDao)

        val registrationRepository = mock<IPushRegistrationRepository>()
        val processor =
                PushProcessor(
                        database = database,
                        messageCodec = codec,
                        codecKeysProvider = { CodecKeys(senderCertificate, recipientPrivateKey) },
                        pushRegistrationRepository = registrationRepository,
                        transaction = { block -> block() },
                )

        assertTrue(
                processor.processNative(
                        PushMessage("message-4", "alice@example.com", 123L, envelope),
                ),
        )
        verify(registrationRepository, org.mockito.kotlin.never()).getRegistration(any())

        val message = argumentCaptor<MessageEntity>()
        verify(messageDao).insert(message.capture())
        assertEquals("known body", message.firstValue.body)
        assertEquals("message-4", message.firstValue.id)
        assertEquals(3, message.firstValue.status)
        val thread = argumentCaptor<ThreadEntity>()
        verify(threadDao).insert(thread.capture())
        assertEquals("known body", thread.firstValue.lastMessage)
    }

    private fun createCertificate(
            publicKey: java.security.PublicKey,
            privateKey: PrivateKey
    ): X509Certificate {
        val subject = X500Name("CN=fixture-sender")
        val builder =
                JcaX509v3CertificateBuilder(
                        subject,
                        BigInteger.ONE,
                        Date(System.currentTimeMillis() - 1000),
                        Date(System.currentTimeMillis() + 86400000),
                        subject,
                        publicKey,
                )
        return JcaX509CertificateConverter()
                .getCertificate(
                        builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(privateKey))
                )
    }
}
