package com.thelightphone.lightimessage.data.provisioning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class ProvisioningHttpClientTest {
    @Test
    fun `register hardware maps the relay response`() = runTest {
        val client = clientFor { request ->
            assertEquals("POST", request.method)
            assertEquals("Bearer session-token", request.header("Authorization"))
            assertEquals("/provisioning/register-hardware", request.url.encodedPath)
            response(200, "{\"device_id\":\"device-1\",\"certificate_data\":\"cert\"}")
        }

        val result = client.registerHardware("session-token", "person@example.com")

        assertEquals(HardwareInfo("device-1", "cert".toByteArray()), result.getOrThrow())
    }

    @Test
    fun `activation status maps activated response`() = runTest {
        val client = clientFor { request ->
            assertEquals("GET", request.method)
            assertEquals("device-1", request.url.queryParameter("device_id"))
            response(200, "{\"status\":\"activated\"}")
        }

        assertEquals(
                ActivationStatus.Activated,
                client.pollActivationStatus("device-1", 1, 0).getOrThrow()
        )
    }

    @Test
    fun `http error is returned as typed failure`() = runTest {
        val client = clientFor { response(503, "temporarily unavailable") }

        val failure =
                client.registerHardware("session-token", "person@example.com").exceptionOrNull()

        assertIs<ProvisioningFailure.HttpError>(failure)
        assertEquals(503, failure.statusCode)
    }

    @Test
    fun `malformed response is returned as typed failure`() = runTest {
        val client = clientFor { response(200, "{\"device_id\":\"device-1\"}") }

        val failure =
                client.registerHardware("session-token", "person@example.com").exceptionOrNull()

        assertIs<ProvisioningFailure.MalformedResponse>(failure)
        assertTrue(failure.message!!.contains("registerHardware"))
    }

    private fun clientFor(handler: (okhttp3.Request) -> Response): ProvisioningHttpClient =
            ProvisioningHttpClient(
                    OkHttpClient.Builder()
                            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
                            .build(),
                    baseUrl = "https://relay.test",
            )

    private fun response(code: Int, body: String): Response =
            Response.Builder()
                    .request(okhttp3.Request.Builder().url("https://relay.test").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("test")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
}
