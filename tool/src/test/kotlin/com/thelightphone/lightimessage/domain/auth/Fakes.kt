package com.thelightphone.lightimessage.domain.auth

import com.thelightphone.lightimessage.data.datastore.ITokenRepository
import com.thelightphone.lightimessage.data.provisioning.ActivationStatus
import com.thelightphone.lightimessage.data.provisioning.HardwareInfo
import com.thelightphone.lightimessage.data.provisioning.IProvisioningClient
import com.thelightphone.lightimessage.data.relay.IRelayHttpClient
import com.thelightphone.lightimessage.data.relay.LoginResponse
import com.thelightphone.lightimessage.data.relay.SessionResponse
import java.security.PrivateKey

/**
 * Hand-written replacement for the old Mockito `ITokenRepository` mock: in-memory storage
 * with per-method [Result] overrides for the failure paths the tests actually stub. When an
 * override is null the fake behaves like a successful in-memory store; setting an override
 * short-circuits the in-memory behavior (e.g. `tokenRepo.clearSessionTokenResult =
 * Result.failure(...)`).
 */
class FakeTokenRepository : ITokenRepository {

    // In-memory storage
    var appleId: String? = null
        private set
    var sessionToken: String? = null
        private set
    var sessionTokenExpiresAt: Long? = null
        private set
    var hardwareInfo: ByteArray? = null
        private set
    private val privateKeys = mutableMapOf<String, PrivateKey>()

    // Programmable overrides; null means "use in-memory behavior".
    var getSessionTokenResult: Result<String?>? = null
    var clearSessionTokenResult: Result<Unit>? = null
    var getAppleIdResult: Result<String?>? = null

    override suspend fun saveSessionToken(token: String, expiresAt: Long): Result<Unit> {
        sessionToken = token
        sessionTokenExpiresAt = expiresAt
        return Result.success(Unit)
    }

    override suspend fun getSessionToken(): Result<String?> =
            getSessionTokenResult ?: Result.success(sessionToken)

    override suspend fun clearSessionToken(): Result<Unit> =
            clearSessionTokenResult
                    ?: run {
                        sessionToken = null
                        sessionTokenExpiresAt = null
                        Result.success(Unit)
                    }

    override suspend fun savePrivateKey(key: PrivateKey, keyId: String): Result<Unit> {
        privateKeys[keyId] = key
        return Result.success(Unit)
    }

    override suspend fun getPrivateKey(keyId: String): Result<PrivateKey?> =
            Result.success(privateKeys[keyId])

    override suspend fun listPrivateKeys(): Result<List<String>> =
            Result.success(privateKeys.keys.toList())

    override suspend fun deletePrivateKey(keyId: String): Result<Unit> {
        privateKeys.remove(keyId)
        return Result.success(Unit)
    }

    override suspend fun saveAppleId(appleId: String): Result<Unit> {
        this.appleId = appleId
        return Result.success(Unit)
    }

    override suspend fun getAppleId(): Result<String?> =
            getAppleIdResult ?: Result.success(appleId)

    override suspend fun saveHardwareInfo(hwInfo: ByteArray): Result<Unit> {
        hardwareInfo = hwInfo
        return Result.success(Unit)
    }

    override suspend fun getHardwareInfo(): Result<ByteArray?> = Result.success(hardwareInfo)
}

/**
 * Hand-written replacement for the old Mockito `IRelayHttpClient` mock. Each endpoint is a
 * programmable lambda; unconfigured endpoints return a failure Result (the Mockito default —
 * null for unstubbed methods — would NPE through `Result.isFailure`, so the fake makes the
 * unstubbed case an explicit, recoverable failure instead). Lambdas may also throw to mirror
 * `.thenThrow(...)` stubbing; `AuthStateMachine` catches thrown exceptions the same way it
 * handles failure Results.
 */
class FakeRelayHttpClient : IRelayHttpClient {
    var onLoginWithCredentials: (String, String) -> Result<LoginResponse> =
            { _, _ -> Result.failure(Exception("unexpected call: loginWithCredentials")) }
    var onSubmitTwoFactor: (String, String) -> Result<SessionResponse> =
            { _, _ -> Result.failure(Exception("unexpected call: submitTwoFactor")) }
    var onResendTwoFactor: (String) -> Result<Unit> =
            { Result.failure(Exception("unexpected call: resendTwoFactor")) }
    var onRefreshToken: (String) -> Result<SessionResponse> =
            { Result.failure(Exception("unexpected call: refreshToken")) }

    override suspend fun loginWithCredentials(email: String, password: String) =
            onLoginWithCredentials(email, password)

    override suspend fun submitTwoFactor(challenge: String, code: String) =
            onSubmitTwoFactor(challenge, code)

    override suspend fun resendTwoFactor(challenge: String) = onResendTwoFactor(challenge)

    override suspend fun refreshToken(token: String) = onRefreshToken(token)
}

/**
 * Hand-written replacement for the old Mockito `IProvisioningClient` mock; same programmable-
 * lambda pattern as [FakeRelayHttpClient].
 */
class FakeProvisioningClient : IProvisioningClient {
    var onRegisterHardware: (String, String) -> Result<HardwareInfo> =
            { _, _ -> Result.failure(Exception("unexpected call: registerHardware")) }
    var onPollActivationStatus: (String, Int, Long) -> Result<ActivationStatus> =
            { _, _, _ -> Result.failure(Exception("unexpected call: pollActivationStatus")) }

    override suspend fun registerHardware(sessionToken: String, email: String) =
            onRegisterHardware(sessionToken, email)

    override suspend fun pollActivationStatus(
            deviceId: String,
            maxAttempts: Int,
            pollIntervalMs: Long,
    ) = onPollActivationStatus(deviceId, maxAttempts, pollIntervalMs)
}
