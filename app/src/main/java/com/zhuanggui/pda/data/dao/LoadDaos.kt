package com.zhuanggui.pda.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zhuanggui.pda.data.entity.LoadContainer
import com.zhuanggui.pda.data.entity.LoadRule
import com.zhuanggui.pda.data.entity.RuleLibEntry
import com.zhuanggui.pda.data.entity.RuleModel
import com.zhuanggui.pda.data.entity.ScanRecord
import kotlinx.coroutines.flow.Flow
import java.util.Date

/** 记录页一级聚合行：按「日期 + 柜号」分组的统计 */
data class ScanRecordGroup(
    val dayLabel: String,   // yyyy-MM-dd（设备本地时区）
    val containerId: Long,
    val containerNo: String,
    val cnt: Int,           // 该组记录总数
    val okCnt: Int,         // 正常
    val mmCnt: Int,         // 串号
    val dupCnt: Int,        // 重复
    val overCnt: Int,       // 超量
    val lastAt: Long        // 组内最新一条时间（排序用）
)

@Dao
interface LoadContainerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(container: LoadContainer): Long

    @Update
    suspend fun update(container: LoadContainer)

    /** 按柜号取会话行（柜号唯一，v1.5.0 起同号柜复用同一行；ORDER BY id DESC 仅为防御历史脏数据） */
    @Query("SELECT * FROM load_containers WHERE containerNo = :no ORDER BY id DESC LIMIT 1")
    suspend fun findByNo(no: String): LoadContainer?
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

    @Query("SELECT * FROM scan_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ScanRecord?

    /** 按 id 批量取（含已删除；撤回/前进后同步落盘文件用） */
    @Query("SELECT * FROM scan_records WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ScanRecord>

    // -------- 软删除（回收站）：删除 = 打标记，可在回收站撤回 --------

    /** 软删除单条（扫码页） */
    @Query("UPDATE scan_records SET deletedAt = :at, syncedAt = NULL WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDeleteById(id: Long, at: Date)

    /** 软删除多条（撤回栈批量用） */
    @Query("UPDATE scan_records SET deletedAt = :at, syncedAt = NULL WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun softDeleteIds(ids: List<Long>, at: Date)

    /**
     * 软删除整组记录（记录页：某柜某日期的全部记录）。
     * 日期表达式与 [observeDayGroups] 的分组表达式一致（本地时区）。
     */
    @Query(
        "UPDATE scan_records SET deletedAt = :at, syncedAt = NULL WHERE containerId = :containerId " +
            "AND strftime('%Y-%m-%d', scannedAt / 1000, 'unixepoch', 'localtime') = :dayLabel " +
            "AND deletedAt IS NULL"
    )
    suspend fun softDeleteGroup(containerId: Long, dayLabel: String, at: Date)

    /** 某柜某日全部记录（含已删除；删除/撤回后同步装柜记录文件与文件回收站用） */
    @Query(
        "SELECT * FROM scan_records WHERE containerId = :containerId " +
            "AND strftime('%Y-%m-%d', scannedAt / 1000, 'unixepoch', 'localtime') = :dayLabel " +
            "ORDER BY scannedAt, id"
    )
    suspend fun getAllGroup(containerId: Long, dayLabel: String): List<ScanRecord>

    /** 撤回：恢复指定记录（deletedAt 置空；同步状态清除 → 重新上传删除状态） */
    @Query("UPDATE scan_records SET deletedAt = NULL, syncedAt = NULL WHERE id IN (:ids)")
    suspend fun restoreIds(ids: List<Long>)

    /** 回收站：全部撤回 */
    @Query("UPDATE scan_records SET deletedAt = NULL, syncedAt = NULL WHERE deletedAt IS NOT NULL")
    suspend fun restoreAll()

    /** 回收站列表（新删的在前） */
    @Query("SELECT * FROM scan_records WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC, id DESC")
    fun observeTrash(): Flow<List<ScanRecord>>

    /** 回收站快照（清空/全部撤回前先取，用于同步落盘文件） */
    @Query("SELECT * FROM scan_records WHERE deletedAt IS NOT NULL")
    suspend fun getTrash(): List<ScanRecord>

    /** 回收站：清空（永久删除，UI 三次确认） */
    @Query("DELETE FROM scan_records WHERE deletedAt IS NOT NULL")
    suspend fun emptyTrash()

    /** 记录页一级聚合：按「日期 + 柜号」分组统计（最新日期在前；不含回收站） */
    @Query(
        "SELECT strftime('%Y-%m-%d', scannedAt / 1000, 'unixepoch', 'localtime') AS dayLabel, " +
            "containerId, containerNo, " +
            "COUNT(*) AS cnt, " +
            "SUM(CASE WHEN result = 'OK' THEN 1 ELSE 0 END) AS okCnt, " +
            "SUM(CASE WHEN result = 'SERIAL_MISMATCH' THEN 1 ELSE 0 END) AS mmCnt, " +
            "SUM(CASE WHEN result = 'DUPLICATE' THEN 1 ELSE 0 END) AS dupCnt, " +
            "SUM(CASE WHEN result = 'OVER_LIMIT' THEN 1 ELSE 0 END) AS overCnt, " +
            "MAX(scannedAt) AS lastAt " +
            "FROM scan_records WHERE deletedAt IS NULL GROUP BY dayLabel, containerId ORDER BY lastAt DESC"
    )
    fun observeDayGroups(): Flow<List<ScanRecordGroup>>

    /** 某柜某日记录（记录页二级：与一级分组口径一致，只展示当天条码） */
    @Query(
        "SELECT * FROM scan_records WHERE containerId = :containerId AND deletedAt IS NULL " +
            "AND strftime('%Y-%m-%d', scannedAt / 1000, 'unixepoch', 'localtime') = :dayLabel " +
            "ORDER BY scannedAt DESC, id DESC"
    )
    fun observeByContainerOnDay(containerId: Long, dayLabel: String): Flow<List<ScanRecord>>

    /** 当前柜最近 N 条（扫码页滚动显示） */
    @Query("SELECT * FROM scan_records WHERE containerId = :containerId AND deletedAt IS NULL ORDER BY scannedAt DESC, id DESC LIMIT :limit")
    fun observeByContainer(containerId: Long, limit: Int): Flow<List<ScanRecord>>

    /** 首页「继续装柜」快捷入口：最近一次有效扫码的柜号（无记录时 null） */
    @Query("SELECT containerNo FROM scan_records WHERE deletedAt IS NULL ORDER BY scannedAt DESC, id DESC LIMIT 1")
    fun observeLastScannedContainerNo(): Flow<String?>

    @Query("SELECT COUNT(*) FROM scan_records WHERE containerId = :containerId AND barcode = :barcode AND deletedAt IS NULL")
    suspend fun countBarcode(containerId: Long, barcode: String): Int

    /** 查历史串号：该条码是否已在「其他柜」的有效记录中扫过（取最新一条，用于报警提示） */
    @Query(
        "SELECT * FROM scan_records WHERE barcode = :barcode AND containerId != :excludeContainerId " +
            "AND deletedAt IS NULL ORDER BY scannedAt DESC, id DESC LIMIT 1"
    )
    suspend fun findOtherContainerHistory(barcode: String, excludeContainerId: Long): ScanRecord?

    /** 某柜某天某结果的记录数（不含回收站；用于「今天已装数」与按天统计） */
    @Query(
        "SELECT COUNT(*) FROM scan_records WHERE containerId = :containerId AND result = :result " +
            "AND deletedAt IS NULL " +
            "AND strftime('%Y-%m-%d', scannedAt / 1000, 'unixepoch', 'localtime') = :dayLabel"
    )
    suspend fun countByResultOnDay(containerId: Long, result: String, dayLabel: String): Int

    // ---------- WiFi 同步（v14） ----------

    /** 待上传：syncedAt IS NULL（新记录 / 删除状态变化 / 撤回） */
    @Query("SELECT * FROM scan_records WHERE syncedAt IS NULL ORDER BY scannedAt, id LIMIT :limit")
    suspend fun pendingSync(limit: Int): List<ScanRecord>

    @Query("SELECT COUNT(*) FROM scan_records WHERE syncedAt IS NULL")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM scan_records WHERE syncedAt IS NULL")
    suspend fun countPending(): Int

    /** 上传成功回写；传 null 表示需要重传（软删除变化时用） */
    @Query("UPDATE scan_records SET syncedAt = :at WHERE uid IN (:uids)")
    suspend fun markSynced(uids: List<String>, at: Date?)

    @Query("UPDATE scan_records SET syncedAt = NULL WHERE id = :id")
    suspend fun resetSynced(id: Long)
}

@Dao
interface RuleLibDao {
    /** 新增或覆盖（prefix 唯一，冲突则替换：导入用） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RuleLibEntry): Long

    /** 修改（规则编辑：前缀 / 型号 / 四个开关，v1.7.0） */
    @Update
    suspend fun updateEntry(entry: RuleLibEntry)

    @Query("SELECT * FROM rule_library ORDER BY prefix")
    fun observeAll(): Flow<List<RuleLibEntry>>

    @Query("SELECT * FROM rule_library ORDER BY prefix")
    suspend fun getAll(): List<RuleLibEntry>

    @Query("DELETE FROM rule_library WHERE id = :id")
    suspend fun delete(id: Long)

    /** 型号改名：同步更新该型号下所有规则的名称（v13） */
    @Query("UPDATE rule_library SET name = :newName, updatedAt = :updatedAt WHERE name = :oldName")
    suspend fun renameModelRules(oldName: String, newName: String, updatedAt: java.util.Date)

    /** 删除型号：删除其下全部规则（v13） */
    @Query("DELETE FROM rule_library WHERE name = :name")
    suspend fun deleteByModelName(name: String)
}

@Dao
interface RuleModelDao {
    @Query("SELECT * FROM rule_models ORDER BY name")
    fun observeAll(): Flow<List<RuleModel>>

    @Query("SELECT * FROM rule_models ORDER BY name")
    suspend fun getAll(): List<RuleModel>

    @Query("SELECT * FROM rule_models WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): RuleModel?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(model: RuleModel): Long

    @Query("UPDATE rule_models SET name = :newName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, newName: String, updatedAt: java.util.Date)

    @Query("DELETE FROM rule_models WHERE id = :id")
    suspend fun delete(id: Long)
}
