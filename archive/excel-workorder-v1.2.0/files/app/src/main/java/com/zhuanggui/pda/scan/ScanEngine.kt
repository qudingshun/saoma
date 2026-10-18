package com.zhuanggui.pda.scan

import com.zhuanggui.pda.data.AppDatabase
import com.zhuanggui.pda.data.entity.ScanLog
import com.zhuanggui.pda.data.entity.ScanResult
import com.zhuanggui.pda.data.entity.WorkOrderItem

data class ScanOutcome(
    val result: ScanResult,
    val item: WorkOrderItem?,
    val message: String
)

class ScanEngine(private val database: AppDatabase) {

    fun normalize(barcode: String): String = barcode.trim()

    /**
     * 计数保存模式：不校验清单，直接记一条 OK 日志。
     * （返回 message 供 UI 展示）
     */
    suspend fun record(workOrderId: Long, rawBarcode: String): ScanOutcome {
        val barcode = normalize(rawBarcode)
        if (barcode.isEmpty()) {
            return ScanOutcome(ScanResult.OK, null, "空条码已忽略")
        }
        val itemDao = database.workOrderItemDao()
        // 若该条码恰好在清单里，同步明细进度，方便和校验模式看同一份数据
        val item = itemDao.findByBarcode(workOrderId, barcode)
        if (item != null) {
            itemDao.updateScannedQuantity(item.id, item.scannedQuantity + 1)
        }
        database.scanLogDao().insert(
            ScanLog(workOrderId = workOrderId, barcode = barcode, result = ScanResult.OK)
        )
        return ScanOutcome(ScanResult.OK, item, "已记录")
    }

    suspend fun process(workOrderId: Long, rawBarcode: String): ScanOutcome {
        val barcode = normalize(rawBarcode).trim() // 不强制小写，保持原始条码格式

        val itemDao = database.workOrderItemDao()
        val scanLogDao = database.scanLogDao()

        val item = itemDao.findByBarcode(workOrderId, barcode)

        val outcome = when {
            item == null -> {
                ScanOutcome(ScanResult.NOT_IN_LIST, null, "条码不在本柜清单内，禁止装入！")
            }
            item.scannedQuantity >= item.allowedQuantity -> {
                ScanOutcome(ScanResult.OVER_LIMIT, item, "数量超限！该条码已达允许数量上限")
            }
            else -> {
                itemDao.updateScannedQuantity(item.id, item.scannedQuantity + 1)
                ScanOutcome(ScanResult.OK, item, "扫码成功")
            }
        }

        scanLogDao.insert(
            ScanLog(workOrderId = workOrderId, barcode = barcode, result = outcome.result)
        )
        return outcome
    }
}
