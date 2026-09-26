package com.thelightphone.sdk.shared

import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
data class LightServerPushCredentials(
        val pushEndpoint: String,
        val pushRegistrationDate: Instant,
        val instance: String = LightConstants.PUSH_INSTANCE_REMOTE,
        val token: String? = null,
        val distributorPackage: String? = null,
)

data class LightServerData(val pushCredentials: LightServerPushCredentials?)
