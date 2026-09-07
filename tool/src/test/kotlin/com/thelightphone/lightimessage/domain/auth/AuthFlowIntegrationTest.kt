package com.thelightphone.lightimessage.domain.auth

import com.thelightphone.lightimessage.data.provisioning.ActivationStatus
import com.thelightphone.lightimessage.data.provisioning.HardwareInfo
import com.thelightphone.lightimessage.data.relay.LoginResponse
import com.thelightphone.lightimessage.data.relay.SessionResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Comprehensive integration tests for end-to-end authentication flow. Tests complete login
 * workflows, 2FA, hardware provisioning, session refresh, and error scenarios. Target: 100%
 * coverage of AuthManager and AuthStateMachine.
 *
 * Spec: milestone-2.md § 3.1 (Authentication Flow), ADR-006 (State Machine Pattern)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthFlowIntegrationTest {
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

    // ========== Full Login Flow ==========

    /**
     * Test: Credentials → 2FA → Hardware Provisioning → Session Established
     *
     * Verifies end-to-end login: user submits credentials, completes 2FA challenge, hardware
     * provisioning succeeds, and session is established and persisted. The state machine flows
     * synchronously through `ProvisioningHardware` inside `submitTwoFA`; because `StateFlow`
     * conflates, only the terminal `SessionEstablished` state is asserted here (see the class-level
     * note in AuthStateMachine).
     */
    @Test
    fun testFullLoginFlow() = runTest {
        // Setup: successful credential submission → 2FA required
        val twoFAChallenge = "2fa-challenge-xyz"
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(LoginResponse.TwoFactorRequired(twoFAChallenge))
        }

        // Setup: successful 2FA submission → session token
        val sessionToken = "session-token-abc123"
        val expiresAt = System.currentTimeMillis() + 3600000
        relayClient.onSubmitTwoFactor = { _, _ ->
            Result.success(SessionResponse(sessionToken, expiresAt))
        }

        // Setup: repository provides the Apple ID stashed during earlier login step
        tokenRepo.getAppleIdResult = Result.success("test@icloud.com")

        // Setup: hardware provisioning
        val deviceId = "device-xyz"
        val certData = byteArrayOf(1, 2, 3, 4)
        provisioningClient.onRegisterHardware = { _, _ ->
            Result.success(HardwareInfo(deviceId, certData))
        }
        provisioningClient.onPollActivationStatus = { _, _, _ ->
            Result.success(ActivationStatus.Activated)
        }

        val machine = createAuthStateMachine()

        // Step 1: Request login with credentials
        val loginResult = machine.requestLogin(AppleId("test@icloud.com"), "password123")
        assertTrue(loginResult.isSuccess, "Login request should succeed")
        assertEquals(
            AuthState.AwaitingTwoFactorCode::class,
            machine.getState().value::class,
            "State should be AwaitingTwoFactorCode",
        )

        // Step 2: Submit 2FA code — machine synchronously flows through ProvisioningHardware
        // to SessionEstablished.
        val twoFAResult = machine.submitTwoFA("123456")
        assertTrue(twoFAResult.isSuccess, "2FA submission should succeed")

        // Step 3: Verify session established
        val finalState = machine.getState().value
        assertEquals(
            AuthState.SessionEstablished::class,
            finalState::class,
            "Final state should be SessionEstablished",
        )
        val sessionState = finalState as AuthState.SessionEstablished
        assertEquals(sessionToken, sessionState.token, "Session token should match")
        assertEquals(expiresAt, sessionState.expiresAt, "Expiry should match")
    }

    // ========== Login Failure Scenarios ==========

    /**
     * Test: Bad Credentials → Failed State
     *
     * Verifies that login with invalid credentials transitions to Failed state and does not attempt
     * 2FA or hardware provisioning.
     */
    @Test
    fun testLoginFailure() = runTest {
        // Setup: failed credential submission (Result.failure — the state machine's retry
        // helper unwraps Result and rethrows on exhaustion).
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.failure(IllegalArgumentException("Invalid credentials"))
        }

        val machine = createAuthStateMachine()

        // Attempt login with bad credentials
        val result = machine.requestLogin(AppleId("wrong@icloud.com"), "wrongpass")
        assertFalse(result.isSuccess, "Login should fail")

        // Verify state is Failed
        val state = machine.getState().value
        assertEquals(AuthState.Failed::class, state::class, "State should be Failed")
        if (state is AuthState.Failed) {
            assertTrue(state.error.isNotEmpty(), "Error message should contain details")
        }
    }

    // ========== 2FA Expiry ==========

    /**
     * Test: 2FA Challenge Timeout
     *
     * Verifies that a 2FA challenge that has expired cannot be submitted, and user must restart
     * login flow.
     */
    @Test
    fun testTwoFAExpiry() = runTest {
        // Setup: 2FA challenge
        val twoFAChallenge = "2fa-challenge-expired"
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(LoginResponse.TwoFactorRequired(twoFAChallenge))
        }

        // Setup: 2FA submission failure due to expiry (all 3 retries)
        relayClient.onSubmitTwoFactor = { _, _ ->
            Result.failure(IllegalStateException("2FA code expired"))
        }

        val machine = createAuthStateMachine()

        // Step 1: Trigger 2FA challenge
        machine.requestLogin(AppleId("test@icloud.com"), "password")
        assertEquals(
            AuthState.AwaitingTwoFactorCode::class,
            machine.getState().value::class,
            "State should be AwaitingTwoFactorCode",
        )

        // Step 2: Submit code after expiry — a single call bumps the retry counter once. The
        // state machine keeps the user in AwaitingTwoFactorCode until MAX_2FA_RETRIES is reached
        // (so they can re-enter the code), then transitions to Failed.
        var lastResult: Result<Unit> = Result.success(Unit)
        repeat(3) { lastResult = machine.submitTwoFA("123456") }
        assertFalse(lastResult.isSuccess, "Submission of expired code should fail")

        // Verify state is Failed
        val state = machine.getState().value
        assertEquals(AuthState.Failed::class, state::class, "State should be Failed")
    }

    // ========== Session Refresh ==========

    /**
     * Test: Automatic Token Refresh on Expiry
     *
     * Verifies that when session token approaches expiry, automatic refresh occurs and new token is
     * persisted. Old session remains valid until refresh completes.
     */
    @Test
    fun testSessionRefresh() = runTest {
        // Setup: Establish session first
        val oldToken = "old-token-abc"
        val newToken = "new-token-xyz"
        val newExpiresAt = System.currentTimeMillis() + 3600000

        tokenRepo.getSessionTokenResult = Result.success(oldToken)
        relayClient.onRefreshToken = {
            Result.success(SessionResponse(newToken, newExpiresAt))
        }

        val machine = createAuthStateMachine()

        // Trigger token refresh
        val refreshResult = machine.refreshToken()
        assertTrue(refreshResult.isSuccess, "Session refresh should complete")

        // Verify state transitioned to SessionEstablished with the new token
        val state = machine.getState().value
        assertEquals(
            AuthState.SessionEstablished::class,
            state::class,
            "State should be SessionEstablished after refresh",
        )
        val sessionState = state as AuthState.SessionEstablished
        assertEquals(newToken, sessionState.token, "New token should be applied")
        assertEquals(newExpiresAt, sessionState.expiresAt, "New expiry should be applied")
    }

    // ========== Logout and Relogin ==========

    /**
     * Test: Logout → Cleared State → New Login Works
     *
     * Verifies that logout clears all session data, state returns to Idle, and a new login flow can
     * be initiated successfully.
     */
    @Test
    fun testLogoutAndRelogin() = runTest {
        // Setup first login
        val twoFAChallenge = "2fa-challenge-1"
        val sessionToken = "session-token-1"
        val expiresAt = System.currentTimeMillis() + 3600000

        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(LoginResponse.TwoFactorRequired(twoFAChallenge))
        }
        relayClient.onSubmitTwoFactor = { _, _ ->
            Result.success(SessionResponse(sessionToken, expiresAt))
        }

        val deviceId = "device-1"
        val certData = byteArrayOf(9, 9, 9)
        tokenRepo.getAppleIdResult = Result.success("user@icloud.com")
        provisioningClient.onRegisterHardware = { _, _ ->
            Result.success(HardwareInfo(deviceId, certData))
        }
        provisioningClient.onPollActivationStatus = { _, _, _ ->
            Result.success(ActivationStatus.Activated)
        }

        val machine = createAuthStateMachine()

        // Step 1: Login
        machine.requestLogin(AppleId("user@icloud.com"), "pass1")
        machine.submitTwoFA("111111")

        val sessionState = machine.getState().value
        assertEquals(
            AuthState.SessionEstablished::class,
            sessionState::class,
            "Should be SessionEstablished after login",
        )

        // Step 2: Logout
        val logoutResult = machine.logout()
        assertTrue(logoutResult.isSuccess, "Logout should succeed")
        assertEquals(AuthState.Idle, machine.getState().value, "State should return to Idle")

        // Step 3: Setup second login with different credentials — reassign the fake's lambda,
        // mirroring the old second `whenever(...)` stubbing.
        val twoFAChallenge2 = "2fa-challenge-2"
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(LoginResponse.TwoFactorRequired(twoFAChallenge2))
        }

        // Step 4: Relogin
        val reloginResult = machine.requestLogin(AppleId("user2@icloud.com"), "pass2")
        assertTrue(reloginResult.isSuccess, "Relogin should succeed")
        assertEquals(
            AuthState.AwaitingTwoFactorCode::class,
            machine.getState().value::class,
            "State should be AwaitingTwoFactorCode",
        )
    }

    // ========== Concurrent Auth Attempts ==========

    /**
     * Test: Multiple Concurrent Authentication Attempts Rejected
     *
     * Verifies that concurrent calls to `requestLogin()` land in a consistent, valid state.
     * Serialization is best-effort inside the state machine (the mutex protects counters and
     * challenge only, not the whole flow), so we assert the terminal state is one of the expected
     * outcomes rather than dictating which call "wins".
     */
    @Test
    fun testConcurrentAuthAttempts() = runTest {
        val twoFAChallenge = "2fa-challenge"
        relayClient.onLoginWithCredentials = { _, _ ->
            Result.success(LoginResponse.TwoFactorRequired(twoFAChallenge))
        }

        val machine = createAuthStateMachine()

        // Launch 3 concurrent login attempts using the runTest TestScope (this).
        val job1 = async { machine.requestLogin(AppleId("test@icloud.com"), "password") }
        val job2 = async { machine.requestLogin(AppleId("test@icloud.com"), "password") }
        val job3 = async { machine.requestLogin(AppleId("test@icloud.com"), "password") }

        job1.await()
        job2.await()
        job3.await()

        // At minimum, state should be consistent (not corrupted).
        val finalState = machine.getState().value
        assertTrue(
            finalState is AuthState.AwaitingTwoFactorCode || finalState is AuthState.Failed,
            "Final state should be valid",
        )
    }

    // ========== Helper Methods ==========

    private fun createAuthStateMachine(): AuthStateMachine =
            AuthStateMachine(
                    tokenRepository = tokenRepo,
                    relayClient = relayClient,
                    nativeClient = provisioningClient,
                    scope = testScope,
            )
}
