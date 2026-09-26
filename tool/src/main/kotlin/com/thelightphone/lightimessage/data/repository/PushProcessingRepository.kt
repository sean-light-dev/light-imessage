package com.thelightphone.lightimessage.data.repository

import com.thelightphone.lightimessage.data.dao.PushDao
import com.thelightphone.lightimessage.data.entity.IncomingPushEntity

class PushProcessingRepository(private val pushDao: PushDao) : IPushProcessingRepository {
    override suspend fun recordPush(push: IncomingPushEntity): Result<Unit> = runCatching {
        pushDao.insert(push)
        Unit
    }

    override suspend fun markProcessed(pushId: String): Result<Unit> = runCatching {
        check(pushDao.markProcessed(pushId) == 1) { "Push receipt not found: $pushId" }
    }

    override suspend fun markFailed(pushId: String, failureReason: String): Result<Unit> =
            runCatching {
                check(pushDao.markFailed(pushId, failureReason) == 1) {
                    "Push receipt not found: $pushId"
                }
            }

    override suspend fun getUnprocessed(): List<IncomingPushEntity> = pushDao.getUnprocessed()
}
