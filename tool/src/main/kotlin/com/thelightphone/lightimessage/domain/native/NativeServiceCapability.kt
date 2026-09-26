package com.thelightphone.lightimessage.domain.native

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository-side seam for the LightOS-owned native-service capability.
 *
 * The tool may connect through this seam once the SDK exposes the capability. It never owns the
 * rustpush process or its lifecycle. Until then, [UnavailableNativeServiceCapability] is the only
 * supported implementation.
 */
interface NativeServiceCapability {
    val state: StateFlow<NativeServiceCapabilityState>
    val client: INativeServiceClient?

    suspend fun connect(): Result<Unit>

    suspend fun disconnect(): Result<Unit>
}

sealed class NativeServiceCapabilityState {
    /** LightOS/SDK has not supplied the native-service capability. */
    data class Unavailable(val reason: String) : NativeServiceCapabilityState()

    /** The OS capability has supplied a client, but it is not connected yet. */
    object Disconnected : NativeServiceCapabilityState()

    /** The capability is establishing or owns an active connection. */
    object Connecting : NativeServiceCapabilityState()

    /** IPC transport is ready; this does not imply rustpush feature readiness. */
    object Connected : NativeServiceCapabilityState()

    data class Failed(val error: String) : NativeServiceCapabilityState()
}

/**
 * Safe placeholder used while the SDK/LightOS contract is unavailable.
 *
 * In particular, this implementation does not construct [NativeServiceClient], open a socket, or
 * start a process. It makes the deployment blocker observable to callers instead of presenting a
 * disconnected client as a supported runtime integration.
 */
class UnavailableNativeServiceCapability(
        reason: String = DEFAULT_REASON,
) : NativeServiceCapability {
    private val unavailable = NativeServiceCapabilityState.Unavailable(reason)
    private val _state = MutableStateFlow<NativeServiceCapabilityState>(unavailable)

    override val state: StateFlow<NativeServiceCapabilityState> = _state
    override val client: INativeServiceClient? = null

    override suspend fun connect(): Result<Unit> =
            Result.failure(
                    IOException((state.value as NativeServiceCapabilityState.Unavailable).reason)
            )

    override suspend fun disconnect(): Result<Unit> = Result.success(Unit)

    private companion object {
        const val DEFAULT_REASON =
                "LightOS native-service capability is not exposed by the current SDK"
    }
}
