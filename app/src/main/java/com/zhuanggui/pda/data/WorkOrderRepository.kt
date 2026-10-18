package com.zhuanggui.pda.data

import com.zhuanggui.pda.data.dao.ScanLogDao
import com.zhuanggui.pda.data.dao.WorkOrderDao
import com.zhuanggui.pda.data.dao.WorkOrderItemDao
import com.zhuanggui.pda.data.entity.ScanLog
import com.zhuanggui.pda.data.entity.ScanResult
import com.zhuanggui.pda.data.entity.WorkOrder
import com.zhuanggui.pda.data.entity.WorkOrderItem
import kotlinx.coroutines.flow.Flow

class WorkOrderRepository(
    private val workOrderDao: WorkOrderDao,
    private val itemDao: WorkOrderItemDao,
    private val scanLogDao: ScanLogDao
) {
    constructor(database: AppDatabase) : this(
        database.workOrderDao(),
        database.workOrderItemDao(),
        database.scanLogDao()
    )

    suspend fun createWorkOrder(name: String, containerNo: String): Long =
        workOrderDao.insert(WorkOrder(name = name, containerNo = containerNo))

    suspend fun addItems(workOrderId: Long, items: List<Pair<String, Int>>) {
        val entities = items.map { (barcode, qty) ->
            WorkOrderItem(workOrderId = workOrderId, barcode = barcode, allowedQuantity = qty)
        }
        itemDao.insertAll(entities)
    }

    suspend fun updateStatus(id: Long, status: String) = workOrderDao.updateStatus(id, status)

    suspend fun updateWorkOrder(order: WorkOrder) = workOrderDao.update(order)

    suspend fun deleteWorkOrder(id: Long) = workOrderDao.deleteById(id)

    fun observeWorkOrders(): Flow<List<WorkOrder>> = workOrderDao.observeAll()

    fun observeItems(workOrderId: Long): Flow<List<WorkOrderItem>> =
        itemDao.observeByWorkOrder(workOrderId)

    fun observeScanLogs(workOrderId: Long): Flow<List<ScanLog>> =
        scanLogDao.observeByWorkOrder(workOrderId)

    suspend fun getItemsSnapshot(workOrderId: Long): List<WorkOrderItem> =
        itemDao.getByWorkOrder(workOrderId)

    suspend fun workOrdersList(): List<WorkOrder> = workOrderDao.getAll()

    suspend fun getOrder(id: Long): WorkOrder? = workOrderDao.getById(id)

    suspend fun scanLogsSnapshot(): List<ScanLog> = scanLogDao.getAll()
}
