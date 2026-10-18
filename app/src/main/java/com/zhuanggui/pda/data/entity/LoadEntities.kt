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
    val prefix: String,                       // 流水号以外的固定部分（字母+数字）
    val allowDuplicate: Boolean = false,      // 允许重复扫（双码机固定码：同码反复扫不报“重复”）
    val countEnabled: Boolean = true,         // 是否计入本柜统计（不勾选 = 辅助码，只留痕不计数）
    val checkHistory: Boolean = false         // 查历史串号：条码若在其他柜记录里出现过 → 报串号（防混柜）
)

/**
 * 扫码记录：每扫一码写一条（含串号/重复/超量/辅助码，完整留痕），同时自动落盘 TSV 文件。
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
    val scannedAt: Date = Date(),
    val deletedAt: Date? = null               // 软删除时间（进回收站）；null = 有效记录
)

/**
 * 规则库（规则源）：全局共享的条码前缀库。
 *  - 规则源导入：从 .xlsx/.xls/.csv/.txt 导入（第一列=前缀，第二列可选=名称）
 *  - 规则选择：装柜设置页「从规则库选择」批量加入本柜
 *  - 规则编辑：新增 / 修改 / 删除
 * 前缀唯一；柜子的规则仍以快照形式存在 [LoadRule]（装柜时从库复制，之后改动库不回溯）。
 */
@Entity(
    tableName = "rule_library",
    indices = [Index(value = ["prefix"], unique = true)]
)
data class RuleLibEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prefix: String,                       // 条码前缀（字母+数字）
    val name: String = "",                    // 名称/型号（可选）
    val updatedAt: Date = Date()
)
