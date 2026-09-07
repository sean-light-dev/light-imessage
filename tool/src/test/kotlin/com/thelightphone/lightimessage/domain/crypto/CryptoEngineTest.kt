package com.thelightphone.lightimessage.domain.crypto

import com.thelightphone.lightimessage.testing.TestCertificate
import java.security.PublicKey
import java.security.cert.X509Certificate
import kotlin.test.*
import kotlin.test.Test

/**
 * Comprehensive unit tests for CryptoEngine. Covers AES-256-GCM encryption/decryption,
 * RSA-2048-OAEP key wrapping, and ECDSA P-256 signing/verification. Target: 100% code coverage.
 */
class CryptoEngineTest {
    private val cryptoEngine = CryptoEngine()

    // ========== AES-256-GCM Key Generation ==========

    @Test
    fun testGenerateAesKey() {
        val key = cryptoEngine.generateAesKey()
        assertNotNull(key, "Key must not be null")
        assertEquals("AES", key.algorithm, "Key algorithm must be AES")
        assertEquals(256, key.encoded.size * 8, "Key size must be 256 bits (32 bytes)")
    }

    @Test
    fun testGenerateAesKeyUniqueness() {
        val key1 = cryptoEngine.generateAesKey()
        val key2 = cryptoEngine.generateAesKey()
        assertFalse(key1.encoded.contentEquals(key2.encoded), "Generated keys must be unique")
    }

    // ========== AES-256-GCM Encryption/Decryption ==========

    @Test
    fun testAesGcmEncryptDecryptSimple() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Hello, World!".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null).getOrThrow()
        assertNotNull(encryptResult, "Encryption result must not be null")
        assertEquals(12, encryptResult.iv.size, "IV must be 12 bytes")
        assertEquals(16, encryptResult.authTag.size, "Auth tag must be 16 bytes")

        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        null,
                )
        assertTrue(decryptResult.isSuccess, "Decryption must succeed")
        assertContentEquals(
            plaintext,
            decryptResult.getOrNull(),
            "Decrypted plaintext must match original",
        )
    }

    @Test
    fun testAesGcmEncryptDecryptRandomPlaintext() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = ByteArray(256) { it.toByte() }

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null).getOrThrow()
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        null,
                )

        assertTrue(decryptResult.isSuccess, "Decryption must succeed")
        assertContentEquals(
            plaintext,
            decryptResult.getOrNull(),
            "Decrypted plaintext must match original",
        )
    }

    @Test
    fun testAesGcmEncryptEmptyPlaintext() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = ByteArray(0)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null).getOrThrow()
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        null,
                )

        assertTrue(decryptResult.isSuccess, "Decryption of empty plaintext must succeed")
        assertEquals(0, decryptResult.getOrNull()?.size, "Decrypted empty plaintext must match")
    }

    @Test
    fun testAesGcmEncryptLargePlaintext() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = ByteArray(1024 * 100) { (it % 256).toByte() } // 100 KB

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null).getOrThrow()
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        null,
                )

        assertTrue(decryptResult.isSuccess, "Decryption of large plaintext must succeed")
        assertContentEquals(
            plaintext,
            decryptResult.getOrNull(),
            "Decrypted large plaintext must match original",
        )
    }

    @Test
    fun testAesGcmEncryptWithAAD() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret message".toByteArray(Charsets.UTF_8)
        val aad = "Additional authenticated data".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, aad).getOrThrow()
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        aad,
                )

        assertTrue(decryptResult.isSuccess, "Decryption with matching AAD must succeed")
        assertContentEquals(
            plaintext,
            decryptResult.getOrNull(),
            "Decrypted plaintext must match original",
        )
    }

    // ========== AES-GCM Authentication Tag Validation ==========

    @Test
    fun testAesGcmAuthTagValidation_CorruptedTag() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null).getOrThrow()

        // Corrupt the auth tag
        val corruptedTag = encryptResult.authTag.copyOf()
        corruptedTag[0] = (corruptedTag[0].toInt() xor 0xFF).toByte()

        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        corruptedTag,
                        null,
                )

        assertTrue(decryptResult.isFailure, "Decryption with corrupted tag must fail")
    }

    @Test
    fun testAesGcmAuthTagValidation_WrongAAD() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret".toByteArray(Charsets.UTF_8)
        val correctAAD = "Correct AAD".toByteArray(Charsets.UTF_8)
        val wrongAAD = "Wrong AAD".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, correctAAD).getOrThrow()
        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        wrongAAD,
                )

        assertTrue(decryptResult.isFailure, "Decryption with wrong AAD must fail")
    }

    @Test
    fun testAesGcmAuthTagValidation_CorruptedCiphertext() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null).getOrThrow()

        // Corrupt the ciphertext
        val corruptedCiphertext = encryptResult.ciphertext.copyOf()
        if (corruptedCiphertext.isNotEmpty()) {
            corruptedCiphertext[0] = (corruptedCiphertext[0].toInt() xor 0xFF).toByte()
        }

        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        corruptedCiphertext,
                        key,
                        encryptResult.iv,
                        encryptResult.authTag,
                        null,
                )

        assertTrue(decryptResult.isFailure, "Decryption with corrupted ciphertext must fail")
    }

    @Test
    fun testAesGcmAuthTagValidation_CorruptedIV() {
        val key = cryptoEngine.generateAesKey()
        val plaintext = "Secret".toByteArray(Charsets.UTF_8)

        val encryptResult = cryptoEngine.aesGcmEncrypt(plaintext, key, null).getOrThrow()

        // Corrupt the IV
        val corruptedIV = encryptResult.iv.copyOf()
        corruptedIV[0] = (corruptedIV[0].toInt() xor 0xFF).toByte()

        val decryptResult =
                cryptoEngine.aesGcmDecrypt(
                        encryptResult.ciphertext,
                        key,
                        corruptedIV,
                        encryptResult.authTag,
                        null,
                )

        assertTrue(decryptResult.isFailure, "Decryption with corrupted IV must fail")
    }

    // ========== RSA-2048-OAEP Key Wrapping ==========

    @Test
    fun testRsaOaepWrapUnwrapSimple() {
        val aesKey = cryptoEngine.generateAesKey()
        val (publicKey, privateKey) = cryptoEngine.generateRsaKeyPair()

        val wrapResult = cryptoEngine.rsaOaepWrap(aesKey, publicKey)
        assertTrue(wrapResult.isSuccess, "Wrapping must succeed")

        val wrappedKey = wrapResult.getOrNull()
        assertNotNull(wrappedKey, "Wrapped key must not be null")
        assertFalse(wrappedKey?.isEmpty() ?: true, "Wrapped key must not be empty")

        val unwrapResult = cryptoEngine.rsaOaepUnwrap(wrappedKey!!, privateKey)
        assertTrue(unwrapResult.isSuccess, "Unwrapping must succeed")

        val unwrappedKey = unwrapResult.getOrNull()
        assertNotNull(unwrappedKey, "Unwrapped key must not be null")
        assertContentEquals(
            aesKey.encoded,
            unwrappedKey?.encoded,
            "Unwrapped key must match original",
        )
    }

    @Test
    fun testRsaOaepWrapUnwrapMultipleKeys() {
        val (publicKey, privateKey) = cryptoEngine.generateRsaKeyPair()

        val key1 = cryptoEngine.generateAesKey()
        val key2 = cryptoEngine.generateAesKey()

        val wrap1 = cryptoEngine.rsaOaepWrap(key1, publicKey)
        val wrap2 = cryptoEngine.rsaOaepWrap(key2, publicKey)

        assertTrue(wrap1.isSuccess, "First wrapping must succeed")
        assertTrue(wrap2.isSuccess, "Second wrapping must succeed")

        val unwrap1 = cryptoEngine.rsaOaepUnwrap(wrap1.getOrThrow(), privateKey)
        val unwrap2 = cryptoEngine.rsaOaepUnwrap(wrap2.getOrThrow(), privateKey)

        assertTrue(unwrap1.isSuccess, "First unwrapping must succeed")
        assertTrue(unwrap2.isSuccess, "Second unwrapping must succeed")

        assertContentEquals(key1.encoded, unwrap1.getOrNull()?.encoded, "First key must match")
        assertContentEquals(key2.encoded, unwrap2.getOrNull()?.encoded, "Second key must match")
    }

    @Test
    fun testRsaOaepWrapUnwrapWrongPrivateKey() {
        val aesKey = cryptoEngine.generateAesKey()
        val (publicKey1, _) = cryptoEngine.generateRsaKeyPair()
        val (_, wrongPrivateKey) = cryptoEngine.generateRsaKeyPair()

        val wrappedKey = cryptoEngine.rsaOaepWrap(aesKey, publicKey1).getOrThrow()
        val unwrapResult = cryptoEngine.rsaOaepUnwrap(wrappedKey, wrongPrivateKey)

        assertTrue(unwrapResult.isFailure, "Unwrapping with wrong private key must fail")
    }

    @Test
    fun testRsaOaepWrapUnwrapCorruptedWrappedKey() {
        val aesKey = cryptoEngine.generateAesKey()
        val (publicKey, privateKey) = cryptoEngine.generateRsaKeyPair()

        val wrappedKey = cryptoEngine.rsaOaepWrap(aesKey, publicKey).getOrThrow()

        // Corrupt the wrapped key
        val corruptedWrappedKey = wrappedKey.copyOf()
        if (corruptedWrappedKey.isNotEmpty()) {
            corruptedWrappedKey[0] = (corruptedWrappedKey[0].toInt() xor 0xFF).toByte()
        }

        val unwrapResult = cryptoEngine.rsaOaepUnwrap(corruptedWrappedKey, privateKey)
        assertTrue(unwrapResult.isFailure, "Unwrapping corrupted key must fail")
    }

    // ========== ECDSA P-256 Signing/Verification ==========

    @Test
    fun testEcdsaSignVerifySimple() {
        val data = "Hello, ECDSA!".toByteArray(Charsets.UTF_8)
        val (publicKey, privateKey) = cryptoEngine.generateEcdsaKeyPair()

        val signResult = cryptoEngine.ecdsaSign(data, privateKey)
        assertTrue(signResult.isSuccess, "Signing must succeed")

        val signature = signResult.getOrNull()
        assertNotNull(signature, "Signature must not be null")
        assertFalse(signature?.isEmpty() ?: true, "Signature must not be empty")

        // Create a self-signed certificate for verification
        val cert = createSelfSignedEcdsaCertificate(publicKey)

        val verifyResult = cryptoEngine.ecdsaVerify(data, signature!!, cert)
        assertTrue(verifyResult.isSuccess, "Verification must succeed")
    }

    @Test
    fun testEcdsaSignVerifyRandomData() {
        val data = ByteArray(256) { it.toByte() }
        val (publicKey, privateKey) = cryptoEngine.generateEcdsaKeyPair()

        val signature = cryptoEngine.ecdsaSign(data, privateKey).getOrThrow()
        val cert = createSelfSignedEcdsaCertificate(publicKey)
        val verifyResult = cryptoEngine.ecdsaVerify(data, signature, cert)

        assertTrue(verifyResult.isSuccess, "Verification of random data must succeed")
    }

    @Test
    fun testEcdsaSignVerifyEmptyData() {
        val data = ByteArray(0)
        val (publicKey, privateKey) = cryptoEngine.generateEcdsaKeyPair()

        val signature = cryptoEngine.ecdsaSign(data, privateKey).getOrThrow()
        val cert = createSelfSignedEcdsaCertificate(publicKey)
        val verifyResult = cryptoEngine.ecdsaVerify(data, signature, cert)

        assertTrue(verifyResult.isSuccess, "Verification of empty data must succeed")
    }

    @Test
    fun testEcdsaSignVerifyLargeData() {
        val data = ByteArray(1024 * 100) { (it % 256).toByte() } // 100 KB
        val (publicKey, privateKey) = cryptoEngine.generateEcdsaKeyPair()

        val signature = cryptoEngine.ecdsaSign(data, privateKey).getOrThrow()
        val cert = createSelfSignedEcdsaCertificate(publicKey)
        val verifyResult = cryptoEngine.ecdsaVerify(data, signature, cert)

        assertTrue(verifyResult.isSuccess, "Verification of large data must succeed")
    }

    @Test
    fun testEcdsaSignVerifyInvalidSignature() {
        val data = "Original data".toByteArray(Charsets.UTF_8)
        val (publicKey, privateKey) = cryptoEngine.generateEcdsaKeyPair()

        val signature = cryptoEngine.ecdsaSign(data, privateKey).getOrThrow()

        // Corrupt the signature
        val corruptedSignature = signature.copyOf()
        if (corruptedSignature.isNotEmpty()) {
            corruptedSignature[0] = (corruptedSignature[0].toInt() xor 0xFF).toByte()
        }

        val cert = createSelfSignedEcdsaCertificate(publicKey)
        val verifyResult = cryptoEngine.ecdsaVerify(data, corruptedSignature, cert)

        assertTrue(verifyResult.isFailure, "Verification of corrupted signature must fail")
    }

    @Test
    fun testEcdsaSignVerifyDataTampered() {
        val originalData = "Original data".toByteArray(Charsets.UTF_8)
        val tamperedData = "Tampered data".toByteArray(Charsets.UTF_8)
        val (publicKey, privateKey) = cryptoEngine.generateEcdsaKeyPair()

        val signature = cryptoEngine.ecdsaSign(originalData, privateKey).getOrThrow()
        val cert = createSelfSignedEcdsaCertificate(publicKey)
        val verifyResult = cryptoEngine.ecdsaVerify(tamperedData, signature, cert)

        assertTrue(verifyResult.isFailure, "Verification with tampered data must fail")
    }

    @Test
    fun testEcdsaVerifyWithWrongKey() {
        val data = "Test data".toByteArray(Charsets.UTF_8)
        val (publicKey1, privateKey1) = cryptoEngine.generateEcdsaKeyPair()
        val (publicKey2, _) = cryptoEngine.generateEcdsaKeyPair()

        val signature = cryptoEngine.ecdsaSign(data, privateKey1).getOrThrow()

        val cert = createSelfSignedEcdsaCertificate(publicKey2)
        val verifyResult = cryptoEngine.ecdsaVerify(data, signature, cert)

        assertTrue(verifyResult.isFailure, "Verification with wrong public key must fail")
    }

    @Test
    fun testEcdsaSignVerifyMultipleSignatures() {
        val data = "Data to sign".toByteArray(Charsets.UTF_8)
        val (publicKey, privateKey) = cryptoEngine.generateEcdsaKeyPair()

        val signature1 = cryptoEngine.ecdsaSign(data, privateKey).getOrThrow()
        val signature2 = cryptoEngine.ecdsaSign(data, privateKey).getOrThrow()

        // ECDSA signatures are non-deterministic (random nonce), so signatures should differ
        assertFalse(
            signature1.contentEquals(signature2),
            "Multiple signatures of same data should differ (ECDSA uses random nonce)",
        )

        val cert = createSelfSignedEcdsaCertificate(publicKey)

        val verify1 = cryptoEngine.ecdsaVerify(data, signature1, cert)
        val verify2 = cryptoEngine.ecdsaVerify(data, signature2, cert)

        assertTrue(verify1.isSuccess, "First signature must verify")
        assertTrue(verify2.isSuccess, "Second signature must verify")
    }

    // ========== Helper Functions ==========

    /**
     * Creates a certificate wrapping [publicKey] for testing ECDSA verify.
     *
     * Only the public key inside the cert is inspected by `CryptoEngine.ecdsaVerify`; the cert's
     * own signature is never validated, so a stub certificate is sufficient. (The old suite
     * generated real self-signed certs via BouncyCastle, which is unavailable in this sandbox.)
     */
    private fun createSelfSignedEcdsaCertificate(publicKey: PublicKey): X509Certificate =
            TestCertificate(publicKey)
}
