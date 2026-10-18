package com.zhuanggui.pda.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zhuanggui.pda.data.entity.ScanLog
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanLogDao {
    @Insert
    suspend fun insert(log: ScanLog)

    @Query("SELECT * FROM scan_logs WHERE workOrderId = :workOrderId ORDER BY scannedAt DESC")
    fun observeByWorkOrder(workOrderId: Long): Flow<List<ScanLog>>

    @Query("SELECT * FROM scan_logs WHERE workOrderId = :workOrderId ORDER BY scannedAt DESC")
    suspend fun getByWorkOrder(workOrderId: Long): List<ScanLog>

    @Query("SELECT COUNT(*) FROM scan_logs WHERE workOrderId = :workOrderId")
    suspend fun countByWorkOrder(workOrderId: Long): Int

    @Query("SELECT * FROM scan_logs ORDER BY scannedAt DESC")
    suspend fun getAll(): List<ScanLog>
}
