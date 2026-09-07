package com.thelightphone.lightimessage.data.datastore

import java.security.PrivateKey

/**
 * Repository interface for encrypted session token and private key storage. All operations are
 * suspend functions (coroutine-safe) and return Result types.
 */
interface ITokenRepository {
    /**
     * Saves a session token with expiration timestamp.
     * @param token The session token string
     * @param expiresAt Expiration timestamp in milliseconds
     * @return Result indicating success or containing error details
     */
    suspend fun saveSessionToken(
            token: String,
            expiresAt: Long,
    ): Result<Unit>

    /**
     * Retrieves the current session token if not expired.
     * @return Result.success(token) if valid and not expired, Result.success(null) if expired,
     * ```
     *         or Result.failure if retrieval fails
     * ```
     */
    suspend fun getSessionToken(): Result<String?>

    /**
     * Clears the session token from storage.
     * @return Result indicating success or containing error details
     */
    suspend fun clearSessionToken(): Result<Unit>

    /**
     * Saves a private key with a unique identifier. Private keys are stored as PKCS#8 DER-encoded
     * bytes, encrypted at rest.
     * @param key The PrivateKey to store
     * @param keyId Unique identifier for this key
     * @return Result indicating success or containing error details
     */
    suspend fun savePrivateKey(
            key: PrivateKey,
            keyId: String,
    ): Result<Unit>

    /**
     * Retrieves a stored private key by its identifier.
     * @param keyId The key identifier
     * @return Result.success(key) if found, Result.success(null) if not found,
     * ```
     *         or Result.failure if retrieval fails
     * ```
     */
    suspend fun getPrivateKey(keyId: String): Result<PrivateKey?>

    /**
     * Lists all stored private key identifiers.
     * @return Result containing list of keyIds or failure
     */
    suspend fun listPrivateKeys(): Result<List<String>>

    /**
     * Deletes a private key by its identifier.
     * @param keyId The key identifier
     * @return Result indicating success or containing error details
     */
    suspend fun deletePrivateKey(keyId: String): Result<Unit>

    /**
     * Saves the Apple ID string.
     * @param appleId The Apple ID to store
     * @return Result indicating success or containing error details
     */
    suspend fun saveAppleId(appleId: String): Result<Unit>

    /**
     * Retrieves the stored Apple ID.
     * @return Result.success(appleId) if found, Result.success(null) if not found,
     * ```
     *         or Result.failure if retrieval fails
     * ```
     */
    suspend fun getAppleId(): Result<String?>

    /**
     * Saves hardware information as encrypted bytes.
     * @param hwInfo Hardware information as ByteArray
     * @return Result indicating success or containing error details
     */
    suspend fun saveHardwareInfo(hwInfo: ByteArray): Result<Unit>

    /**
     * Retrieves the stored hardware information.
     * @return Result.success(hwInfo) if found, Result.success(null) if not found,
     * ```
     *         or Result.failure if retrieval fails
     * ```
     */
    suspend fun getHardwareInfo(): Result<ByteArray?>
}
