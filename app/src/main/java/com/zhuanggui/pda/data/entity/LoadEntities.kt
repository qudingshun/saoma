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
    val totalQuantity: Int = 0,              // 计划装柜数量（0 = 不限）
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
 * 流水号范围（v11，选填）：前缀之后的流水号数字须落在 [serialMin, serialMax] 内
 * （留空 = 该侧不限；两侧都留空 = 不校验流水号，行为与旧版一致）。
 * serialTailLen（v12）：>0 = 取条码「末尾 N 位数字」作为流水号（先忽略末尾字母/符号），
 * 用于条码中间还有不参与核对的字符/数字、流水号在末尾的情况（如 ASV12SDFWG05455056$ → 056）。
 * 0 = 旧语义（前缀之后整段必须是纯数字）。
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
    val checkHistory: Boolean = false,        // 查历史串号：条码若在其他柜记录里出现过 → 报串号（防混柜）
    val serialMin: String = "",               // 流水号最小值（前缀之后的数字下界；空 = 不限，v11）
    val serialMax: String = "",               // 流水号最大值（前缀之后的数字上界；空 = 不限，v11）
    val serialTailLen: Int = 0,               // 流水号取末尾 N 位数字（0 = 前缀后整段，v12）
    val ignoreRepeat: Boolean = false         // 忽略（仅“可重复扫”时生效）：扫到不记录、不落盘、不计数（v11）
)

/**
 * 扫码记录：每扫一码写一条（含串号/重复/超量/辅助码，完整留痕），同时自动落盘 TSV 文件。
 * fullCode = 柜号 + "，" + 条码（逗号拼接，按需求约定）。
 * operatorId = 扫描时的操作员工号（设置页选填；空 = 未填），用于追溯（v1.6.0）。
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
    indices = [Index("containerId"), Index("barcode")]   // barcode 索引（v9）：查重 / 查历史串号提速
)
data class ScanRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val containerId: Long,
    val containerNo: String,
    val barcode: String,
    val fullCode: String,                     // 柜号，条码
    val result: ScanResult,
    val scannedAt: Date = Date(),
    val deletedAt: Date? = null,              // 软删除时间（进回收站）；null = 有效记录
    val operatorId: String = ""               // 操作员工号（v10；空 = 未填）
)

/**
 * 规则库（规则源）：全局共享的条码前缀库。
 *  - 规则源导入：从 .xlsx/.xls/.csv/.txt 导入（第一列=前缀，第二列可选=名称）
 *  - 规则选择：装柜设置页「从规则库选择」批量加入本柜（连同四个开关一起复制）
 *  - 规则编辑：新增 / 修改 / 删除；同一型号（name）可挂多条前缀（v1.7.0 第 6 条，按型号分组展示）
 * 四个开关（v11）：与出库扫码规则设置对齐 + 忽略
 *  - [allowDuplicate] 可重复扫：同码反复扫不报“重复”（双码机固定码）
 *  - [countEnabled] 计入统计：不勾选 = 辅助码（只留痕不计数）
 *  - [checkHistory] 查历史串号：条码在其他柜出现过 → 报串号（防混柜）
 *  - [ignoreRepeat] 忽略（仅“可重复扫”时生效）：扫到不记录、不落盘、不计数（语音仍报“辅助码”）
 * 前缀唯一；柜子的规则仍以快照形式存在 [LoadRule]（装柜时从库复制，之后改动库不回溯）。
 */
@Entity(
    tableName = "rule_library",
    indices = [Index(value = ["prefix"], unique = true)]
)
data class RuleLibEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prefix: String,                       // 条码前缀（字母+数字）
    val name: String = "",                    // 型号（可选；同型号可多条前缀，界面按型号分组）
    val allowDuplicate: Boolean = false,      // 可重复扫（v11）
    val countEnabled: Boolean = true,         // 计入统计（v11）
    val checkHistory: Boolean = false,        // 查历史串号（v11）
    val ignoreRepeat: Boolean = false,        // 忽略：可重复扫的条码扫到不记录（v11）
    val updatedAt: Date = Date()
)

/**
 * 规则库型号（v13）：规则库维护以「型号名称」为主，先建型号，再逐个添加前缀规则。
 * - name 唯一；允许型号下暂时没有规则（空型号）
 * - 规则表 rule_library 仍按名称冗余保存（导出/选择沿用），改名时同步更新
 * - 删除型号 = 删除其下全部规则
 */
@Entity(
    tableName = "rule_models",
    indices = [Index(value = ["name"], unique = true)]
)
data class RuleModel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val updatedAt: Date = Date()
)
