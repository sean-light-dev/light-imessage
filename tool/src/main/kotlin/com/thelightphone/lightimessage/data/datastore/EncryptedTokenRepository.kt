package com.thelightphone.lightimessage.data.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * [ITokenRepository] implementation backed by DataStore with values encrypted under an
 * AndroidKeyStore-resident AES-256-GCM key.
 *
 * Each stored value is `Base64(IV (12 bytes) || ciphertext || GCM tag)`, encrypted with a fresh
 * random IV per write. The AES key never leaves the TEE-backed keystore. The key is created (and
 * persisted) lazily on first access, so state survives process and app restarts. If the key is
 * invalidated by the platform (e.g. after biometric enrollment change) reads will surface
 * exceptions; callers should treat that as "sign-in required" and clear dependent state.
 *
 * This replaces the original `EncryptedSharedPreferences` (androidx.security:security-crypto)
 * implementation, which is not on the Light SDK dependency allowlist — the fallback path
 * anticipated by ADR-006 (plaintext preference encryption via javax.crypto AES-256-GCM), with the
 * data key held in AndroidKeyStore rather than alongside the data.
 *
 * ### Heap exposure Values are returned as [String]/[ByteArray] and therefore live in the JVM heap
 * for the lifetime of the returned reference. Callers that need short-lived credential material
 * should overwrite their references promptly.
 *
 * @param dataStore Preferences DataStore from the Light SDK's SealedLightContext.
 */
class EncryptedTokenRepository(
        private val dataStore: DataStore<Preferences>,
) : ITokenRepository {
    companion object {
        private const val KEYSTORE_ALIAS = "light_imessage_token_master"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        // Field keys
        private val KEY_SESSION_TOKEN = stringPreferencesKey("session_token")
        private val KEY_SESSION_EXPIRES = longPreferencesKey("session_expires")
        private val KEY_APPLE_ID = stringPreferencesKey("apple_id")
        private val KEY_HARDWARE_INFO = stringPreferencesKey("hardware_info")
        private val KEY_PRIVATE_KEY_IDS = stringSetPreferencesKey("private_key_ids")
        private const val KEY_PRIVATE_KEY_PREFIX = "private_key."
        private const val KEY_PRIVATE_KEY_ALG_PREFIX = "private_key_alg."

        /** Restricted keyId charset: URL-safe, bounded length — prevents pref-key collisions. */
        private val KEY_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        private fun requireValidKeyId(keyId: String) {
            require(KEY_ID_PATTERN.matches(keyId)) {
                "invalid keyId (must match ${KEY_ID_PATTERN.pattern})"
            }
        }

        private fun privateKeyKey(keyId: String) = stringPreferencesKey(KEY_PRIVATE_KEY_PREFIX + keyId)
        private fun privateKeyAlgKey(keyId: String) =
                stringPreferencesKey(KEY_PRIVATE_KEY_ALG_PREFIX + keyId)
    }

    // ---- Keystore-backed AES-GCM primitive ------------------------------------

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    /** Lazily create (or fetch) the AES-256 master key. It never leaves the keystore. */
    private val masterKey: SecretKey by lazy {
        keyStore.getEntry(KEYSTORE_ALIAS, null)?.let { (it as KeyStore.SecretKeyEntry).secretKey }
                ?: run {
                    val generator =
                            KeyGenerator.getInstance(
                                    KeyProperties.KEY_ALGORITHM_AES,
                                    "AndroidKeyStore",
                            )
                    generator.init(
                            KeyGenParameterSpec.Builder(
                                            KEYSTORE_ALIAS,
                                            KeyProperties.PURPOSE_ENCRYPT or
                                                    KeyProperties.PURPOSE_DECRYPT,
                                    )
                                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                    .setKeySize(256)
                                    .build(),
                    )
                    generator.generateKey()
                }
    }

    /** Encrypt and encode as Base64(IV || ciphertext || tag). */
    private fun encrypt(plaintext: ByteArray): String {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    /** Decode and decrypt a value produced by [encrypt]. */
    private fun decrypt(encoded: String): ByteArray {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > GCM_IV_BYTES) { "ciphertext too short" }
        val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    // ---- ITokenRepository ------------------------------------------------------

    override suspend fun saveSessionToken(
            token: String,
            expiresAt: Long,
    ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val encrypted = encrypt(token.toByteArray(Charsets.UTF_8))
                    dataStore.edit { prefs ->
                        prefs[KEY_SESSION_TOKEN] = encrypted
                        prefs[KEY_SESSION_EXPIRES] = expiresAt
                    }
                    Result.success(Unit)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

    override suspend fun getSessionToken(): Result<String?> =
            withContext(Dispatchers.IO) {
                try {
                    val prefs = dataStore.data.first()
                    val encrypted = prefs[KEY_SESSION_TOKEN]
                    if (encrypted == null) {
                        Result.success(null)
                    } else {
                        val expiresAt = prefs[KEY_SESSION_EXPIRES] ?: 0L
                        if (expiresAt == 0L || System.currentTimeMillis() > expiresAt) {
                            Result.success(null)
                        } else {
                            Result.success(String(decrypt(encrypted), Charsets.UTF_8))
                        }
                    }
                } catch (e: Throwable) {
                    // Corrupt / unauthenticated record — best-effort clear so future reads
                    // don't loop on the same broken ciphertext (e.g. after master key
                    // invalidation). Preserves original failure cause.
                    runCatching {
                        dataStore.edit { prefs ->
                            prefs.remove(KEY_SESSION_TOKEN)
                            prefs.remove(KEY_SESSION_EXPIRES)
                        }
                    }
                    Result.failure(e)
                }
            }

    override suspend fun clearSessionToken(): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    dataStore.edit { prefs ->
                        prefs.remove(KEY_SESSION_TOKEN)
                        prefs.remove(KEY_SESSION_EXPIRES)
                    }
                    Result.success(Unit)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

    override suspend fun savePrivateKey(
            key: PrivateKey,
            keyId: String,
    ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    requireValidKeyId(keyId)
                    val encoded = key.encoded ?: error("PrivateKey has no PKCS#8 encoding")
                    val encrypted = encrypt(encoded)
                    dataStore.edit { prefs ->
                        prefs[privateKeyKey(keyId)] = encrypted
                        prefs[privateKeyAlgKey(keyId)] = key.algorithm
                        prefs[KEY_PRIVATE_KEY_IDS] = (prefs[KEY_PRIVATE_KEY_IDS] ?: emptySet()) + keyId
                    }
                    Result.success(Unit)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

    override suspend fun getPrivateKey(keyId: String): Result<PrivateKey?> =
            withContext(Dispatchers.IO) {
                try {
                    requireValidKeyId(keyId)
                    val prefs = dataStore.data.first()
                    val encrypted =
                            prefs[privateKeyKey(keyId)]
                                    ?: return@withContext Result.success(null)
                    val algorithm = prefs[privateKeyAlgKey(keyId)] ?: "RSA"
                    val bytes = decrypt(encrypted)
                    val privateKey =
                            KeyFactory.getInstance(algorithm)
                                    .generatePrivate(PKCS8EncodedKeySpec(bytes))
                    Result.success(privateKey)
                } catch (e: Throwable) {
                    // Clear the individual record on decryption / parse failure to avoid a
                    // permanent read loop when the master key is invalidated.
                    runCatching {
                        dataStore.edit { prefs ->
                            prefs.remove(privateKeyKey(keyId))
                            prefs.remove(privateKeyAlgKey(keyId))
                        }
                    }
                    Result.failure(e)
                }
            }

    override suspend fun listPrivateKeys(): Result<List<String>> =
            withContext(Dispatchers.IO) {
                try {
                    val ids = dataStore.data.first()[KEY_PRIVATE_KEY_IDS] ?: emptySet()
                    // Defensive: drop anything that no longer matches the accepted charset.
                    Result.success(ids.filter { KEY_ID_PATTERN.matches(it) })
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

    override suspend fun deletePrivateKey(keyId: String): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    requireValidKeyId(keyId)
                    dataStore.edit { prefs ->
                        prefs.remove(privateKeyKey(keyId))
                        prefs.remove(privateKeyAlgKey(keyId))
                        prefs[KEY_PRIVATE_KEY_IDS] = (prefs[KEY_PRIVATE_KEY_IDS] ?: emptySet()) - keyId
                    }
                    Result.success(Unit)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

    override suspend fun saveAppleId(appleId: String): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val encrypted = encrypt(appleId.toByteArray(Charsets.UTF_8))
                    dataStore.edit { prefs -> prefs[KEY_APPLE_ID] = encrypted }
                    Result.success(Unit)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

    override suspend fun getAppleId(): Result<String?> =
            withContext(Dispatchers.IO) {
                try {
                    val encrypted = dataStore.data.first()[KEY_APPLE_ID]
                    Result.success(encrypted?.let { String(decrypt(it), Charsets.UTF_8) })
                } catch (e: Throwable) {
                    runCatching { dataStore.edit { prefs -> prefs.remove(KEY_APPLE_ID) } }
                    Result.failure(e)
                }
            }

    override suspend fun saveHardwareInfo(hwInfo: ByteArray): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val encrypted = encrypt(hwInfo)
                    dataStore.edit { prefs -> prefs[KEY_HARDWARE_INFO] = encrypted }
                    Result.success(Unit)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }

    override suspend fun getHardwareInfo(): Result<ByteArray?> =
            withContext(Dispatchers.IO) {
                try {
                    val encrypted =
                            dataStore.data.first()[KEY_HARDWARE_INFO]
                                    ?: return@withContext Result.success(null)
                    Result.success(decrypt(encrypted))
                } catch (e: Throwable) {
                    runCatching { dataStore.edit { prefs -> prefs.remove(KEY_HARDWARE_INFO) } }
                    Result.failure(e)
                }
            }
}
