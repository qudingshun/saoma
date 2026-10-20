package com.zhuanggui.pda.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zhuanggui.pda.data.entity.SyncInbox
import com.zhuanggui.pda.data.entity.SyncState
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface SyncStateDao {
    @Query("SELECT v FROM sync_state WHERE k = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM sync_state")
    suspend fun getAll(): List<SyncState>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncState)

    @Query("DELETE FROM sync_state WHERE k = :key")
    suspend fun delete(key: String)
}

@Dao
interface SyncInboxDao {
    @Query("SELECT * FROM sync_inbox ORDER BY receivedAt DESC, id DESC")
    fun observeAll(): Flow<List<SyncInbox>>

    @Query("SELECT * FROM sync_inbox ORDER BY receivedAt DESC, id DESC")
    suspend fun getAll(): List<SyncInbox>

    @Query("SELECT * FROM sync_inbox WHERE id = :id")
    suspend fun findById(id: Long): SyncInbox?

    /** 同一柜号覆盖（REPLACE），保留最新任务内容 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(inbox: SyncInbox): Long

    @Query("DELETE FROM sync_inbox WHERE containerNo = :containerNo")
    suspend fun deleteByNo(containerNo: String)

    @Query("UPDATE sync_inbox SET consumedAt = :at WHERE id = :id")
    suspend fun markConsumed(id: Long, at: Date)

    @Query("SELECT COUNT(*) FROM sync_inbox WHERE consumedAt IS NULL")
    fun observePendingCount(): Flow<Int>
}
