package com.zhuanggui.pda.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zhuanggui.pda.data.entity.WorkOrderItem
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkOrderItemDao {
    @Insert
    suspend fun insertAll(items: List<WorkOrderItem>)

    @Query("SELECT * FROM work_order_items WHERE workOrderId = :workOrderId ORDER BY barcode")
    fun observeByWorkOrder(workOrderId: Long): Flow<List<WorkOrderItem>>

    @Query("SELECT * FROM work_order_items WHERE workOrderId = :workOrderId AND barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(workOrderId: Long, barcode: String): WorkOrderItem?

    @Query("UPDATE work_order_items SET scannedQuantity = :scanned WHERE id = :id")
    suspend fun updateScannedQuantity(id: Long, scanned: Int)

    @Query("SELECT COUNT(*) FROM work_order_items WHERE workOrderId = :workOrderId")
    suspend fun countByWorkOrder(workOrderId: Long): Int

    @Query("SELECT * FROM work_order_items WHERE workOrderId = :workOrderId ORDER BY barcode")
    suspend fun getByWorkOrder(workOrderId: Long): List<WorkOrderItem>
}
