package com.thelightphone.lightimessage.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.thelightphone.lightimessage.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for message operations. Provides query and mutation operations on the messages
 * table. All Flow-based queries return reactive updates. Spec: milestone-2.md § 2 (Data Model).
 */
@Dao
interface MessageDao {
    @Insert suspend fun insert(message: MessageEntity)

    @Update suspend fun update(message: MessageEntity)

    @Delete suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId") suspend fun deleteById(messageId: String)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    fun getById(messageId: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getByIdOnce(messageId: String): MessageEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :messageId)")
    suspend fun existsById(messageId: String): Boolean

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp DESC")
    fun getByThreadId(threadId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC") fun getAll(): Flow<List<MessageEntity>>

    /**
     * Outgoing messages eligible for send retry: not yet relay-acked (2=SENT) and not in a
     * success-terminal state (3=DELIVERED, 4=READ). Without the `isOutgoing` filter this query
     * also matched *incoming* rows (status 0/3/4), and the background sync job would "resend"
     * a received message back to its sender.
     */
    @Query("SELECT * FROM messages WHERE isOutgoing = 1 AND status NOT IN (2, 3, 4) ORDER BY timestamp ASC")
    fun getUndelivered(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE readReceiptAt IS NULL ORDER BY timestamp ASC")
    fun getUnread(): Flow<List<MessageEntity>>

    @Query(
            "UPDATE messages SET status = 2, deliveryReceiptAt = :deliveryReceiptAt WHERE id = :messageId",
    )
    suspend fun markDelivered(
            messageId: String,
            deliveryReceiptAt: Long,
    )

    @Query("UPDATE messages SET readReceiptAt = :readReceiptAt WHERE id = :messageId")
    suspend fun markRead(
            messageId: String,
            readReceiptAt: Long,
    )
}
