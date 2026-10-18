package com.zhuanggui.pda.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zhuanggui.pda.data.entity.WorkOrder
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkOrderDao {
    @Insert
    suspend fun insert(order: WorkOrder): Long

    @Update
    suspend fun update(order: WorkOrder)

    @Query("SELECT * FROM work_orders WHERE id = :id")
    suspend fun getById(id: Long): WorkOrder?

    @Query("SELECT * FROM work_orders ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WorkOrder>>

    @Query("SELECT * FROM work_orders WHERE id = :id")
    fun observeById(id: Long): Flow<WorkOrder?>

    @Query("SELECT * FROM work_orders ORDER BY createdAt DESC")
    suspend fun getAll(): List<WorkOrder>

    @Query("DELETE FROM work_orders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE work_orders SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)
}
