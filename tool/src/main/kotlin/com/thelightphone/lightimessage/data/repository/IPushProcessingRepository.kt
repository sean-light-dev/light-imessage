package com.thelightphone.lightimessage.data.repository

import com.thelightphone.lightimessage.data.entity.IncomingPushEntity

interface IPushProcessingRepository {
    suspend fun recordPush(push: IncomingPushEntity): Result<Unit>
    suspend fun markProcessed(pushId: String): Result<Unit>
    suspend fun markFailed(pushId: String, failureReason: String): Result<Unit>
    suspend fun getUnprocessed(): List<IncomingPushEntity>
}
