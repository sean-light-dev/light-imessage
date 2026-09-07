package com.thelightphone.lightimessage.testing

import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Minimal stub [X509Certificate] for unit tests, replacing the BouncyCastle-generated
 * self-signed certificates from the old test suite (BouncyCastle is not available in this
 * sandbox). The production code under test (`CryptoEngine.ecdsaVerify`, used via
 * `MessageCodec.decodeEnvelope`) only ever reads `certificate.publicKey`, so that is the
 * only member implemented meaningfully; every other abstract member of
 * [X509Certificate]/`X509Extension` is overridden to satisfy the compiler and throws.
 */
class TestCertificate(private val publicKey: PublicKey) : X509Certificate() {

    override fun getPublicKey(): PublicKey = publicKey

    // -- java.security.cert.Certificate -------------------------------------

    override fun getEncoded(): ByteArray = throw UnsupportedOperationException()

    override fun verify(key: PublicKey): Unit = throw UnsupportedOperationException()

    override fun verify(key: PublicKey, sigProvider: String): Unit =
            throw UnsupportedOperationException()

    override fun toString(): String = "TestCertificate(publicKey=$publicKey)"

    // -- X509Extension ------------------------------------------------------

    override fun hasUnsupportedCriticalExtension(): Boolean =
            throw UnsupportedOperationException()

    override fun getCriticalExtensionOIDs(): MutableSet<String>? =
            throw UnsupportedOperationException()

    override fun getNonCriticalExtensionOIDs(): MutableSet<String>? =
            throw UnsupportedOperationException()

    override fun getExtensionValue(oid: String): ByteArray? =
            throw UnsupportedOperationException()

    // -- X509Certificate ----------------------------------------------------

    override fun checkValidity(): Unit = throw UnsupportedOperationException()

    override fun checkValidity(date: Date): Unit = throw UnsupportedOperationException()

    override fun getVersion(): Int = throw UnsupportedOperationException()

    override fun getSerialNumber(): BigInteger = throw UnsupportedOperationException()

    override fun getIssuerDN(): Principal = throw UnsupportedOperationException()

    override fun getSubjectDN(): Principal = throw UnsupportedOperationException()

    override fun getNotBefore(): Date = throw UnsupportedOperationException()

    override fun getNotAfter(): Date = throw UnsupportedOperationException()

    override fun getTBSCertificate(): ByteArray = throw UnsupportedOperationException()

    override fun getSignature(): ByteArray = throw UnsupportedOperationException()

    override fun getSigAlgName(): String = throw UnsupportedOperationException()

    override fun getSigAlgOID(): String = throw UnsupportedOperationException()

    override fun getSigAlgParams(): ByteArray? = throw UnsupportedOperationException()

    override fun getIssuerUniqueID(): BooleanArray = throw UnsupportedOperationException()

    override fun getSubjectUniqueID(): BooleanArray = throw UnsupportedOperationException()

    override fun getKeyUsage(): BooleanArray = throw UnsupportedOperationException()

    override fun getBasicConstraints(): Int = throw UnsupportedOperationException()
}
