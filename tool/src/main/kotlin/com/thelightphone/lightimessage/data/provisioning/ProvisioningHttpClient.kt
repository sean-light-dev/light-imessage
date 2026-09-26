package com.thelightphone.lightimessage.data.provisioning

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Typed failures returned by the provisioning transport. */
sealed class ProvisioningFailure(message: String, cause: Throwable? = null) :
        IOException(message, cause) {
    class HttpError(
            val operation: String,
            val statusCode: Int,
    ) : ProvisioningFailure("$operation failed with HTTP $statusCode")

    class MalformedResponse(
            val operation: String,
            cause: Throwable,
    ) : ProvisioningFailure("Malformed $operation response", cause)
}

/** OkHttp implementation of the relay's one-time hardware provisioning API. */
class ProvisioningHttpClient(
        private val okHttpClient: OkHttpClient,
        baseUrl: String = DEFAULT_BASE_URL,
) : IProvisioningClient {
    private val baseUrl = baseUrl.trimEnd('/').toHttpUrl()

    override suspend fun registerHardware(
            sessionToken: String,
            email: String,
    ): Result<HardwareInfo> =
            runCatching {
                val body = RegisterHardwareRequest(email)
                val request =
                        Request.Builder()
                                .url(
                                        baseUrl.newBuilder()
                                                .addPathSegments("provisioning/register-hardware")
                                                .build()
                                )
                                .header("Authorization", "Bearer $sessionToken")
                                .post(
                                        Json.encodeToString(
                                                        RegisterHardwareRequest.serializer(),
                                                        body
                                                )
                                                .toRequestBody(JSON_MEDIA_TYPE)
                                )
                                .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw ProvisioningFailure.HttpError(
                                operation = "registerHardware",
                                statusCode = response.code,
                        )
                    }

                    val responseBody = response.body.string()
                    val result =
                            decode(
                                    "registerHardware",
                                    responseBody,
                                    RegisterHardwareResponse.serializer()
                            )
                    if (result.deviceId.isBlank() || result.certificateData.isBlank()) {
                        throw malformed("registerHardware", "required fields are blank")
                    }
                    HardwareInfo(result.deviceId, result.certificateData.toByteArray())
                }
            }
                    .onFailure { Log.e(TAG, "registerHardware failed", it) }

    override suspend fun pollActivationStatus(
            deviceId: String,
            maxAttempts: Int,
            pollIntervalMs: Long,
    ): Result<ActivationStatus> =
            runCatching {
                require(maxAttempts > 0) { "maxAttempts must be positive" }
                require(pollIntervalMs >= 0) { "pollIntervalMs must not be negative" }

                var lastStatus: ActivationStatus = ActivationStatus.Pending
                repeat(maxAttempts) { attempt ->
                    val request =
                            Request.Builder()
                                    .url(
                                            baseUrl.newBuilder()
                                                    .addPathSegments(
                                                            "provisioning/activation-status"
                                                    )
                                                    .addQueryParameter("device_id", deviceId)
                                                    .build()
                                    )
                                    .get()
                                    .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw ProvisioningFailure.HttpError(
                                    operation = "pollActivationStatus",
                                    statusCode = response.code,
                            )
                        }

                        val responseBody = response.body.string()
                        val result =
                                decode(
                                        "pollActivationStatus",
                                        responseBody,
                                        ActivationStatusResponse.serializer()
                                )
                        lastStatus =
                                when (result.status) {
                                    "activated" -> return@runCatching ActivationStatus.Activated
                                    "pending" -> ActivationStatus.Pending
                                    "failed" ->
                                            ActivationStatus.Failed(
                                                    result.reason ?: "Unknown failure"
                                            )
                                    else ->
                                            throw malformed(
                                                    "pollActivationStatus",
                                                    "unknown status '${result.status}'"
                                            )
                                }
                        if (lastStatus is ActivationStatus.Failed) return@runCatching lastStatus
                    }

                    if (attempt < maxAttempts - 1) delay(pollIntervalMs)
                }
                lastStatus
            }
                    .onFailure { Log.e(TAG, "pollActivationStatus failed", it) }

    private fun <T> decode(
            operation: String,
            body: String,
            deserializer: DeserializationStrategy<T>,
    ): T =
            try {
                Json.decodeFromString(deserializer, body)
            } catch (e: SerializationException) {
                throw ProvisioningFailure.MalformedResponse(operation, e)
            } catch (e: IllegalArgumentException) {
                throw ProvisioningFailure.MalformedResponse(operation, e)
            }

    private fun malformed(operation: String, reason: String) =
            ProvisioningFailure.MalformedResponse(operation, IllegalArgumentException(reason))

    private companion object {
        const val TAG = "ProvisioningHttpClient"
        const val DEFAULT_BASE_URL = "https://relay.apple.com"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

@Serializable private data class RegisterHardwareRequest(val email: String)

@Serializable
private data class RegisterHardwareResponse(
        @SerialName("device_id") val deviceId: String,
        @SerialName("certificate_data") val certificateData: String,
)

@Serializable
private data class ActivationStatusResponse(
        val status: String,
        val reason: String? = null,
)
