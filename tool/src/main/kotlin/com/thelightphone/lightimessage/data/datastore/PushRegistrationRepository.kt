package com.thelightphone.lightimessage.data.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** DataStore-backed registration store using the same AndroidKeyStore AES-GCM scheme as tokens. */
class PushRegistrationRepository(
        private val dataStore: DataStore<Preferences>,
) : IPushRegistrationRepository {
    @Serializable
    private data class StoredRegistration(
            val id: String,
            val distributorPackage: String,
            val endpointUrl: String,
            val instance: String,
            val token: String,
            val registeredAt: Long,
    )

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }
    private val masterKey: SecretKey by lazy {
        keyStore.getEntry(KEYSTORE_ALIAS, null)?.let { (it as KeyStore.SecretKeyEntry).secretKey }
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                        .apply {
                            init(
                                    KeyGenParameterSpec.Builder(
                                                    KEYSTORE_ALIAS,
                                                    KeyProperties.PURPOSE_ENCRYPT or
                                                            KeyProperties.PURPOSE_DECRYPT,
                                            )
                                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                            .setEncryptionPaddings(
                                                    KeyProperties.ENCRYPTION_PADDING_NONE
                                            )
                                            .setKeySize(256)
                                            .build(),
                            )
                        }
                        .generateKey()
    }

    override suspend fun saveRegistration(registration: PushRegistration): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    requireValidInstance(registration.instance)
                    val value =
                            StoredRegistration(
                                    registration.id,
                                    registration.distributorPackage,
                                    registration.endpointUrl,
                                    registration.instance,
                                    registration.token,
                                    registration.registeredAt,
                            )
                    dataStore.edit {
                        it[key(registration.instance)] = encrypt(Json.encodeToString(value))
                    }
                    Unit
                }
            }

    override suspend fun getRegistration(instance: String): PushRegistration? =
            withContext(Dispatchers.IO) {
                requireValidInstance(instance)
                val encoded = dataStore.data.first()[key(instance)] ?: return@withContext null
                runCatching {
                            Json.decodeFromString<StoredRegistration>(decrypt(encoded)).let {
                                PushRegistration(
                                        it.id,
                                        it.distributorPackage,
                                        it.endpointUrl,
                                        it.instance,
                                        it.token,
                                        it.registeredAt
                                )
                            }
                        }
                        .getOrNull()
            }

    override suspend fun deleteRegistration(instance: String): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    requireValidInstance(instance)
                    dataStore.edit { it.remove(key(instance)) }
                    Unit
                }
            }

    private fun encrypt(value: String): String {
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(TAG_BITS, iv))
        return Base64.encodeToString(
                iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
        )
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
                Cipher.DECRYPT_MODE,
                masterKey,
                GCMParameterSpec(TAG_BITS, bytes.copyOf(IV_BYTES))
        )
        return String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
    }

    companion object {
        private const val KEYSTORE_ALIAS = "light_imessage_push_registration_master"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val INSTANCE_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        private fun key(instance: String) = stringPreferencesKey("push_registration.$instance")
        private fun requireValidInstance(instance: String) {
            require(INSTANCE_PATTERN.matches(instance)) { "invalid push registration instance" }
        }
    }
}
