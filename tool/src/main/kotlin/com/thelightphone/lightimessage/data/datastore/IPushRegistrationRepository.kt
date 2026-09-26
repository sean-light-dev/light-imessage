package com.thelightphone.lightimessage.data.datastore

interface IPushRegistrationRepository {
    suspend fun saveRegistration(registration: PushRegistration): Result<Unit>
    suspend fun getRegistration(instance: String): PushRegistration?
    suspend fun deleteRegistration(instance: String): Result<Unit>
}
