package com.zhuanggui.pda.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zhuanggui.pda.data.entity.LoadContainer
import com.zhuanggui.pda.data.entity.LoadRule
import com.zhuanggui.pda.data.entity.ScanRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface LoadContainerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(container: LoadContainer): Long

    @Update
    suspend fun update(container: LoadContainer)

    @Query("SELECT * FROM load_containers WHERE containerNo = :no LIMIT 1")
    suspend fun findByNo(no: String): LoadContainer?

    @Query("SELECT * FROM load_containers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<LoadContainer>>
}

@Dao
interface LoadRuleDao {
    @Insert
    suspend fun insert(rule: LoadRule): Long

    @Query("SELECT * FROM load_rules WHERE containerId = :containerId ORDER BY id")
    suspend fun getByContainer(containerId: Long): List<LoadRule>

    @Query("DELETE FROM load_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ScanRecordDao {
    @Insert
    suspend fun insert(record: ScanRecord): Long

    /** 全部记录，新的在前（扫码记录页） */
    @Query("SELECT * FROM scan_records ORDER BY scannedAt DESC")
    fun observeAll(): Flow<List<ScanRecord>>

    /** 当前柜最近 N 条（扫码页滚动显示） */
    @Query("SELECT * FROM scan_records WHERE containerId = :containerId ORDER BY scannedAt DESC, id DESC LIMIT :limit")
    fun observeByContainer(containerId: Long, limit: Int): Flow<List<ScanRecord>>

    @Query("SELECT COUNT(*) FROM scan_records WHERE containerId = :containerId AND barcode = :barcode")
    suspend fun countBarcode(containerId: Long, barcode: String): Int

    @Query("SELECT COUNT(*) FROM scan_records WHERE containerId = :containerId AND result = :result")
    suspend fun countByResult(containerId: Long, result: String): Int
}
