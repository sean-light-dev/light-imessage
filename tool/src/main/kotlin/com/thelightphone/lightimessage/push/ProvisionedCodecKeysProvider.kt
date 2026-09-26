package com.thelightphone.lightimessage.push

import com.thelightphone.lightimessage.data.datastore.ITokenRepository
import java.io.ByteArrayInputStream
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/** Loads the certificate and RSA recipient key saved by completed provisioning. */
class ProvisionedCodecKeysProvider(private val tokenRepository: ITokenRepository) {
    suspend fun get(): CodecKeys? {
        val certificateBytes = tokenRepository.getHardwareInfo().getOrNull() ?: return null
        val senderCert = parseCertificate(certificateBytes) ?: return null
        val recipientKey = findRsaPrivateKey() ?: return null
        return CodecKeys(senderCert, recipientKey)
    }

    private suspend fun findRsaPrivateKey(): PrivateKey? {
        val keyIds = tokenRepository.listPrivateKeys().getOrNull() ?: return null
        for (keyId in keyIds) {
            val key = tokenRepository.getPrivateKey(keyId).getOrNull()
            if (key?.algorithm.equals("RSA", ignoreCase = true)) return key
        }
        return null
    }

    private fun parseCertificate(bytes: ByteArray): X509Certificate? =
            sequenceOf(bytes, decodeBase64(bytes))
                    .filterNotNull()
                    .mapNotNull { candidate ->
                        runCatching {
                                    CertificateFactory.getInstance("X.509")
                                            .generateCertificate(ByteArrayInputStream(candidate)) as
                                            X509Certificate
                                }
                                .getOrNull()
                    }
                    .firstOrNull()

    private fun decodeBase64(bytes: ByteArray): ByteArray? =
            runCatching { Base64.getDecoder().decode(String(bytes, Charsets.UTF_8).trim()) }
                    .getOrNull()
}
