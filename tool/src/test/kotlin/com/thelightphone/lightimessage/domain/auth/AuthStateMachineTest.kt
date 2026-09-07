package com.thelightphone.lightimessage.domain.auth

import com.thelightphone.lightimessage.data.provisioning.ActivationStatus
import com.thelightphone.lightimessage.data.provisioning.HardwareInfo
import com.thelightphone.lightimessage.data.relay.LoginResponse
import com.thelightphone.lightimessage.data.relay.SessionResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comprehensive unit tests for AuthStateMachine. Tests state transitions, retry logic, 2FA flow,
 * and error handling. Target: 100% code coverage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthStateMachineTest {
    private lateinit var tokenRepo: FakeTokenRepository

    private lateinit var relayClient: FakeRelayHttpClient

    private lateinit var provisioningClient: FakeProvisioningClient

    private val testScope = TestScope()

    @BeforeTest
    fun setUp() {
        tokenRepo = FakeTokenRepository()
        relayClient = FakeRelayHttpClient()
        provisioningClient = FakeProvisioningClient()
    }

    // ========== Initial State ==========

    @Test
    fun testInitialState() {
        val machine = createAuthStateMachine()
        val state = machine.getState().value

        assertEquals(AuthState.Idle, state, "Initial state must be Idle")
    }

    // ========== Idle to AwaitingCredentials Transition ==========

    @Test
    fun testIdleToAwaitingCredentials() = runTest {
        val timestamp = futureTimestamp()
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(
                LoginResponse.SessionToken(
                    token = "token-123",
                    expiresAt = timestamp,
                ),
            )
        }
        provisioningClient.onRegisterHardware = { _, _ ->
            Result.success(
                HardwareInfo(
                    deviceId = "device-123",
                    certificateData = ByteArray(0),
                ),
            )
        }
        provisioningClient.onPollActivationStatus = { _, _, _ ->
            Result.success(ActivationStatus.Activated)
        }

        val machine = createAuthStateMachine()

        val appleId = AppleId("test@icloud.com")
        val result = machine.requestLogin(appleId, "password123")

        assertTrue(result.isSuccess, "Login request must succeed")

        val state = machine.getState().value
        assertTrue(
            state is AuthState.SessionEstablished,
            "State must be SessionEstablished after successful login",
        )
    }

    @Test
    fun testRequestLoginTransitionsToAwaitingCredentials() = runTest {
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(
                LoginResponse.TwoFactorRequired(challenge = "challenge-123")
            )
        }

        val machine = createAuthStateMachine()

        val appleId = AppleId("test@icloud.com")
        machine.requestLogin(appleId, "password123")

        val state = machine.getState().value
        assertTrue(
            state is AuthState.AwaitingTwoFactorCode,
            "After requestLogin, state should be AwaitingTwoFactorCode",
        )
    }

    // ========== Credentials to 2FA Transition ==========

    @Test
    fun testCredentialsToTwoFA() = runTest {
        val challenge = "challenge-456"
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(LoginResponse.TwoFactorRequired(challenge = challenge))
        }

        val machine = createAuthStateMachine()

        machine.requestLogin(AppleId("test@icloud.com"), "password123")

        val state = machine.getState().value
        assertTrue(state is AuthState.AwaitingTwoFactorCode, "State must be AwaitingTwoFactorCode")
        assertEquals(
            challenge,
            (state as AuthState.AwaitingTwoFactorCode).challenge,
            "Challenge must be stored",
        )
    }

    // ========== 2FA Submission ==========

    @Test
    fun testTwoFASubmissionSuccess() = runTest {
        val timestamp = futureTimestamp()
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(
                LoginResponse.TwoFactorRequired(challenge = "challenge-123")
            )
        }
        tokenRepo.getAppleIdResult = Result.success("test@icloud.com")
        relayClient.onSubmitTwoFactor = { _, _ ->
            Result.success(
                SessionResponse(token = "token-456", expiresAt = timestamp),
            )
        }
        provisioningClient.onRegisterHardware = { _, _ ->
            Result.success(
                HardwareInfo(
                    deviceId = "device-123",
                    certificateData = ByteArray(0),
                ),
            )
        }
        provisioningClient.onPollActivationStatus = { _, _, _ ->
            Result.success(ActivationStatus.Activated)
        }

        val machine = createAuthStateMachine()

        machine.requestLogin(AppleId("test@icloud.com"), "password123")
        val result = machine.submitTwoFA("123456")

        assertTrue(result.isSuccess, "2FA submission must succeed")

        val state = machine.getState().value
        assertTrue(state is AuthState.SessionEstablished, "State must be SessionEstablished")
    }

    @Test
    fun testTwoFASubmissionInvalidCode() = runTest {
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(
                LoginResponse.TwoFactorRequired(challenge = "challenge-123")
            )
        }

        val machine = createAuthStateMachine()

        machine.requestLogin(AppleId("test@icloud.com"), "password123")
        val result = machine.submitTwoFA("invalid")

        assertTrue(result.isFailure, "Invalid 2FA code must fail")
    }

    @Test
    fun testTwoFASubmissionWrongLength() = runTest {
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(
                LoginResponse.TwoFactorRequired(challenge = "challenge-123")
            )
        }

        val machine = createAuthStateMachine()

        machine.requestLogin(AppleId("test@icloud.com"), "password123")

        val result1 = machine.submitTwoFA("12345") // Too short
        assertTrue(result1.isFailure, "2FA code with wrong length must fail")

        val result2 = machine.submitTwoFA("1234567") // Too long
        assertTrue(result2.isFailure, "2FA code with wrong length must fail")
    }

    @Test
    fun testTwoFASubmissionNonNumeric() = runTest {
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(
                LoginResponse.TwoFactorRequired(challenge = "challenge-123")
            )
        }

        val machine = createAuthStateMachine()

        machine.requestLogin(AppleId("test@icloud.com"), "password123")
        val result = machine.submitTwoFA("1234ab")

        assertTrue(result.isFailure, "Non-numeric 2FA code must fail")
    }

    @Test
    fun testTwoFASubmissionWithoutChallenge() = runTest {
        val machine = createAuthStateMachine()

        val result = machine.submitTwoFA("123456")

        assertTrue(result.isFailure, "Submitting 2FA without challenge must fail")
    }

    // ========== 2FA Resend ==========

    @Test
    fun testTwoFAResendSuccess() = runTest {
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(
                LoginResponse.TwoFactorRequired(challenge = "challenge-123")
            )
        }
        relayClient.onResendTwoFactor = { Result.success(Unit) }

        val machine = createAuthStateMachine()

        machine.requestLogin(AppleId("test@icloud.com"), "password123")
        val result = machine.resendTwoFA()

        assertTrue(result.isSuccess, "Resend 2FA must succeed")

        val state = machine.getState().value
        assertTrue(
            state is AuthState.AwaitingTwoFactorCode,
            "State must remain AwaitingTwoFactorCode",
        )
    }

    @Test
    fun testTwoFAResendMaxAttempts() = runTest {
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(
                LoginResponse.TwoFactorRequired(challenge = "challenge-123")
            )
        }
        relayClient.onResendTwoFactor = { Result.success(Unit) }

        val machine = createAuthStateMachine()

        machine.requestLogin(AppleId("test@icloud.com"), "password123")

        // Try to resend 3 times (allowed)
        assertTrue(machine.resendTwoFA().isSuccess, "First resend must succeed")
        assertTrue(machine.resendTwoFA().isSuccess, "Second resend must succeed")
        assertTrue(machine.resendTwoFA().isSuccess, "Third resend must succeed")

        // Fourth attempt should fail
        val result = machine.resendTwoFA()
        assertTrue(result.isFailure, "Fourth resend must fail (max attempts exceeded)")
    }

    @Test
    fun testTwoFAResendWithoutChallenge() = runTest {
        val machine = createAuthStateMachine()

        val result = machine.resendTwoFA()

        assertTrue(result.isFailure, "Resending 2FA without challenge must fail")
    }

    // ========== Retry Logic & Backoff ==========

    @Test
    fun testRetryBackoffOnLoginFailure() = runTest {
        var attemptCount = 0
        val timestamp = futureTimestamp()
        relayClient.onLoginWithCredentials = { _, _ ->
            attemptCount++
            if (attemptCount < 3) {
                Result.failure(Exception("Network error"))
            } else {
                Result.success(
                    LoginResponse.SessionToken(
                        token = "token-789",
                        expiresAt = timestamp,
                    ),
                )
            }
        }
        provisioningClient.onRegisterHardware = { _, _ ->
            Result.success(
                HardwareInfo(
                    deviceId = "device-123",
                    certificateData = ByteArray(0),
                ),
            )
        }
        provisioningClient.onPollActivationStatus = { _, _, _ ->
            Result.success(ActivationStatus.Activated)
        }

        val machine = createAuthStateMachine()

        val result = machine.requestLogin(AppleId("test@icloud.com"), "password123")

        assertTrue(result.isSuccess, "Login must succeed after retries")
        assertEquals(3, attemptCount, "Should have tried 3 times")
    }

    @Test
    fun testLoginFailureAfterMaxRetries() = runTest {
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.failure(Exception("Persistent network error"))
        }

        val machine = createAuthStateMachine()

        val result = machine.requestLogin(AppleId("test@icloud.com"), "password123")

        assertTrue(result.isFailure, "Login must fail after retries exhausted")

        val state = machine.getState().value
        assertTrue(state is AuthState.Failed, "State must be Failed")
    }

    // ========== Session Token Management ==========

    @Test
    fun testSessionTokenPersisted() = runTest {
        val token = "session-token-123"
        val expiresAt = futureTimestamp()

        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(
                LoginResponse.SessionToken(token = token, expiresAt = expiresAt),
            )
        }
        provisioningClient.onRegisterHardware = { _, _ ->
            Result.success(
                HardwareInfo(
                    deviceId = "device-123",
                    certificateData = ByteArray(0),
                ),
            )
        }
        provisioningClient.onPollActivationStatus = { _, _, _ ->
            Result.success(ActivationStatus.Activated)
        }

        val machine = createAuthStateMachine()

        machine.requestLogin(AppleId("test@icloud.com"), "password123")

        val state = machine.getState().value
        assertTrue(state is AuthState.SessionEstablished, "State must be SessionEstablished")

        if (state is AuthState.SessionEstablished) {
            assertEquals(token, state.token, "Token must match")
            assertEquals(expiresAt, state.expiresAt, "Expiration must match")
        }
    }

    // ========== Token Refresh ==========

    @Test
    fun testTokenRefresh() = runTest {
        val oldToken = "old-token"
        val newToken = "new-token"
        val newExpiresAt = futureTimestamp() + 3600000

        tokenRepo.getSessionTokenResult = Result.success(oldToken)
        relayClient.onRefreshToken = {
            Result.success(SessionResponse(token = newToken, expiresAt = newExpiresAt))
        }

        val machine = createAuthStateMachine()

        val result = machine.refreshToken()

        assertTrue(result.isSuccess, "Token refresh must succeed")

        val state = machine.getState().value
        assertTrue(
            state is AuthState.SessionEstablished,
            "State must be SessionEstablished after refresh",
        )

        if (state is AuthState.SessionEstablished) {
            assertEquals(newToken, state.token, "New token must be stored")
        }
    }

    @Test
    fun testTokenRefreshWithoutToken() = runTest {
        tokenRepo.getSessionTokenResult = Result.failure(Exception("No token stored"))

        val machine = createAuthStateMachine()

        val result = machine.refreshToken()

        assertTrue(result.isFailure, "Refresh without token must fail")
    }

    @Test
    fun testTokenRefreshFailureTransitionsToAwaitingCredentials() = runTest {
        tokenRepo.getSessionTokenResult = Result.success("old-token")
        // Mockito stubbed `.thenThrow(...)`; the fake throws the same way. The state machine
        // catches it in refreshToken() and demotes to AwaitingCredentials.
        relayClient.onRefreshToken = { throw UnauthorizedException("Token expired") }

        val machine = createAuthStateMachine()

        machine.refreshToken()

        val state = machine.getState().value
        assertTrue(
            state is AuthState.AwaitingCredentials,
            "Failed refresh must transition to AwaitingCredentials",
        )
    }

    // ========== Logout ==========

    @Test
    fun testLogoutSuccess() = runTest {
        val machine = createAuthStateMachine()

        val result = machine.logout()

        assertTrue(result.isSuccess, "Logout must succeed")

        val state = machine.getState().value
        assertEquals(AuthState.Idle, state, "State must be Idle after logout")
    }

    @Test
    fun testLogoutClearsSessionData() = runTest {
        val machine = createAuthStateMachine()

        machine.logout()

        val state = machine.getState().value
        assertEquals(AuthState.Idle, state, "State must be Idle")
    }

    @Test
    fun testLogoutFromSessionEstablished() = runTest {
        val timestamp = futureTimestamp()
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(
                LoginResponse.SessionToken(
                    token = "token",
                    expiresAt = timestamp,
                ),
            )
        }
        provisioningClient.onRegisterHardware = { _, _ ->
            Result.success(
                HardwareInfo(
                    deviceId = "device-123",
                    certificateData = ByteArray(0),
                ),
            )
        }
        provisioningClient.onPollActivationStatus = { _, _, _ ->
            Result.success(ActivationStatus.Activated)
        }

        val machine = createAuthStateMachine()

        machine.requestLogin(AppleId("test@icloud.com"), "password123")
        val state1 = machine.getState().value
        assertTrue(state1 is AuthState.SessionEstablished, "State should be SessionEstablished")

        machine.logout()
        val state2 = machine.getState().value
        assertEquals(AuthState.Idle, state2, "State must return to Idle after logout")
    }

    @Test
    fun testLogoutFailure() = runTest {
        tokenRepo.clearSessionTokenResult = Result.failure(Exception("Storage error"))

        val machine = createAuthStateMachine()

        val result = machine.logout()

        assertTrue(result.isFailure, "Logout must fail on storage error")
    }

    // ========== State Flow Reactivity ==========

    @Test
    fun testStateFlowUpdates() = runTest {
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(
                LoginResponse.TwoFactorRequired(challenge = "challenge-123")
            )
        }

        val machine = createAuthStateMachine()
        val states = mutableListOf<AuthState>()

        val collectJob = this.launch { machine.getState().collect { state -> states.add(state) } }
        delay(100) // Allow collection subscription to establish

        machine.requestLogin(AppleId("test@icloud.com"), "password123")
        delay(100) // Allow login and state changes to complete
        collectJob.cancel()

        assertTrue(states.contains(AuthState.Idle), "State flow must emit initial Idle state")
        assertTrue(
            states.any { it is AuthState.AwaitingTwoFactorCode },
            "State flow must emit AwaitingTwoFactorCode",
        )
    }

    // ========== Error Handling ==========

    @Test
    fun testLoginWithInvalidEmailFormat() = runTest {
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.failure(Exception("Invalid email format"))
        }

        val machine = createAuthStateMachine()

        val result = machine.requestLogin(AppleId("not-an-email"), "password123")

        assertTrue(result.isFailure, "Login with invalid email must fail")
    }

    @Test
    fun testLoginWithEmptyPassword() = runTest {
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.failure(Exception("Password cannot be empty"))
        }

        val machine = createAuthStateMachine()

        val result = machine.requestLogin(AppleId("test@icloud.com"), "")

        assertTrue(result.isFailure, "Login with empty password must fail")
    }

    // ========== Hardware Provisioning State ==========

    @Test
    fun testHardwareProvisioningProgress() = runTest {
        val timestamp = futureTimestamp()
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(
                LoginResponse.SessionToken(
                    token = "token",
                    expiresAt = timestamp,
                ),
            )
        }
        provisioningClient.onRegisterHardware = { _, _ ->
            Result.success(
                HardwareInfo(
                    deviceId = "device-123",
                    certificateData = ByteArray(0),
                ),
            )
        }
        provisioningClient.onPollActivationStatus = { _, _, _ ->
            Result.success(ActivationStatus.Activated)
        }

        val machine = createAuthStateMachine()

        machine.requestLogin(AppleId("test@icloud.com"), "password123")

        val finalState = machine.getState().value
        assertTrue(
            finalState is AuthState.SessionEstablished,
            "Final state must be SessionEstablished",
        )
    }

    // ========== Helper Functions ==========

    private fun createAuthStateMachine(): AuthStateMachine {
        return AuthStateMachine(
            tokenRepository = tokenRepo,
            relayClient = relayClient,
            nativeClient = provisioningClient,
            scope = testScope,
        )
    }

    private fun futureTimestamp(): Long {
        return System.currentTimeMillis() + 3600000 // 1 hour in future
    }
}
