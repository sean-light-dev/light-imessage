package com.thelightphone.lightimessage.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.thelightphone.lightimessage.data.entity.IncomingPushEntity

@Dao
interface PushDao {
    /** Returns -1 when the push id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(push: IncomingPushEntity): Long

    @Query("SELECT * FROM incoming_pushes WHERE processed = 0 ORDER BY receivedAt ASC")
    suspend fun getUnprocessed(): List<IncomingPushEntity>

    @Query("UPDATE incoming_pushes SET processed = 1, failureReason = NULL WHERE id = :id")
    suspend fun markProcessed(id: String): Int

    @Query(
            "UPDATE incoming_pushes SET processed = 0, failureReason = :failureReason WHERE id = :id"
    )
    suspend fun markFailed(id: String, failureReason: String): Int
}
