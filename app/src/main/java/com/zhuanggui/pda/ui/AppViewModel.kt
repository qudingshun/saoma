package com.zhuanggui.pda.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.zhuanggui.pda.ZhuangGuiApp
import com.zhuanggui.pda.data.dao.ScanRecordGroup
import com.zhuanggui.pda.data.entity.LoadContainer
import com.zhuanggui.pda.data.entity.LoadRule
import com.zhuanggui.pda.data.entity.RuleLibEntry
import com.zhuanggui.pda.data.entity.ScanRecord
import com.zhuanggui.pda.data.entity.ScanResult
import com.zhuanggui.pda.export.FileExporter
import com.zhuanggui.pda.importer.RuleSourceImporter
import com.zhuanggui.pda.scan.AlarmManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 出库扫码当前所处阶段 */
enum class OutboundStage { SETUP, SCANNING }

/** 本柜条码规则（双码机选项）：
 *  - [allowDuplicate] 允许重复扫：同码可反复扫不报“重复”（双码机的固定码）
 *  - [countEnabled] 计入统计：不勾选 = 辅助码（AUX），只留痕不计数、不占本柜数量
 *  - [checkHistory] 查历史串号：条码若在其他柜的记录里出现过 → 报串号（防止混入其他柜的旧箱）
 */
data class RuleSpec(
    val prefix: String,
    val allowDuplicate: Boolean = false,
    val countEnabled: Boolean = true,
    val checkHistory: Boolean = false
)

/** 出库扫码：设置阶段状态 */
data class OutboundSetupState(
    val containerNo: String = "",
    val totalQuantity: String = "",            // 本柜数量（留空/0 = 不限）
    val rules: List<RuleSpec> = emptyList(),   // 条码规则（前缀），拼装柜可多条
    val ruleInput: String = "",
    val ruleAllowDup: Boolean = false,         // 新增规则时勾选：可重复扫
    val ruleCount: Boolean = true,             // 新增规则时勾选：计入统计
    val ruleCheckHistory: Boolean = false,     // 新增规则时勾选：查历史串号
    val resumed: Boolean = false               // 命中已有柜号（未装完）= 继续装柜
)

/** 出库扫码：扫码阶段状态 */
data class OutboundScanState(
    val activeContainer: LoadContainer? = null,
    val rules: List<RuleSpec> = emptyList(),
    val total: Int = 0,                        // 本柜数量（0 = 不限）
    val okCount: Int = 0,                      // 正常件数
    val mismatchCount: Int = 0,                // 串号
    val duplicateCount: Int = 0,               // 重复
    val overCount: Int = 0,                    // 超量
    val lastFullCode: String = "",             // 柜号，条码
    val lastBarcode: String = "",
    val lastMessage: String = "",
    val lastResult: ScanResult? = null,        // 最近一枪校验结果（报警弹窗用）
    val lastRecordId: Long = 0,                // 最近一枪的记录 id（"删除这条重复记录"用）
    val scanSeq: Long = 0,                     // 每枪 +1（报警弹窗同一枪只弹一次）
    val screenColor: Color = Color.White,
    val fileSaved: Boolean = false,            // 最近一次自动落盘是否成功
    val totalRecords: Int = 0,                 // 本柜累计记录数
    val completedEvent: Long = 0,              // 装柜完成事件 = 装齐那一枪的 scanSeq（0 = 未触发；界面弹一次完成弹窗）
    val completedSummarySaved: Boolean = false // 装柜完成汇总行是否写入成功（完成弹窗里提示，v1.5.8）
)

/** 扫码页「撤回 / 前进」状态（栈深度） */
data class UndoRedoState(
    val undoCount: Int = 0,
    val redoCount: Int = 0
)

/** 记录操作（撤回/前进栈元素）：INSERT = 新扫一枪；DELETE = 删除一条记录（软删） */
private data class RecordOp(val kind: Kind, val ids: List<Long>) {
    enum class Kind { INSERT, DELETE }
}

/**
 * 全局状态机（出库扫码 + 扫码记录 + 规则库）。
 *
 * 并发要点（P0 修复）：
 *  - 所有扫码处理经 [outboundMutex] 串行化：「校验 → 入库 → 计数」按枪序原子推进，
 *    快速连扫不会丢计数，两枪同码第二枪必判重复
 *  - 状态更新一律用 [MutableStateFlow.update]（基于最新值计算），不再用旧快照 copy
 *
 * 报警要点（v1.4.0，v1.5.8 起蜂鸣/振动可在扫码页「报警设置」分别开关）：
 *  - 重复/串号为循环蜂鸣 + 循环振动，触屏 / 弹窗确认 / 下一枪 / 删除记录 / 退出扫码页都会立即停止；10 秒兜底自动停
 *  - 装柜完成（正常数首次装齐）：追加一行到 /sdcard/ZhuangGui/装柜完成汇总.tsv，界面弹一次完成弹窗（v1.5.8）
 *  - 记录删除一律进回收站（软删），可「撤回/前进」或到回收站恢复；正常记录删除需三步确认
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhuangGuiApp
    private val alarm = AlarmManager(application)

    private val scanner = app.scannerManager

    // ---------- 数据层 ----------
    private val containerDao = app.database.loadContainerDao()
    private val ruleDao = app.database.loadRuleDao()
    private val recordDao = app.database.scanRecordDao()
    private val ruleLibDao = app.database.ruleLibDao()

    /** 串行化「设置→装柜」与「每枪扫码」：保证计数与查重基于最新状态 */
    private val outboundMutex = Mutex()

    private val _outboundStage = MutableStateFlow(OutboundStage.SETUP)
    val outboundStage: StateFlow<OutboundStage> = _outboundStage.asStateFlow()

    private val _setup = MutableStateFlow(OutboundSetupState())
    val setup: StateFlow<OutboundSetupState> = _setup.asStateFlow()

    private val _outbound = MutableStateFlow(OutboundScanState())
    val outbound: StateFlow<OutboundScanState> = _outbound.asStateFlow()

    /** 当前柜最近记录（扫码页明细滚动） */
    private val _outboundRecords = MutableStateFlow<List<ScanRecord>>(emptyList())
    val outboundRecords: StateFlow<List<ScanRecord>> = _outboundRecords.asStateFlow()

    // ---------- 扫码页「撤回 / 前进」：仅覆盖本柜当前会话的记录操作 ----------
    private val undoStack = ArrayDeque<RecordOp>()
    private val redoStack = ArrayDeque<RecordOp>()
    private val _undoRedo = MutableStateFlow(UndoRedoState())
    val undoRedo: StateFlow<UndoRedoState> = _undoRedo.asStateFlow()

    // ---------- 扫码记录查看（按「日期 + 柜号」聚合，避免全表读入内存） ----------

    /** 一级：日期 + 柜号分组统计 */
    val recordGroups: StateFlow<List<ScanRecordGroup>> = recordDao.observeDayGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 首页「继续装柜」快捷入口：最近一次有效扫码的柜号；无记录时 null（单行查询，仅首页订阅） */
    val lastContainerNo: StateFlow<String?> = recordDao.observeLastScannedContainerNo()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _selectedGroup = MutableStateFlow<ScanRecordGroup?>(null)
    val selectedGroup: StateFlow<ScanRecordGroup?> = _selectedGroup.asStateFlow()

    /** 二级：选中组（柜号 + 日期）当天的记录（与一级分组口径一致） */
    val selectedGroupRecords: StateFlow<List<ScanRecord>> = _selectedGroup
        .filterNotNull()
        .flatMapLatest { recordDao.observeByContainerOnDay(it.containerId, it.dayLabel) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 回收站：已删除（软删）的记录，可逐条撤回或全部撤回 */
    val trashRecords: StateFlow<List<ScanRecord>> = recordDao.observeTrash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---------- 规则库（规则源导入 / 规则编辑 / 规则选择） ----------

    val ruleLibrary: StateFlow<List<RuleLibEntry>> = ruleLibDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ruleLibMessage = MutableStateFlow<String?>(null)
    val ruleLibMessage: StateFlow<String?> = _ruleLibMessage.asStateFlow()

    private val _ruleImporting = MutableStateFlow(false)
    val ruleImporting: StateFlow<Boolean> = _ruleImporting.asStateFlow()

    private var recordsJob: Job? = null
    private var containerLookupJob: Job? = null

    init {
        scanner.register()
        viewModelScope.launch {
            scanner.scans.collect { barcode ->
                // 兜底：单次扫码事件处理异常绝不终止采集流（否则之后所有扫码都会静默失效）
                try {
                    when {
                        // 装柜进行中 → 正常校验
                        _outboundStage.value == OutboundStage.SCANNING &&
                            _outbound.value.activeContainer != null -> handleOutboundScan(barcode)
                        // 设置页 → 扫码填入规则栏（完整码，人工删减成前缀）
                        _outboundStage.value == OutboundStage.SETUP ->
                            _setup.update { it.copy(ruleInput = barcode.trim()) }
                        // 其余场景忽略（老的 Excel 工单流程已下架，见 archive/）
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "扫码事件处理失败", t)
                }
            }
        }
    }

    // ==================== 出库扫码：设置阶段 ====================

    fun onContainerNoChange(v: String) {
        // 柜号只允许字母+数字
        val clean = v.filter { it.isLetterOrDigit() }.uppercase()
        _setup.update { it.copy(containerNo = clean) }
        scheduleContainerLookup(clean)
    }

    /**
     * 柜号输入后（防抖 150ms）查询已有柜号：
     *  - 柜号「今天」已有装柜记录（已删除的不参与统计）→ 自动载入原规则；本柜数量自动填入今天已装数量
     *    → 进入扫码页后新扫会提示超量（闭环防重复装柜；如需补装手动改大本柜数量）
     *  - 其余情况 → 取消「已有装柜记录」提示（保持手填数量与已有规则）
     * 输入已变化时丢弃过期查询结果。
     */
    private fun scheduleContainerLookup(clean: String) {
        containerLookupJob?.cancel()
        if (clean.isBlank()) return
        containerLookupJob = viewModelScope.launch {
            delay(150)
            val existing = containerDao.findByNo(clean)
            if (_setup.value.containerNo != clean) return@launch // 期间输入已变化，丢弃过期查询
            if (existing == null) {
                _setup.update { s -> if (s.containerNo != clean) s else s.copy(resumed = false) }
                return@launch
            }
            // 「今天已装数」：只算今天的有效记录（已删除的、昨天的都不参与）
            val loaded = recordDao.countByResultOnDay(existing.id, ScanResult.OK.name, todayLabel())
            val rules = ruleDao.getByContainer(existing.id)
                .map { RuleSpec(it.prefix, it.allowDuplicate, it.countEnabled, it.checkHistory) }
                .distinctBy { it.prefix }
            _setup.update { s ->
                if (s.containerNo != clean) s
                else s.copy(
                    // 有已装数据才提示并填数量（防同号柜重复装）；空壳柜号只载入规则
                    resumed = loaded > 0,
                    rules = rules,
                    // 已装柜数量直接填入本柜数量：再扫码即超量（如需补装手动改大本柜数量）
                    totalQuantity = if (loaded > 0) loaded.toString() else s.totalQuantity
                )
            }
        }
    }

    fun onTotalQuantityChange(v: String) {
        _setup.update { it.copy(totalQuantity = v.filter { c -> c.isDigit() }.take(6)) }
    }

    fun onRuleInputChange(v: String) {
        _setup.update { it.copy(ruleInput = v.filter { c -> c.isLetterOrDigit() }.uppercase()) }
    }

    /** 新增规则时的勾选：可重复扫（双码机固定码） */
    fun onRuleAllowDupChange(v: Boolean) {
        _setup.update { it.copy(ruleAllowDup = v) }
    }

    /** 新增规则时的勾选：计入统计（不勾 = 辅助码，只留痕不计数） */
    fun onRuleCountChange(v: Boolean) {
        _setup.update { it.copy(ruleCount = v) }
    }

    /** 新增规则时的勾选：查历史串号（条码在其他柜记录里出现过 → 报串号，防混柜） */
    fun onRuleCheckHistoryChange(v: Boolean) {
        _setup.update { it.copy(ruleCheckHistory = v) }
    }

    fun addRule() {
        val prefix = _setup.value.ruleInput.trim()
        if (prefix.isEmpty()) return
        _setup.update { s ->
            if (s.rules.any { it.prefix == prefix }) s.copy(ruleInput = "")
            else s.copy(
                rules = s.rules + RuleSpec(prefix, s.ruleAllowDup, s.ruleCount, s.ruleCheckHistory),
                ruleInput = ""
            )
        }
    }

    fun removeRule(rule: String) {
        _setup.update { it.copy(rules = it.rules.filterNot { r -> r.prefix == rule }) }
    }

    /** 列表里直接改某条规则的勾选（可重复 / 计入统计 / 查历史串号） */
    fun updateRuleFlags(prefix: String, allowDuplicate: Boolean, countEnabled: Boolean, checkHistory: Boolean) {
        _setup.update { s ->
            s.copy(rules = s.rules.map {
                if (it.prefix == prefix) it.copy(
                    allowDuplicate = allowDuplicate,
                    countEnabled = countEnabled,
                    checkHistory = checkHistory
                )
                else it
            })
        }
    }

    /** 规则选择：把规则库里勾选的前缀批量加入本柜（自动去重；默认“不可重复 + 计入统计”，可在列表里改） */
    fun addRulesFromSetup(prefixes: List<String>) {
        if (prefixes.isEmpty()) return
        _setup.update { s ->
            val existing = s.rules.map { it.prefix }.toSet()
            s.copy(rules = s.rules + prefixes.filterNot { it in existing }.map { RuleSpec(it) })
        }
    }

    /** 从首页进入出库扫码设置页：整页空白（不保留上次柜号/数量/规则；扫码页「返回设置」才保留） */
    fun resetOutboundSetup() {
        alarm.stop()
        _outboundStage.value = OutboundStage.SETUP
        _setup.value = OutboundSetupState()
    }

    /**
     * 扫码页「返回设置」：柜号 / 本柜数量 / 规则原样保留（无需重新选择/录入）。
     * 若本柜已装齐（正常数 ≥ 本柜数量）→ 清空规则：该柜号再装按全新柜处理。
     */
    fun backToOutboundSetup() {
        alarm.stop()
        _outboundStage.value = OutboundStage.SETUP
        val st = _outbound.value
        val done = st.activeContainer != null && st.total > 0 && st.okCount >= st.total
        if (done) {
            _setup.update { it.copy(rules = emptyList(), resumed = false) }
        }
    }

    /**
     * 点【装柜】：柜号/数量/规则落库（同柜号=继续，规则快照同步），然后进入扫码界面。
     * suspend：调用方 await 完成后再导航，避免"界面已打开但状态未就绪"导致首枪丢失。
     * 返回 false 表示参数不完整。
     */
    suspend fun startOutboundScan(): Boolean {
        val s = _setup.value
        if (s.containerNo.isBlank() || s.rules.isEmpty()) return false
        // 兜底：落库 / 恢复计数异常时记日志并留在设置页，不崩溃
        try {
            outboundMutex.withLock {
                // 防双击：已在装柜中且柜号未变 → 幂等跳过（不重复初始化）
                val already = _outboundStage.value == OutboundStage.SCANNING &&
                    _outbound.value.activeContainer?.containerNo == s.containerNo
                if (!already) {
                    val total = s.totalQuantity.toIntOrNull() ?: 0
                    // 容器 + 规则快照同步在同一事务，避免中途失败留下半套规则
                    val container = app.database.withTransaction {
                        val existing = containerDao.findByNo(s.containerNo)
                        val c: LoadContainer = if (existing == null) {
                            // 新柜号 → 建装柜会话（柜号唯一约束：同号柜只能复用一个会话行）
                            val id = containerDao.insert(
                                LoadContainer(containerNo = s.containerNo, totalQuantity = total)
                            )
                            LoadContainer(id = id, containerNo = s.containerNo, totalQuantity = total)
                        } else if (existing.totalQuantity != total) {
                            // 已有柜号 → 复用会话行，不新建；本柜数量以本次填写为准
                            val updated = existing.copy(totalQuantity = total)
                            containerDao.update(updated)
                            updated
                        } else {
                            existing
                        }
                        ruleDao.getByContainer(c.id).forEach { ruleDao.delete(it.id) }
                        s.rules.forEach {
                            ruleDao.insert(
                                LoadRule(
                                    containerId = c.id,
                                    prefix = it.prefix,
                                    allowDuplicate = it.allowDuplicate,
                                    countEnabled = it.countEnabled,
                                    checkHistory = it.checkHistory
                                )
                            )
                        }
                        c
                    }

                    // 换柜 = 新会话：清空撤回/前进栈（同柜继续装柜则保留）
                    if (_outbound.value.activeContainer?.id != container.id) {
                        undoStack.clear()
                        redoStack.clear()
                        syncUndoState()
                    }

                    // 恢复本柜历史计数（同柜号回来继续装柜，计数不丢）
                    val counts = countResults(container.id)

                    _outbound.value = OutboundScanState(
                        activeContainer = container,
                        rules = s.rules,
                        total = total,
                        okCount = counts.ok, mismatchCount = counts.mm,
                        duplicateCount = counts.dup, overCount = counts.over,
                        totalRecords = counts.total,
                        lastMessage = when {
                            total > 0 && counts.ok >= total ->
                                "该柜已装齐（${counts.ok}/$total）：再扫码将提示超量"
                            counts.total > 0 -> "继续装柜：已扫 ${counts.total} 条"
                            else -> "开始装柜：扫一个码自动保存一条"
                        }
                    )
                    _outboundStage.value = OutboundStage.SCANNING
                    recordsJob?.cancel()
                    recordsJob = viewModelScope.launch {
                        recordDao.observeByContainer(container.id, 100).collect {
                            _outboundRecords.value = it
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "装柜初始化失败（留在设置页可重试）", t)
            return false
        }
        return true
    }

    // ==================== 出库扫码：扫码校验 ====================

    /** 手动录入（出库扫码页） */
    fun manualOutboundScan(barcode: String) {
        if (barcode.isNotBlank()) handleOutboundScan(barcode.trim())
    }

    /** 停止报警振动（触屏 / 弹窗确认 / 删除记录等调用；幂等） */
    fun stopAlarm() {
        alarm.stop()
    }

    // ---------- 扫码页「报警设置」（v1.5.8：蜂鸣 / 振动开关，即时生效 + 持久化） ----------

    /** 报警蜂鸣开关（默认开） */
    private val _alarmBeep = MutableStateFlow(alarm.beepEnabled)
    val alarmBeep: StateFlow<Boolean> = _alarmBeep.asStateFlow()

    /** 报警振动开关（默认开） */
    private val _alarmVibrate = MutableStateFlow(alarm.vibrateEnabled)
    val alarmVibrate: StateFlow<Boolean> = _alarmVibrate.asStateFlow()

    fun setAlarmBeep(enabled: Boolean) {
        alarm.beepEnabled = enabled
        _alarmBeep.value = enabled
    }

    fun setAlarmVibrate(enabled: Boolean) {
        alarm.vibrateEnabled = enabled
        _alarmVibrate.value = enabled
    }

    /**
     * 删除一条记录（扫码页）：正常/重复/串号/辅助码/超量均可删。
     *  - 正常记录由界面走「三步确认」后调用；重复/串号/辅助码/超量一步确认（报警弹窗或点按行）
     *  - 一律软删除进回收站（可撤回）；本柜计数实时重算；落盘文件同步进文件回收站
     */
    fun deleteRecord(recordId: Long) {
        viewModelScope.launch {
            outboundMutex.withLock {
                val rec = recordDao.getById(recordId) ?: return@withLock
                val deletable = rec.result == ScanResult.OK ||
                    rec.result == ScanResult.DUPLICATE ||
                    rec.result == ScanResult.SERIAL_MISMATCH ||
                    rec.result == ScanResult.AUX ||
                    rec.result == ScanResult.OVER_LIMIT
                if (!deletable) return@withLock
                val container = _outbound.value.activeContainer ?: return@withLock
                if (rec.containerId != container.id) return@withLock
                if (rec.deletedAt != null) return@withLock
                recordDao.softDeleteById(recordId, Date())
                pushOp(RecordOp(RecordOp.Kind.DELETE, listOf(recordId)))
                alarm.stop()
                refreshActiveCounts()
                syncRecordFiles(listOf(pairOf(rec)))
            }
        }
    }

    /** 撤回上一步（新扫一枪 → 撤销该枪；删除 → 从回收站恢复） */
    fun undoLastRecordOp() {
        val op = undoStack.removeLastOrNull() ?: return
        viewModelScope.launch {
            outboundMutex.withLock {
                applyOp(op, isUndo = true)
                redoStack.addLast(op)
                syncUndoState()
                refreshActiveCounts()
                syncRecordFiles(recordDao.getByIds(op.ids).map { pairOf(it) })
            }
        }
    }

    /** 前进（重做刚撤回的操作） */
    fun redoLastRecordOp() {
        val op = redoStack.removeLastOrNull() ?: return
        viewModelScope.launch {
            outboundMutex.withLock {
                applyOp(op, isUndo = false)
                undoStack.addLast(op)
                syncUndoState()
                refreshActiveCounts()
                syncRecordFiles(recordDao.getByIds(op.ids).map { pairOf(it) })
            }
        }
    }

    /** 回收站：逐条撤回（计数与落盘文件一并恢复；与扫码共用互斥锁，避免并发窗口） */
    fun restoreTrashRecord(recordId: Long) {
        viewModelScope.launch {
            outboundMutex.withLock {
                val rec = recordDao.getById(recordId) ?: return@withLock
                recordDao.restoreIds(listOf(recordId))
                refreshActiveCounts()
                syncRecordFiles(listOf(pairOf(rec)))
            }
        }
    }

    /** 回收站：全部撤回（计数与落盘文件一并恢复；与扫码共用互斥锁） */
    fun restoreAllTrash() {
        viewModelScope.launch {
            outboundMutex.withLock {
                val recs = recordDao.getTrash()
                if (recs.isEmpty()) return@withLock
                recordDao.restoreAll()
                refreshActiveCounts()
                syncRecordFiles(recs.map { pairOf(it) })
            }
        }
    }

    /** 回收站：清空（永久删除；界面三步确认后调用；落盘文件一并清理；与扫码共用互斥锁） */
    fun emptyTrash() {
        viewModelScope.launch {
            outboundMutex.withLock {
                val recs = recordDao.getTrash()
                if (recs.isEmpty()) return@withLock
                recordDao.emptyTrash()
                syncRecordFiles(recs.map { pairOf(it) })
            }
        }
    }

    /**
     * 出库扫码校验：规则前缀（串号）→ 同柜重复 → 本柜数量（超量）→ 正常。
     * 全程持有 outboundMutex：快速连扫按枪序处理，不丢计数；校验与写入之间无并发窗口。
     */
    private fun handleOutboundScan(raw: String) {
        val barcode = raw.trim()
        if (barcode.isEmpty()) return

        viewModelScope.launch {
            // 兜底：单枪处理任何异常都只提示、不崩溃，扫码链路保持可用
            try {
                outboundMutex.withLock {
                    val st = _outbound.value
                    val container = st.activeContainer ?: return@withLock

                    val matchedSpecs = st.rules.filter { barcode.startsWith(it.prefix) }
                    val matched = st.rules.isEmpty() || matchedSpecs.isNotEmpty()
                    val allowDup = matchedSpecs.any { it.allowDuplicate }
                    val countEnabled = matchedSpecs.isEmpty() || matchedSpecs.any { it.countEnabled }
                    val dupCount = if (allowDup) 0 else recordDao.countBarcode(container.id, barcode)
                    // 查历史串号（勾选了该选项的规则）：条码在其他柜出现过 → 报警，防旧箱混入
                    val historyHit = if (matchedSpecs.any { it.checkHistory })
                        recordDao.findOtherContainerHistory(barcode, container.id) else null

                    // 判定顺序（业务契约，见 decideScanResult 注释）：串号→重复→历史串号→辅助→超量→正常
                    val result = decideScanResult(
                        matched = matched,
                        dupCount = dupCount,
                        historyHit = historyHit != null,
                        countEnabled = countEnabled,
                        total = st.total,
                        okCount = st.okCount
                    )
                    val message = when (result) {
                        ScanResult.SERIAL_MISMATCH ->
                            if (historyHit != null) {
                                "串号报警！该条码已于 " +
                                    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(historyHit.scannedAt) +
                                    " 在柜 ${historyHit.containerNo} 录入过，请核实是否混入本柜"
                            } else {
                                "串号报警！条码不符合本柜规则：$barcode"
                            }
                        ScanResult.DUPLICATE -> "重复扫码！该条码已录入过：$barcode"
                        ScanResult.AUX -> "辅助码（不计入本柜统计）：$barcode"
                        ScanResult.OVER_LIMIT -> "超出本柜数量（${st.total}）！条码：$barcode"
                        else -> "装柜成功"
                    }

                    val fullCode = "${container.containerNo}，$barcode"
                    val record = ScanRecord(
                        containerId = container.id,
                        containerNo = container.containerNo,
                        barcode = barcode,
                        fullCode = fullCode,
                        result = result,
                        scannedAt = Date()
                    )
                    val recordId = recordDao.insert(record)

                    // 本枪进「撤回」栈（撤回可撤销本次扫描）
                    pushOp(RecordOp(RecordOp.Kind.INSERT, listOf(recordId)))

                    // 每扫一码自动落盘（增量追加一行）
                    val saved = withContext(Dispatchers.IO) { FileExporter.append(record) }

                    // 报警反馈（重复/串号/超量 = 循环蜂鸣 + 循环振动，直到被确认/触屏/下一枪/删除）
                    when (result) {
                        ScanResult.OK, ScanResult.AUX -> alarm.success()
                        ScanResult.OVER_LIMIT -> alarm.overLimit()
                        else -> alarm.error()
                    }

                    // 本枪刚好装齐（正常数首次达到本柜数量）→ 追加一行到装柜完成汇总 + 置完成事件（界面弹一次「装柜完成」，v1.5.8）
                    val willComplete = result == ScanResult.OK && st.total > 0 &&
                        st.okCount < st.total && st.okCount + 1 >= st.total
                    val summarySaved = if (willComplete) {
                        withContext(Dispatchers.IO) {
                            FileExporter.appendCompletion(
                                containerNo = container.containerNo,
                                total = st.total,
                                ok = st.okCount + 1,
                                mismatch = st.mismatchCount,
                                duplicate = st.duplicateCount,
                                over = st.overCount,
                                totalRecords = st.totalRecords + 1
                            )
                        }
                    } else false

                    _outbound.update { cur ->
                        // 已切换柜号（期间返回设置页重新装柜）→ 丢弃过期更新
                        if (cur.activeContainer?.id != container.id) cur
                        else cur.copy(
                            okCount = if (result == ScanResult.OK) cur.okCount + 1 else cur.okCount,
                            completedEvent = if (willComplete) cur.scanSeq + 1 else cur.completedEvent,
                            completedSummarySaved = if (willComplete) summarySaved else cur.completedSummarySaved,
                            mismatchCount = if (result == ScanResult.SERIAL_MISMATCH)
                                cur.mismatchCount + 1 else cur.mismatchCount,
                            duplicateCount = if (result == ScanResult.DUPLICATE)
                                cur.duplicateCount + 1 else cur.duplicateCount,
                            overCount = if (result == ScanResult.OVER_LIMIT)
                                cur.overCount + 1 else cur.overCount,
                            totalRecords = cur.totalRecords + 1,
                            lastFullCode = fullCode,
                            lastBarcode = barcode,
                            lastMessage = message,
                            lastResult = result,
                            lastRecordId = recordId,
                            scanSeq = cur.scanSeq + 1,
                            screenColor = when (result) {
                                ScanResult.OK -> Color(0xFFE8F5E9)
                                ScanResult.AUX -> Color(0xFFE3F2FD)
                                ScanResult.OVER_LIMIT -> Color(0xFFFFF9C4)
                                else -> Color(0xFFFFCDD2)
                            },
                            fileSaved = saved
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "本条扫码处理失败：$barcode", t)
                _outbound.update { cur ->
                    if (cur.activeContainer == null) cur
                    else cur.copy(lastMessage = "本条处理失败（${t.message ?: "未知错误"}），请重扫")
                }
            }
        }
    }

    // ==================== 扫码记录查看 ====================

    /** 打开/关闭二级分组（null = 回到一级） */
    fun openRecordGroup(group: ScanRecordGroup?) {
        _selectedGroup.value = group
    }

    /** 记录页：单个/批量删除「日期 + 柜号」组的全部记录（软删进回收站，可在回收站撤回；柜子与规则保留） */
    fun deleteRecordGroups(groups: List<ScanRecordGroup>) {
        if (groups.isEmpty()) return
        viewModelScope.launch {
            outboundMutex.withLock {
                val now = Date()
                app.database.withTransaction {
                    groups.forEach { recordDao.softDeleteGroup(it.containerId, it.dayLabel, now) }
                }
                alarm.stop()
                refreshActiveCounts()
                syncRecordFiles(groups.map { Triple(it.containerId, it.containerNo, it.dayLabel) })
                // 若正停留在被删除的二级页 → 回到一级
                val sel = _selectedGroup.value
                if (sel != null && groups.any { it.containerId == sel.containerId && it.dayLabel == sel.dayLabel }) {
                    _selectedGroup.value = null
                }
            }
        }
    }

    // ==================== 规则库 ====================

    /** 规则编辑：新增（id = null）或修改一条库中规则 */
    fun saveLibRule(id: Long?, prefixInput: String, name: String) {
        val prefix = RuleSourceImporter.cleanPrefix(prefixInput)
        if (prefix == null) {
            _ruleLibMessage.value = "前缀无效：仅支持字母+数字（0-9 / A-Z）"
            return
        }
        val cleanName = name.trim()
        viewModelScope.launch {
            if (id == null) {
                // 新增同样查重（与编辑一致）：避免 upsert 的 REPLACE 静默覆盖库中同名前缀
                if (ruleLibDao.getAll().any { it.prefix == prefix }) {
                    _ruleLibMessage.value = "前缀已存在：$prefix（未新增；可在列表中点该行「编辑」改名称）"
                    return@launch
                }
                ruleLibDao.upsert(RuleLibEntry(prefix = prefix, name = cleanName))
            } else {
                val conflict = ruleLibDao.getAll().any { it.prefix == prefix && it.id != id }
                if (conflict) {
                    _ruleLibMessage.value = "前缀已存在：$prefix"
                    return@launch
                }
                ruleLibDao.update(id, prefix, cleanName, Date())
            }
        }
    }

    fun deleteLibRule(id: Long) {
        viewModelScope.launch { ruleLibDao.delete(id) }
    }

    /**
     * 规则源导入：解析 .xlsx/.xls/.csv/.txt（第一列=前缀，第二列可选=名称），
     * 全部写入规则库；前缀冲突则覆盖名称。结果通过 [ruleLibMessage] 提示。
     */
    fun importRuleSource(context: Context, uri: Uri) {
        viewModelScope.launch {
            _ruleImporting.value = true
            val result = runCatching {
                val parsed = withContext(Dispatchers.IO) { RuleSourceImporter.parse(context, uri) }
                if (parsed.isEmpty()) {
                    "未解析到有效规则。\n格式要求：第一列=条码前缀（字母+数字），第二列可选=名称"
                } else {
                    var added = 0
                    var updated = 0
                    app.database.withTransaction {
                        val existing = ruleLibDao.getAll().map { it.prefix }.toSet()
                        parsed.forEach { p ->
                            if (p.prefix in existing) updated++ else added++
                            ruleLibDao.upsert(RuleLibEntry(prefix = p.prefix, name = p.name))
                        }
                    }
                    "导入完成：新增 $added 条，覆盖 $updated 条，共 ${parsed.size} 条"
                }
            }.getOrElse { "导入失败：${it.message ?: "文件无法解析"}" }
            _ruleImporting.value = false
            _ruleLibMessage.value = result
        }
    }

    fun clearRuleLibMessage() {
        _ruleLibMessage.value = null
    }

    // ==================== 内部工具 ====================

    /** 记录操作入「撤回」栈（清空「前进」栈；上限 100 步） */
    private fun pushOp(op: RecordOp) {
        undoStack.addLast(op)
        while (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        syncUndoState()
    }

    private fun syncUndoState() {
        _undoRedo.value = UndoRedoState(undoStack.size, redoStack.size)
    }

    private val dayLabelFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun dayLabelOf(date: Date): String = dayLabelFormat.format(date)

    private fun pairOf(rec: ScanRecord): Triple<Long, String, String> =
        Triple(rec.containerId, rec.containerNo, dayLabelOf(rec.scannedAt))

    /**
     * 删除 / 撤回 / 恢复后：同步「某柜某日」的装柜记录文件与文件回收站，保持与 App 动作一致
     * （整组删空 = 文件从「装柜记录」移到「回收站」目录；撤回 = 移回；部分删除 = 两侧各留一份）。
     */
    private suspend fun syncRecordFiles(pairs: Collection<Triple<Long, String, String>>) {
        pairs.distinct().forEach { (containerId, containerNo, dayLabel) ->
            val all = recordDao.getAllGroup(containerId, dayLabel)
            val active = all.filter { it.deletedAt == null }
            val deleted = all.filter { it.deletedAt != null }
            withContext(Dispatchers.IO) {
                FileExporter.syncDay(dayLabel, containerNo, active, deleted)
            }
        }
    }

    /** 撤回/前进：INSERT 撤销 = 软删该枪；DELETE 撤销 = 恢复记录（前进取反） */
    private suspend fun applyOp(op: RecordOp, isUndo: Boolean) {
        val doDelete = if (isUndo) op.kind == RecordOp.Kind.INSERT
        else op.kind == RecordOp.Kind.DELETE
        if (doDelete) recordDao.softDeleteIds(op.ids, Date())
        else recordDao.restoreIds(op.ids)
    }

    /** 重算当前柜计数（删除 / 撤回 / 恢复后调用；不在装柜中则忽略） */
    private suspend fun refreshActiveCounts() {
        val container = _outbound.value.activeContainer ?: return
        val counts = countResults(container.id)
        _outbound.update { cur ->
            if (cur.activeContainer?.id != container.id) cur
            else cur.copy(
                okCount = counts.ok,
                mismatchCount = counts.mm,
                duplicateCount = counts.dup,
                overCount = counts.over,
                totalRecords = counts.total
            )
        }
    }

    private data class ResultCounts(val ok: Int, val mm: Int, val dup: Int, val over: Int, val aux: Int) {
        val total: Int get() = ok + mm + dup + over + aux
    }

    private suspend fun countResults(containerId: Long): ResultCounts = ResultCounts(
        ok = recordDao.countByResultOnDay(containerId, ScanResult.OK.name, todayLabel()),
        mm = recordDao.countByResultOnDay(containerId, ScanResult.SERIAL_MISMATCH.name, todayLabel()),
        dup = recordDao.countByResultOnDay(containerId, ScanResult.DUPLICATE.name, todayLabel()),
        over = recordDao.countByResultOnDay(containerId, ScanResult.OVER_LIMIT.name, todayLabel()),
        aux = recordDao.countByResultOnDay(containerId, ScanResult.AUX.name, todayLabel())
    )

    /** 今天（设备本地时区）的日期标签，与 SQLite strftime('%Y-%m-%d','localtime') 一致 */
    private fun todayLabel(): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    override fun onCleared() {
        scanner.unregister()
        alarm.release()
        super.onCleared()
    }

    private companion object {
        const val TAG = "AppViewModel"
    }
}
