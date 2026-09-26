package com.thelightphone.lightimessage.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the durable inbound-push receipt table introduced in schema version 2. */
val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                        """
            CREATE TABLE IF NOT EXISTS incoming_pushes (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                payloadBase64 TEXT NOT NULL,
                distributorInstance TEXT NOT NULL,
                receivedAt INTEGER NOT NULL,
                processed INTEGER NOT NULL DEFAULT 0,
                failureReason TEXT
            )
            """.trimIndent(),
                )
            }
        }
