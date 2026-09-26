package com.thelightphone.lightimessage.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable receipt and processing outcome for a push delivered by UnifiedPush. */
@Entity(tableName = "incoming_pushes")
data class IncomingPushEntity(
        @PrimaryKey val id: String,
        val type: String,
        val payloadBase64: String,
        val distributorInstance: String,
        val receivedAt: Long,
        val processed: Boolean = false,
        val failureReason: String? = null,
)
