package com.thelightphone.lightimessage.domain.native

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class NativeServiceCapabilityTest {
    @Test
    fun unavailableCapabilityExposesBlockerWithoutAClient() = runTest {
        val capability = UnavailableNativeServiceCapability("SDK contract not available")

        assertEquals(
                NativeServiceCapabilityState.Unavailable("SDK contract not available"),
                capability.state.value,
        )
        assertNull(capability.client)

        val result = capability.connect()

        assertTrue(result.isFailure)
        assertEquals("SDK contract not available", result.exceptionOrNull()?.message)
        assertIs<NativeServiceCapabilityState.Unavailable>(capability.state.value)
    }

    @Test
    fun disconnectIsSafeBeforeCapabilityExists() = runTest {
        val capability = UnavailableNativeServiceCapability()

        assertTrue(capability.disconnect().isSuccess)
        assertIs<NativeServiceCapabilityState.Unavailable>(capability.state.value)
    }
}
