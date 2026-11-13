package com.example.vanish.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "messages", indices = [Index("expires_at")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val sender: String,
    val text: String,
    /** Relay time (ms). 0 until the relay has stamped the message. */
    @ColumnInfo(name = "sent_at") val sentAt: Long,
    /** Relay time (ms). Stored as an absolute time, never as a "seconds left" counter. 0 = not stamped yet. */
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    val mine: Boolean,
    val state: String,
    /** Phone wall clock (ms). Only used to measure how long a message waited in the offline queue. */
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/** An id we have already processed. Holds no text, only the id and when to forget it. */
@Entity(tableName = "seen_ids")
data class SeenId(
    @PrimaryKey val id: String,
    /** Relay time (ms). */
    @ColumnInfo(name = "forget_at") val forgetAt: Long
)
