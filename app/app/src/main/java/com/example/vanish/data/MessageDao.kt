package com.example.vanish.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    // Stamped messages first (by relay time), unsent ones last (by creation time).
    @Query("SELECT * FROM messages ORDER BY (sent_at = 0), sent_at, created_at")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE state = 'WAITING' ORDER BY created_at")
    suspend fun waiting(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(seen: SeenId): Long

    @Query("SELECT COUNT(*) FROM seen_ids WHERE id = :id")
    suspend fun seenCount(id: String): Int

    /** Relay acknowledged our message. Only a WAITING message moves to SENT (never backwards). */
    @Query("UPDATE messages SET sent_at = :sentAt, expires_at = :expiresAt, state = 'SENT' WHERE id = :id AND state = 'WAITING'")
    suspend fun stamp(id: String, sentAt: Long, expiresAt: Long): Int

    @Query("UPDATE messages SET state = 'DELIVERED' WHERE id = :id AND mine = 1 AND state IN ('WAITING', 'SENT')")
    suspend fun markMineDelivered(id: String): Int

    @Query("UPDATE messages SET state = 'DISPLAYED' WHERE id = :id AND mine = 0 AND state = 'DELIVERED'")
    suspend fun markDisplayed(id: String): Int

    /** Give up on a queued message: remove its text now and let it vanish after a short notice. */
    @Query("UPDATE messages SET text = '', state = 'DROPPED', expires_at = :noticeUntil WHERE id = :id AND state = 'WAITING'")
    suspend fun drop(id: String, noticeUntil: Long): Int

    // ---- deletion. Always wipe the text first, then delete the row. ----
    @Query("UPDATE messages SET text = '' WHERE id = :id")
    suspend fun wipe(id: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE messages SET text = '' WHERE expires_at > 0 AND expires_at <= :now")
    suspend fun wipeExpired(now: Long)

    @Query("DELETE FROM messages WHERE expires_at > 0 AND expires_at <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("DELETE FROM seen_ids WHERE forget_at <= :now")
    suspend fun forgetOld(now: Long)

    @Query("UPDATE messages SET text = ''")
    suspend fun wipeAll()

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("DELETE FROM seen_ids")
    suspend fun deleteAllSeen()
}
