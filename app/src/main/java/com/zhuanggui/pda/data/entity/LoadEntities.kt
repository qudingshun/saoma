package com.zhuanggui.pda.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * 装柜会话：一个柜号对应一条记录。
 * 同柜号 = 继续装柜（增量），换柜号 = 新会话。
 */
@Entity(
    tableName = "load_containers",
    indices = [Index(value = ["containerNo"], unique = true)]
)
data class LoadContainer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val containerNo: String,                 // 柜号（字母+数字）
    val totalQuantity: Int = 0,              // 本柜数量（0 = 不限）
    val createdAt: Date = Date(),
    val status: String = STATUS_ACTIVE       // ACTIVE / FINISHED
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_FINISHED = "FINISHED"
    }
}

/**
 * 条码规则：属于某个柜，前缀 = 条码中固定的部分（拼装柜可挂多条 = 多型号）。
 */
@Entity(
    tableName = "load_rules",
    foreignKeys = [
        ForeignKey(
            entity = LoadContainer::class,
            parentColumns = ["id"],
            childColumns = ["containerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("containerId")]
)
data class LoadRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val containerId: Long,
    val prefix: String                        // 流水号以外的固定部分（字母+数字）
)

/**
 * 扫码记录：每扫一码写一条（含串号/重复/超量，完整留痕），同时自动落盘 CSV。
 * fullCode = 柜号 + "，" + 条码（逗号拼接，按需求约定）。
 */
@Entity(
    tableName = "scan_records",
    foreignKeys = [
        ForeignKey(
            entity = LoadContainer::class,
            parentColumns = ["id"],
            childColumns = ["containerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("containerId")]
)
data class ScanRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val containerId: Long,
    val containerNo: String,
    val barcode: String,
    val fullCode: String,                     // 柜号，条码
    val result: ScanResult,
    val scannedAt: Date = Date()
)
