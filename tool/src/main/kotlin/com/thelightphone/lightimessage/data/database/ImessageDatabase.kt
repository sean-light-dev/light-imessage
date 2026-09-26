package com.thelightphone.lightimessage.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.thelightphone.lightimessage.data.dao.AttachmentDao
import com.thelightphone.lightimessage.data.dao.ContactDao
import com.thelightphone.lightimessage.data.dao.DomainEventDao
import com.thelightphone.lightimessage.data.dao.MessageDao
import com.thelightphone.lightimessage.data.dao.PushDao
import com.thelightphone.lightimessage.data.dao.ThreadDao
import com.thelightphone.lightimessage.data.entity.AttachmentEntity
import com.thelightphone.lightimessage.data.entity.ContactEntity
import com.thelightphone.lightimessage.data.entity.DomainEventEntity
import com.thelightphone.lightimessage.data.entity.IncomingPushEntity
import com.thelightphone.lightimessage.data.entity.MessageEntity
import com.thelightphone.lightimessage.data.entity.ThreadEntity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase

/**
 * Room database for iMessage cache. Single canonical database for all persistent state.
 *
 * Spec: milestone-2.md § 2 (Data Model); ADR-006 (Room and DataStore).
 *
 * Schema version 2 adds durable inbound-push receipts. Migrations are registered in [getInstance]
 * because the SDK owns the Room builder.
 */
@Database(
        entities =
                [
                        MessageEntity::class,
                        ThreadEntity::class,
                        ContactEntity::class,
                        AttachmentEntity::class,
                        DomainEventEntity::class,
                        IncomingPushEntity::class,
                ],
        version = 2,
        exportSchema = false,
)
abstract class ImessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun pushDao(): PushDao

    abstract fun threadDao(): ThreadDao

    abstract fun contactDao(): ContactDao

    abstract fun attachmentDao(): AttachmentDao

    abstract fun domainEventDao(): DomainEventDao

    companion object {
        private const val DB_NAME = "imessage.db"

        @Volatile private var INSTANCE: ImessageDatabase? = null

        /**
         * Get (or create) the singleton database. The Light SDK sandbox does not expose a raw
         * `Context` to tool code, so the database is built from the [SealedLightContext] handed to
         * screens and background jobs.
         */
        fun getInstance(lightContext: SealedLightContext): ImessageDatabase =
                INSTANCE
                        ?: synchronized(this) {
                            INSTANCE
                                    ?: lightContext.buildDatabase(
                                                    ImessageDatabase::class.java,
                                                    DB_NAME,
                                                    MIGRATION_1_2
                                            )
                                            .also { INSTANCE = it }
                        }
    }
}
