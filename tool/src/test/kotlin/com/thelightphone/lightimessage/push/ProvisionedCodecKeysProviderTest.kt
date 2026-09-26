package com.thelightphone.lightimessage.push

import com.thelightphone.lightimessage.data.datastore.ITokenRepository
import com.thelightphone.lightimessage.domain.crypto.CryptoEngine
import java.math.BigInteger
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ProvisionedCodecKeysProviderTest {
    @Test
    fun `loads provisioned sender certificate and RSA recipient key`() = runTest {
        val crypto = CryptoEngine()
        val (senderPublicKey, senderPrivateKey) = crypto.generateEcdsaKeyPair()
        val senderCertificate = createCertificate(senderPublicKey, senderPrivateKey)
        val (_, recipientPrivateKey) = crypto.generateRsaKeyPair()
        val repository = mock<ITokenRepository>()
        whenever(repository.getHardwareInfo()).thenReturn(Result.success(senderCertificate.encoded))
        whenever(repository.listPrivateKeys()).thenReturn(Result.success(listOf("ids")))
        whenever(repository.getPrivateKey("ids")).thenReturn(Result.success(recipientPrivateKey))

        val keys = ProvisionedCodecKeysProvider(repository).get()

        assertNotNull(keys)
        assertEquals("RSA", keys.recipientKey.algorithm)
        assertEquals(senderCertificate.publicKey, keys.senderCert.publicKey)
    }

    @Test
    fun `returns no keys until both certificate and RSA key are provisioned`() = runTest {
        val repository = mock<ITokenRepository>()
        whenever(repository.getHardwareInfo()).thenReturn(Result.success(null))

        assertEquals(null, ProvisionedCodecKeysProvider(repository).get())
    }

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
                        Date(System.currentTimeMillis() + 86400000),
                        subject,
                        publicKey,
                )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(privateKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
