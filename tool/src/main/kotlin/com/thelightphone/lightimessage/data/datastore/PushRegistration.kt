package com.thelightphone.lightimessage.data.datastore

/** UnifiedPush registration details used to authenticate inbound distributor messages. */
data class PushRegistration(
        val id: String,
        val distributorPackage: String,
        val endpointUrl: String,
        val instance: String,
        val token: String,
        val registeredAt: Long,
)
