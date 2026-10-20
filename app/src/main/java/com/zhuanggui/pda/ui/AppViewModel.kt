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
import com.zhuanggui.pda.data.entity.RuleModel
import com.zhuanggui.pda.data.entity.ScanRecord
import com.zhuanggui.pda.data.entity.ScanResult
import com.zhuanggui.pda.data.entity.SyncState
import com.zhuanggui.pda.export.FileExporter
import com.zhuanggui.pda.export.RuleExporter
import com.zhuanggui.pda.importer.RuleSourceImporter
import com.zhuanggui.pda.net.PairingInfo
import com.zhuanggui.pda.net.SyncRepository
import com.zhuanggui.pda.scan.AlarmManager
import com.zhuanggui.pda.sync.SyncScheduler
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

/** 设置页扫码键的填入口：工号栏聚焦时填工号（替换），否则填规则栏（原行为） */
enum class SetupScanTarget { RULE, OPERATOR }

/** 本柜条码规则（双码机选项）：
 *  - [allowDuplicate] 允许重复扫：同码可反复扫不报“重复”（双码机的固定码）
 *  - [countEnabled] 计入统计：不勾选 = 辅助码（AUX），只留痕不计数、不占计划装柜数量
 *  - [checkHistory] 查历史串号：条码若在其他柜的记录里出现过 → 报串号（防止混入其他柜的旧箱）
 *  - [serialMin] / [serialMax] 流水号范围（v1.7.0）：前缀之后的数字须落在范围内，空 = 该侧不限
 *  - [serialTailLen] 末尾流水号位数（v1.7.2）：>0 = 取条码末尾 N 位数字作为流水号（忽略末尾符号），
 *    用于中间还有不参与核对的字符/数字、流水号在末尾的条码；0 = 前缀后整段数字
 *  - [ignoreRepeat] 忽略（v1.7.0）：仅「可重复扫」时生效，扫到不记录、不落盘、不计数（语音报“辅助码”）
 */
data class RuleSpec(
    val prefix: String,
    val allowDuplicate: Boolean = false,
    val countEnabled: Boolean = true,
    val checkHistory: Boolean = false,
    val serialMin: String = "",
    val serialMax: String = "",
    val serialTailLen: Int = 0,
    val ignoreRepeat: Boolean = false
)

/** WiFi 同步界面状态（v1.8.0） */
data class SyncUiState(
    val paired: Boolean = false,
    val baseUrl: String = "",
    val deviceId: String = "",
    val lastSyncAt: Long = 0L,
    val lastError: String = "",
    val serverTime: String = "",
    val pairing: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    // 规则库同步（v1.9.0）
    val ruleLibHasData: Boolean = false,
    val ruleLibServerRev: Int = 0,
    val ruleLibLocalRev: Int = 0,
    val ruleLibServerModels: Int = 0,
    val ruleLibServerRules: Int = 0,
    val ruleLibUpdatedAt: String = "",
    val ruleLibUpdatedBy: String = "",
    val ruleLibLocalModels: Int = 0,
    val ruleLibLocalRules: Int = 0,
    val ruleLibBusy: Boolean = false,
    val ruleLibError: String = ""
)

/** 出库扫码：设置阶段状态（v1.5.9：数量拆分为「计划装柜数量」+「已扫码数量」；v1.7.0：计划随柜回显） */
data class OutboundSetupState(
    val containerNo: String = "",
    val totalQuantity: String = "",            // 计划装柜数量（人工填写；命中已有柜号/从扫码页返回时回显已存计划）
    val rules: List<RuleSpec> = emptyList(),   // 条码规则（前缀），拼装柜可多条
    val ruleInput: String = "",
    val ruleAllowDup: Boolean = false,         // 新增规则时勾选：可重复扫
    val ruleCount: Boolean = true,             // 新增规则时勾选：计入统计
    val ruleCheckHistory: Boolean = false,     // 新增规则时勾选：查历史串号
    val ruleSerialMin: String = "",            // 新增规则时填：流水号最小值（选填，v1.7.0）
    val ruleSerialMax: String = "",            // 新增规则时填：流水号最大值（选填，v1.7.0）
    val ruleSerialTailLen: String = "",        // 新增规则时填：流水号取末尾 N 位（空/0 = 前缀后整段，v1.7.2）
    val ruleIgnoreRepeat: Boolean = false,      // 新增规则时勾选：忽略（仅可重复扫时生效，v1.7.0）
    val scannedQuantity: Int = 0,              // 今天已扫码数量（只读展示，自动统计；= 已装柜数量）
    val storedPlan: Int = 0,                   // 命中柜号的已存计划装柜数量（0 = 无/不限；仅用于提示与留空续装兜底）
    val operatorId: String = ""                // 操作员工号（选填：下拉 A/B/C/D 或扫工牌/手输；随本批记录落库落盘）
)

/** 出库扫码：扫码阶段状态 */
data class OutboundScanState(
    val activeContainer: LoadContainer? = null,
    val rules: List<RuleSpec> = emptyList(),
    val total: Int = 0,                        // 计划装柜数量（0 = 不限）
    val operatorId: String = "",               // 操作员工号（进扫码会话时快照；随每条记录落库落盘）
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
 * 规则库导入预览（v1.8.3）：解析完先让用户核对「新增 / 覆盖」再决定是否写入。
 * 备份仍在确认后、写库前执行；取消导入不会改规则库、也不会生成备份。
 */
data class RuleImportPreview(
    val rules: List<RuleSourceImporter.ParsedRule>,
    val modelNames: List<String>,
    val newRules: Int,
    val overwriteRules: Int,
    val newModels: Int,
    val overwriteSamples: List<String>
)

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

    /** WiFi 同步（v1.8.0）：旁挂能力，未配对时完全不影响扫码 */
    private val syncRepository = app.syncRepository
    private val syncStateDao = app.database.syncStateDao()

    // ---------- 数据层 ----------
    private val containerDao = app.database.loadContainerDao()
    private val ruleDao = app.database.loadRuleDao()
    private val recordDao = app.database.scanRecordDao()
    private val ruleLibDao = app.database.ruleLibDao()
    private val ruleModelDao = app.database.ruleModelDao()

    /** 串行化「设置→装柜」与「每枪扫码」：保证计数与查重基于最新状态 */
    private val outboundMutex = Mutex()

    private val _outboundStage = MutableStateFlow(OutboundStage.SETUP)
    val outboundStage: StateFlow<OutboundStage> = _outboundStage.asStateFlow()

    private val _setup = MutableStateFlow(OutboundSetupState())
    val setup: StateFlow<OutboundSetupState> = _setup.asStateFlow()

    /** 设置页扫码键填入口（工号栏聚焦 = OPERATOR，其余 = 规则栏）；由界面焦点变化驱动 */
    private val _setupScanTarget = MutableStateFlow(SetupScanTarget.RULE)

    /** 操作员工号：记住上次填写（首页进入设置页时预填，避免每柜重输） */
    private val operatorPrefs = application.getSharedPreferences("operator_prefs", Context.MODE_PRIVATE)

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

    /** 规则库型号（v13）：先建型号、再逐个添加规则；空型号也会显示 */
    val ruleModels: StateFlow<List<RuleModel>> = ruleModelDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ruleLibMessage = MutableStateFlow<String?>(null)
    val ruleLibMessage: StateFlow<String?> = _ruleLibMessage.asStateFlow()

    private val _ruleImporting = MutableStateFlow(false)
    val ruleImporting: StateFlow<Boolean> = _ruleImporting.asStateFlow()

    /** 导入解析完成后的待确认预览；null = 没有待确认导入 */
    private val _ruleImportPreview = MutableStateFlow<RuleImportPreview?>(null)
    val ruleImportPreview: StateFlow<RuleImportPreview?> = _ruleImportPreview.asStateFlow()

    private val _ruleExporting = MutableStateFlow(false)
    val ruleExporting: StateFlow<Boolean> = _ruleExporting.asStateFlow()

    // ---------- WiFi 同步（v1.8.0） ----------

    private val _syncUi = MutableStateFlow(SyncUiState())
    val syncUi: StateFlow<SyncUiState> = _syncUi.asStateFlow()

    /** 待上传条数（扫码页/首页实时显示） */
    val syncPending: StateFlow<Int> = syncRepository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 配对模式：下一条扫码内容按配对码解析（不影响正常扫码） */
    private val _pairing = MutableStateFlow(false)

    private var recordsJob: Job? = null
    private var containerLookupJob: Job? = null

    init {
        scanner.register()
        refreshSyncState()
        viewModelScope.launch {
            scanner.scans.collect { barcode ->
                // 兜底：单次扫码事件处理异常绝不终止采集流（否则之后所有扫码都会静默失效）
                try {
                    // 配对模式：这一枪按配对码解析（二维码/一维码兜底），不进入规则/工号
                    if (_pairing.value) {
                        _pairing.value = false
                        _syncUi.update { it.copy(pairing = false) }
                        pairWithCode(barcode)
                        return@collect
                    }
                    when {
                        // 装柜进行中 → 正常校验
                        _outboundStage.value == OutboundStage.SCANNING &&
                            _outbound.value.activeContainer != null -> handleOutboundScan(barcode)
                        // 设置页 → 扫码填入：工号栏聚焦时填工号（替换），否则填规则栏（完整码，人工删减成前缀）
                        _outboundStage.value == OutboundStage.SETUP ->
                            if (_setupScanTarget.value == SetupScanTarget.OPERATOR) onOperatorIdChange(barcode)
                            else _setup.update { it.copy(ruleInput = barcode.trim()) }
                        // 其余场景忽略（老的 Excel 工单流程已下架，见 archive/）
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "扫码事件处理失败", t)
                }
            }
        }
    }

    // ==================== WiFi 同步（v1.8.0） ====================

    /** 读 sync_state → 界面状态（未配对也可看 deviceId 与上次错误） */
    fun refreshSyncState() {
        viewModelScope.launch {
            val url = syncStateDao.get(SyncState.K_SERVER_URL).orEmpty()
            val token = syncStateDao.get(SyncState.K_TOKEN).orEmpty()
            val device = syncRepository.ensureDeviceId()
            val last = syncStateDao.get(SyncState.K_LAST_SYNC_AT)
                ?.toLongOrNull() ?: 0L
            val err = syncStateDao.get(SyncState.K_LAST_ERROR).orEmpty()
            val st = syncStateDao.get(SyncState.K_SERVER_TIME).orEmpty()
            val rlSynced = syncStateDao.get(SyncState.K_RULELIB_SYNCED_REV)?.toIntOrNull() ?: 0
            val rlServer = syncStateDao.get(SyncState.K_RULELIB_SERVER_REV)?.toIntOrNull() ?: 0
            val rlAt = syncStateDao.get(SyncState.K_RULELIB_UPDATED_AT).orEmpty()
            val rlBy = syncStateDao.get(SyncState.K_RULELIB_UPDATED_BY).orEmpty()
            val localModelCount = ruleModelDao.getAll().size
            val localRuleCount = ruleLibDao.getAll().size
            _syncUi.update {
                it.copy(
                    paired = url.isNotBlank() && token.isNotBlank(),
                    baseUrl = url,
                    deviceId = device,
                    lastSyncAt = last,
                    lastError = err,
                    serverTime = st,
                    ruleLibServerRev = rlServer,
                    ruleLibLocalRev = rlSynced,
                    ruleLibUpdatedAt = rlAt,
                    ruleLibUpdatedBy = rlBy,
                    ruleLibLocalModels = localModelCount,
                    ruleLibLocalRules = localRuleCount
                )
            }
        }
    }

    /** 进入配对模式：下一枪扫码按配对码解析 */
    fun startPairing() {
        _pairing.value = true
        _syncUi.update {
            it.copy(pairing = true, message = "请用扫码枪扫描电脑屏幕上的配对二维码（或点「手动输入」）")
        }
    }

    fun cancelPairing() {
        _pairing.value = false
        _syncUi.update { it.copy(pairing = false) }
    }

    /** 配对码：SAOMASYNC1|ip=..|port=..|token=.. 或手输 ip:port#token */
    fun pairWithCode(raw: String) {
        val info = PairingInfo.parse(raw)
        if (info == null) {
            _syncUi.update { it.copy(message = "配对码无效：$raw") }
            return
        }
        viewModelScope.launch {
            _syncUi.update { it.copy(busy = true, message = null) }
            runCatching { withContext(Dispatchers.IO) { syncRepository.pair(info) } }
                .onSuccess { cfg ->
                    _syncUi.update {
                        it.copy(
                            busy = false,
                            message = "配对成功：${cfg.baseUrl}\n设备号 ${cfg.deviceId}"
                        )
                    }
                    refreshSyncState()
                    SyncScheduler.syncNow(app)
                    refreshRuleLibInfo()
                }
                .onFailure { e ->
                    Log.w("SyncPair", "配对失败", e)
                    _syncUi.update {
                        it.copy(
                            busy = false,
                            message = "配对失败：${e.message ?: e.javaClass.simpleName}"
                        )
                    }
                    refreshSyncState()
                }
        }
    }

    fun testSyncConnection() {
        viewModelScope.launch {
            _syncUi.update { it.copy(busy = true) }
            runCatching { withContext(Dispatchers.IO) { syncRepository.health() } }
                .onSuccess { h ->
                    _syncUi.update {
                        it.copy(
                            busy = false,
                            serverTime = h.optString("time"),
                            message = "连接成功：服务端已收 ${h.optInt("pendingScans", 0)} 条记录\n" +
                                "服务器时间 ${h.optString("time")}"
                        )
                    }
                    refreshSyncState()
                }
                .onFailure { e ->
                    _syncUi.update {
                        it.copy(busy = false, message = "连接失败：${e.message ?: "网络不通"}")
                    }
                    refreshSyncState()
                }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _syncUi.update { it.copy(busy = true) }
            runCatching { withContext(Dispatchers.IO) { syncRepository.syncOnce() } }
                .onSuccess { r ->
                    val msg = when (r) {
                        is SyncRepository.Result.NotPaired -> "尚未配对，请先扫描电脑上的配对码"
                        is SyncRepository.Result.Ok ->
                            "同步完成：上传 ${r.pushed} 条（重复 ${r.duplicated} 条）" +
                                if (r.pulled > 0) "，收到任务 ${r.pulled} 个" else ""
                    }
                    _syncUi.update { it.copy(busy = false, message = msg) }
                    refreshSyncState()
                }
                .onFailure { e ->
                    _syncUi.update {
                        it.copy(busy = false, message = "同步失败：${e.message ?: "网络不通"}")
                    }
                    refreshSyncState()
                }
        }
    }

    fun unpairSync() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { syncRepository.unpair() }
            _syncUi.update {
                it.copy(
                    paired = false, baseUrl = "", serverTime = "",
                    ruleLibHasData = false, ruleLibServerRev = 0, ruleLibLocalRev = 0,
                    ruleLibServerModels = 0, ruleLibServerRules = 0,
                    ruleLibUpdatedAt = "", ruleLibUpdatedBy = "", ruleLibError = "",
                    message = "已解除配对"
                )
            }
            refreshSyncState()
        }
    }

    // ---------- 规则库同步（v1.9.0） ----------

    /** 查电脑端规则库版本/条数（只读） */
    fun refreshRuleLibInfo() {
        if (!_syncUi.value.paired) return
        viewModelScope.launch {
            _syncUi.update { it.copy(ruleLibBusy = true, ruleLibError = "") }
            runCatching { withContext(Dispatchers.IO) { syncRepository.fetchRuleLibrary() } }
                .onSuccess { snap ->
                    val localRev = syncStateDao.get(SyncState.K_RULELIB_SYNCED_REV)?.toIntOrNull() ?: 0
                    val localModels = ruleModelDao.getAll().size
                    val localRules = ruleLibDao.getAll().size
                    _syncUi.update {
                        it.copy(
                            ruleLibBusy = false,
                            ruleLibHasData = snap.hasData,
                            ruleLibServerRev = snap.rev,
                            ruleLibLocalRev = localRev,
                            ruleLibServerModels = snap.models.size,
                            ruleLibServerRules = snap.rules.size,
                            ruleLibUpdatedAt = snap.updatedAt,
                            ruleLibUpdatedBy = snap.updatedBy,
                            ruleLibLocalModels = localModels,
                            ruleLibLocalRules = localRules,
                            ruleLibError = ""
                        )
                    }
                }
                .onFailure { e ->
                    _syncUi.update {
                        it.copy(
                            ruleLibBusy = false,
                            ruleLibError = "读取电脑端规则库失败：${e.message ?: "网络不通"}"
                        )
                    }
                }
        }
    }

    /** 上传本机规则库 → 电脑端（整库替换；其他 PDA 可下载） */
    fun uploadRuleLibrary() {
        viewModelScope.launch {
            _syncUi.update { it.copy(ruleLibBusy = true, ruleLibError = "") }
            runCatching { withContext(Dispatchers.IO) { syncRepository.uploadRuleLibrary() } }
                .onSuccess { snap ->
                    val localModels = ruleModelDao.getAll().size
                    val localRules = ruleLibDao.getAll().size
                    _syncUi.update {
                        it.copy(
                            ruleLibBusy = false,
                            ruleLibHasData = true,
                            ruleLibServerRev = snap.rev,
                            ruleLibLocalRev = snap.rev,
                            ruleLibServerModels = snap.models.size,
                            ruleLibServerRules = snap.rules.size,
                            ruleLibUpdatedAt = snap.updatedAt,
                            ruleLibUpdatedBy = snap.updatedBy,
                            ruleLibLocalModels = localModels,
                            ruleLibLocalRules = localRules,
                            message = "已上传规则库到电脑：rev ${snap.rev}，" +
                                "${snap.models.size} 个型号 / ${snap.rules.size} 条规则\n\n" +
                                "其他 PDA 可在「WiFi 同步 → 规则库同步」点「下载并覆盖本机规则库」。"
                        )
                    }
                }
                .onFailure { e ->
                    _syncUi.update {
                        it.copy(ruleLibBusy = false, message = "上传规则库失败：${e.message ?: "网络不通"}")
                    }
                }
        }
    }

    /** 下载电脑端规则库 → 整库覆盖本机（先自动备份；已装柜的柜子不受影响） */
    fun downloadRuleLibrary() {
        viewModelScope.launch {
            _syncUi.update { it.copy(ruleLibBusy = true, ruleLibError = "") }
            runCatching { withContext(Dispatchers.IO) { syncRepository.downloadRuleLibrary() } }
                .onSuccess { r ->
                    val msg = buildString {
                        append("已下载规则库 rev ${r.snapshot.rev}：")
                        append("${r.modelCount} 个型号 / ${r.ruleCount} 条规则")
                        if (r.backup != null) {
                            append("\n\n覆盖前已备份本机规则库：\n")
                            append("${RuleExporter.displayDir()}/${r.backup.name}")
                        }
                        append("\n\n已装柜的柜子不受影响（使用各自的规则快照）。")
                    }
                    _syncUi.update {
                        it.copy(
                            ruleLibBusy = false,
                            ruleLibHasData = true,
                            ruleLibServerRev = r.snapshot.rev,
                            ruleLibLocalRev = r.snapshot.rev,
                            ruleLibServerModels = r.modelCount,
                            ruleLibServerRules = r.ruleCount,
                            ruleLibUpdatedAt = r.snapshot.updatedAt,
                            ruleLibUpdatedBy = r.snapshot.updatedBy,
                            ruleLibLocalModels = r.modelCount,
                            ruleLibLocalRules = r.ruleCount,
                            message = msg
                        )
                    }
                }
                .onFailure { e ->
                    _syncUi.update {
                        it.copy(
                            ruleLibBusy = false,
                            message = "下载规则库失败（本机规则库未修改）：${e.message ?: "网络不通"}"
                        )
                    }
                }
        }
    }

    fun clearSyncMessage() {
        _syncUi.update { it.copy(message = null) }
    }

    // ==================== 出库扫码：设置阶段 ====================

    fun onContainerNoChange(v: String) {
        // 柜号只允许字母+数字
        val clean = v.filter { it.isLetterOrDigit() }.uppercase()
        _setup.update {
            it.copy(
                containerNo = clean,
                scannedQuantity = if (clean.isBlank()) 0 else it.scannedQuantity,
                storedPlan = if (clean.isBlank()) 0 else it.storedPlan
            )
        }
        scheduleContainerLookup(clean)
    }

    /**
     * 柜号输入后（防抖 150ms）查询已有柜号：
     *  - 命中已有柜号 → 载入原规则；带出「已扫码数量」（今天已装正常数，只读展示），
     *    并回显「计划装柜数量」（v1.7.0：选择前面已有的柜号 → 显示前期存的计划装柜数量）
     *  - 未命中 → 清空展示值与已存计划（保持人工填写的计划与已有规则）
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
                _setup.update { s ->
                    if (s.containerNo != clean) s else s.copy(scannedQuantity = 0, storedPlan = 0)
                }
                return@launch
            }
            // 「今天已装数」：只算今天的有效记录（已删除的、昨天的都不参与）
            val loaded = recordDao.countByResultOnDay(existing.id, ScanResult.OK.name, todayLabel())
            val rules = ruleDao.getByContainer(existing.id)
                .map {
                    RuleSpec(
                        prefix = it.prefix,
                        allowDuplicate = it.allowDuplicate,
                        countEnabled = it.countEnabled,
                        checkHistory = it.checkHistory,
                        serialMin = it.serialMin,
                        serialMax = it.serialMax,
                        serialTailLen = it.serialTailLen,
                        ignoreRepeat = it.ignoreRepeat
                    )
                }
                .distinctBy { it.prefix }
            _setup.update { s ->
                if (s.containerNo != clean) s
                else s.copy(
                    rules = rules,
                    // 已扫码数量 = 今天已装正常数（只读展示，不写入计划字段）
                    scannedQuantity = loaded,
                    // 回显前期存的计划装柜数量（v1.7.0；该柜未设计划时保持人工输入）
                    totalQuantity = if (existing.totalQuantity > 0) existing.totalQuantity.toString() else s.totalQuantity,
                    // 已存计划：用于提示与留空续装兜底
                    storedPlan = existing.totalQuantity
                )
            }
        }
    }

    fun onTotalQuantityChange(v: String) {
        _setup.update { it.copy(totalQuantity = v.filter { c -> c.isDigit() }.take(6)) }
    }

    /** 操作员工号（选填）：字母+数字、自动大写、≤12 位；随每次修改持久化（下次进入预填） */
    fun onOperatorIdChange(v: String) {
        val clean = v.filter { it.isLetterOrDigit() }.uppercase().take(12)
        _setup.update { it.copy(operatorId = clean) }
        operatorPrefs.edit().putString(KEY_LAST_OPERATOR, clean).apply()
    }

    /** 设置页焦点变化：工号栏聚焦时扫码键填入工号，其余位置填入规则栏（原行为） */
    fun onSetupScanTarget(target: SetupScanTarget) {
        _setupScanTarget.value = target
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

    /** 新增规则时填：流水号最小值（选填；与前缀组成数据范围） */
    fun onRuleSerialMinChange(v: String) {
        _setup.update { it.copy(ruleSerialMin = v.filter { c -> c.isDigit() }.take(12)) }
    }

    /** 新增规则时填：流水号最大值（选填） */
    fun onRuleSerialMaxChange(v: String) {
        _setup.update { it.copy(ruleSerialMax = v.filter { c -> c.isDigit() }.take(12)) }
    }

    /** 新增规则时填：流水号取末尾 N 位（0/空 = 前缀后整段；上限 6 位） */
    fun onRuleSerialTailLenChange(v: String) {
        _setup.update { it.copy(ruleSerialTailLen = v.filter { c -> c.isDigit() }.take(2)) }
    }

    /** 新增规则时的勾选：忽略（仅「可重复扫」时生效；扫到不记录、不落盘、不计数） */
    fun onRuleIgnoreChange(v: Boolean) {
        _setup.update { it.copy(ruleIgnoreRepeat = v) }
    }

    fun addRule() {
        val prefix = _setup.value.ruleInput.trim()
        if (prefix.isEmpty()) return
        _setup.update { s ->
            if (s.rules.any { it.prefix == prefix }) s.copy(ruleInput = "")
            else s.copy(
                rules = s.rules + RuleSpec(
                    prefix = prefix,
                    allowDuplicate = s.ruleAllowDup,
                    countEnabled = s.ruleCount,
                    checkHistory = s.ruleCheckHistory,
                    serialMin = s.ruleSerialMin,
                    serialMax = s.ruleSerialMax,
                    serialTailLen = s.ruleSerialTailLen.toIntOrNull() ?: 0,
                    ignoreRepeat = s.ruleAllowDup && s.ruleIgnoreRepeat
                ),
                ruleInput = "",
                ruleSerialMin = "",
                ruleSerialMax = "",
                ruleSerialTailLen = ""
            )
        }
    }

    fun removeRule(rule: String) {
        _setup.update { it.copy(rules = it.rules.filterNot { r -> r.prefix == rule }) }
    }

    /** 列表里直接改某条规则的勾选（可重复 / 计入统计 / 查历史串号 / 忽略） */
    fun updateRuleFlags(
        prefix: String,
        allowDuplicate: Boolean,
        countEnabled: Boolean,
        checkHistory: Boolean,
        ignoreRepeat: Boolean
    ) {
        _setup.update { s ->
            s.copy(rules = s.rules.map {
                if (it.prefix == prefix) it.copy(
                    allowDuplicate = allowDuplicate,
                    countEnabled = countEnabled,
                    checkHistory = checkHistory,
                    ignoreRepeat = allowDuplicate && ignoreRepeat
                )
                else it
            })
        }
    }

    /**
     * 列表里补录/修改某条规则（含从规则库导入的）的流水号范围；
     * 两项都留空 = 不限制。只保留数字（与录入时一致），避免非法值导致全部条码报串号。
     * [serialTailLen] > 0 = 取条码末尾 N 位数字作为流水号（忽略末尾符号），0 = 前缀后整段。
     */
    fun updateRuleSerial(prefix: String, serialMin: String, serialMax: String, serialTailLen: Int) {
        val mn = serialMin.filter { it.isDigit() }.take(12)
        val mx = serialMax.filter { it.isDigit() }.take(12)
        val tail = serialTailLen.coerceIn(0, 20)
        _setup.update { s ->
            s.copy(rules = s.rules.map {
                if (it.prefix == prefix) it.copy(
                    serialMin = mn, serialMax = mx, serialTailLen = tail
                ) else it
            })
        }
    }

    /** 规则选择：把规则库里勾选的前缀批量加入本柜（自动去重；连同库中四个开关一起复制，可在列表里改） */
    fun addRulesFromSetup(prefixes: List<String>) {
        if (prefixes.isEmpty()) return
        viewModelScope.launch {
            val lib = ruleLibDao.getAll().associateBy { it.prefix }
            _setup.update { s ->
                val existing = s.rules.map { it.prefix }.toSet()
                s.copy(rules = s.rules + prefixes.filterNot { it in existing }.map { p ->
                    val e = lib[p]
                    if (e == null) RuleSpec(p)
                    else RuleSpec(
                        prefix = e.prefix,
                        allowDuplicate = e.allowDuplicate,
                        countEnabled = e.countEnabled,
                        checkHistory = e.checkHistory,
                        ignoreRepeat = e.allowDuplicate && e.ignoreRepeat
                    )
                })
            }
        }
    }

    /** 从首页进入出库扫码设置页：整页空白（不保留上次柜号/数量/规则；操作员工号预填上次填写） */
    fun resetOutboundSetup() {
        alarm.stop()
        _outboundStage.value = OutboundStage.SETUP
        _setupScanTarget.value = SetupScanTarget.RULE
        _setup.value = OutboundSetupState(
            operatorId = operatorPrefs.getString(KEY_LAST_OPERATOR, "") ?: ""
        )
    }

    /**
     * 扫码页「返回设置」：柜号 / 计划装柜数量 / 规则原样保留（无需重新选择/录入）；
     * 「已扫码数量」同步为本会话已装数（只读展示）；「计划装柜数量」回显本次会话实际计划（v1.7.0）。
     * 若本柜已装齐（正常数 ≥ 计划装柜数量）→ 清空规则：该柜号再装按全新柜处理。
     */
    fun backToOutboundSetup() {
        alarm.stop()
        _outboundStage.value = OutboundStage.SETUP
        _setupScanTarget.value = SetupScanTarget.RULE
        val st = _outbound.value
        if (st.activeContainer == null) return
        val done = st.total > 0 && st.okCount >= st.total
        _setup.update {
            it.copy(
                rules = if (done) emptyList() else it.rules,
                scannedQuantity = st.okCount,
                // v1.7.0：从扫码页返回 → 显示上次设置的计划装柜数量（会话计划>0 时回显）
                totalQuantity = if (st.total > 0) st.total.toString() else it.totalQuantity,
                storedPlan = st.total
            )
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
                    val typed = s.totalQuantity.trim()
                    val typedTotal = typed.toIntOrNull() ?: 0
                    // 容器 + 规则快照同步在同一事务，避免中途失败留下半套规则
                    // 计划装柜数量：本次填写优先；留空且该柜今天已扫码（续装）→ 按该柜已存计划继续
                    // （只用于本次会话，绝不改写输入框；v1.6.0 语义修正）
                    val (container, total) = app.database.withTransaction {
                        val existing = containerDao.findByNo(s.containerNo)
                        val total = if (typed.isEmpty() && existing != null &&
                            recordDao.countByResultOnDay(existing.id, ScanResult.OK.name, todayLabel()) > 0
                        ) existing.totalQuantity else typedTotal
                        val c: LoadContainer = if (existing == null) {
                            // 新柜号 → 建装柜会话（柜号唯一约束：同号柜只能复用一个会话行）
                            val id = containerDao.insert(
                                LoadContainer(containerNo = s.containerNo, totalQuantity = total)
                            )
                            LoadContainer(id = id, containerNo = s.containerNo, totalQuantity = total)
                        } else if (existing.totalQuantity != total) {
                            // 已有柜号 → 复用会话行，不新建；计划装柜数量以本次为准
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
                                    checkHistory = it.checkHistory,
                                    serialMin = it.serialMin,
                                    serialMax = it.serialMax,
                                    serialTailLen = it.serialTailLen,
                                    ignoreRepeat = it.ignoreRepeat
                                )
                            )
                        }
                        c to total
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
                        operatorId = s.operatorId,
                        okCount = counts.ok, mismatchCount = counts.mm,
                        duplicateCount = counts.dup, overCount = counts.over,
                        totalRecords = counts.total,
                        lastMessage = when {
                            total > 0 && counts.ok >= total ->
                                "该柜已装齐：已扫码 ${counts.ok} / 计划 $total；再扫码将提示超量"
                            total > 0 && counts.ok > 0 ->
                                "继续装柜：已扫码 ${counts.ok} / 计划 $total"
                            counts.ok > 0 ->
                                "继续装柜：已扫码 ${counts.ok} 件（计划不限）"
                            counts.total > 0 -> "继续装柜：今天已录 ${counts.total} 条"
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

    // ---------- 扫码页「震动和声音设置」（v1.7.0：正常/非正常分组，开关 + 震动强度 + 语音朗读） ----------

    /** 正常扫码蜂鸣开关（默认开） */
    private val _alarmSoundOk = MutableStateFlow(alarm.soundOkEnabled)
    val alarmSoundOk: StateFlow<Boolean> = _alarmSoundOk.asStateFlow()

    /** 非正常扫码（串号/重复/超量）蜂鸣开关（默认开） */
    private val _alarmSoundError = MutableStateFlow(alarm.soundErrorEnabled)
    val alarmSoundError: StateFlow<Boolean> = _alarmSoundError.asStateFlow()

    /** 正常扫码振动强度（0=关…3=强） */
    private val _alarmVibOk = MutableStateFlow(alarm.vibrateOkLevel)
    val alarmVibOk: StateFlow<Int> = _alarmVibOk.asStateFlow()

    /** 非正常扫码振动强度（0=关…3=强） */
    private val _alarmVibError = MutableStateFlow(alarm.vibrateErrorLevel)
    val alarmVibError: StateFlow<Int> = _alarmVibError.asStateFlow()

    /** 语音朗读开关（默认开：正常读出「已扫码，N」；辅助码/忽略读出「辅助码」） */
    private val _alarmSpeak = MutableStateFlow(alarm.speakEnabled)
    val alarmSpeak: StateFlow<Boolean> = _alarmSpeak.asStateFlow()

    fun setAlarmSoundOk(enabled: Boolean) {
        alarm.soundOkEnabled = enabled
        _alarmSoundOk.value = enabled
    }

    fun setAlarmSoundError(enabled: Boolean) {
        alarm.soundErrorEnabled = enabled
        _alarmSoundError.value = enabled
    }

    fun setAlarmVibOk(level: Int) {
        alarm.vibrateOkLevel = level
        _alarmVibOk.value = alarm.vibrateOkLevel
    }

    fun setAlarmVibError(level: Int) {
        alarm.vibrateErrorLevel = level
        _alarmVibError.value = alarm.vibrateErrorLevel
    }

    fun setAlarmSpeak(enabled: Boolean) {
        alarm.speakEnabled = enabled
        _alarmSpeak.value = enabled
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
                    .also { SyncScheduler.requestSync(app) }
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
                SyncScheduler.requestSync(app)
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
                SyncScheduler.requestSync(app)
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
                SyncScheduler.requestSync(app)
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
                SyncScheduler.requestSync(app)
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
     * 出库扫码校验：规则前缀（串号）→ 同柜重复 → 计划装柜数量（超量）→ 正常。
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

                    // 前缀匹配 + 流水号范围（v1.7.0 第 1 条：规则可限定流水号区间；未填范围则不校验）
                    val prefixSpecs = st.rules.filter { barcode.startsWith(it.prefix) }
                    val matchedSpecs = prefixSpecs.filter {
                        serialInRange(
                            barcode.removePrefix(it.prefix),
                            it.serialMin, it.serialMax, it.serialTailLen
                        )
                    }
                    val matched = st.rules.isEmpty() || matchedSpecs.isNotEmpty()
                    val rangeFiltered = prefixSpecs.isNotEmpty() && matchedSpecs.isEmpty()

                    // 忽略（v1.7.0 第 6 条）：可重复扫且勾选「忽略」的条码 → 不记录、不落盘、不计数，
                    // 仅正常反馈 + 语音提示「辅助码」（双码机固定码不再堆积噪声记录）
                    val ignored = matchedSpecs.any { it.allowDuplicate && it.ignoreRepeat }
                    if (ignored) {
                        alarm.success()
                        alarm.speakAux()
                        _outbound.update { cur ->
                            if (cur.activeContainer?.id != container.id) cur
                            else cur.copy(
                                lastFullCode = "${container.containerNo}，$barcode",
                                lastBarcode = barcode,
                                lastMessage = "辅助码（忽略，不记录）：$barcode",
                                lastResult = ScanResult.AUX,
                                lastRecordId = 0,
                                scanSeq = cur.scanSeq + 1,
                                screenColor = Color(0xFFE3F2FD)
                            )
                        }
                        return@withLock
                    }

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
                            when {
                                historyHit != null ->
                                    "串号报警！该条码已于 " +
                                        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(historyHit.scannedAt) +
                                        " 在柜 ${historyHit.containerNo} 录入过，请核实是否混入本柜"
                                rangeFiltered -> {
                                    // v1.7.0：把规则的流水号范围带进提示，方便现场核对（多前缀命中时取最长前缀）
                                    val spec = prefixSpecs.maxByOrNull { it.prefix.length }
                                    val range = when {
                                        spec == null -> ""
                                        spec.serialMin.isNotBlank() && spec.serialMax.isNotBlank() ->
                                            "${spec.serialMin}~${spec.serialMax}"
                                        spec.serialMin.isNotBlank() -> "≥${spec.serialMin}"
                                        spec.serialMax.isNotBlank() -> "≤${spec.serialMax}"
                                        else -> ""
                                    }
                                    val suffix = if (range.isBlank()) "" else {
                                        val tail = if (spec != null && spec.serialTailLen > 0)
                                            "末${spec.serialTailLen}位 " else ""
                                        "（$tail$range）"
                                    }
                                    "串号报警！条码流水号不在规则范围$suffix：$barcode"
                                }
                                else -> "串号报警！条码不符合本柜规则：$barcode"
                            }
                        ScanResult.DUPLICATE -> "重复扫码！该条码已录入过：$barcode"
                        ScanResult.AUX -> "辅助码（不计入本柜统计）：$barcode"
                        ScanResult.OVER_LIMIT -> "超出计划装柜数量（${st.total}）！条码：$barcode"
                        else -> "装柜成功"
                    }

                    val fullCode = "${container.containerNo}，$barcode"
                    val record = ScanRecord(
                        containerId = container.id,
                        containerNo = container.containerNo,
                        barcode = barcode,
                        fullCode = fullCode,
                        result = result,
                        scannedAt = Date(),
                        operatorId = st.operatorId
                    )
                    val recordId = recordDao.insert(record)

                    // WiFi 同步（v1.8.0）：5 秒合并触发一次上传；未配对时 Worker 自动跳过
                    SyncScheduler.requestSync(app)

                    // 本枪进「撤回」栈（撤回可撤销本次扫描）
                    pushOp(RecordOp(RecordOp.Kind.INSERT, listOf(recordId)))

                    // 每扫一码自动落盘（增量追加一行）
                    val saved = withContext(Dispatchers.IO) { FileExporter.append(record) }

                    // 报警反馈（重复/串号/超量 = 循环蜂鸣 + 循环振动，直到被确认/触屏/下一枪/删除）
                    // + 语音朗读（v1.7.0 第 7 条：正常读出「已扫码，N」，辅助码读出「辅助码」）
                    when (result) {
                        ScanResult.OK -> {
                            alarm.success()
                            alarm.speakOk(st.okCount + 1)
                        }
                        ScanResult.AUX -> {
                            alarm.success()
                            alarm.speakAux()
                        }
                        ScanResult.OVER_LIMIT -> alarm.overLimit()
                        else -> alarm.error()
                    }

                    // 本枪刚好装齐（正常数首次达到计划装柜数量）→ 追加一行到装柜完成汇总 + 置完成事件（界面弹一次「装柜完成」，v1.5.8）
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
                                totalRecords = st.totalRecords + 1,
                                operatorId = st.operatorId
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
                SyncScheduler.requestSync(app)
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

    /**
     * 规则编辑：新增（id = null；前缀栏支持多个前缀：逗号/空格/换行分隔，同型号一次挂多条）
     * 或修改一条库中规则。四个开关与出库扫码规则设置对齐（可重复扫 / 计入统计 / 查历史串号 / 忽略）。
     */
    fun saveLibRule(
        id: Long?,
        prefixInput: String,
        name: String,
        allowDuplicate: Boolean,
        countEnabled: Boolean,
        checkHistory: Boolean,
        ignoreRepeat: Boolean
    ) {
        val cleanName = name.trim()
        val ignore = allowDuplicate && ignoreRepeat   // 忽略仅「可重复扫」时生效
        val parts = prefixInput.split(',', '，', '、', ';', '；', ' ', '\n', '\t').filter { it.isNotBlank() }
        val cleaned = parts.mapNotNull { RuleSourceImporter.cleanPrefix(it) }
        if (cleaned.isEmpty()) {
            _ruleLibMessage.value = "前缀无效：仅支持字母+数字（0-9 / A-Z）"
            return
        }
        if (id != null && cleaned.size > 1) {
            _ruleLibMessage.value = "编辑时仅支持单条前缀（如需多条：删掉本条后重新新增）"
            return
        }
        viewModelScope.launch {
            if (id == null) {
                var added = 0
                val conflicts = mutableListOf<String>()
                app.database.withTransaction {
                    if (cleanName.isNotBlank()) {
                        // 规则挂在型号下：型号行不存在则补建（导入/手动新增都走这里）
                        ruleModelDao.insert(RuleModel(name = cleanName))
                    }
                    val existing = ruleLibDao.getAll().map { it.prefix }.toMutableSet()
                    cleaned.forEach { p ->
                        if (p in existing) {
                            conflicts.add(p)
                        } else {
                            ruleLibDao.upsert(
                                RuleLibEntry(
                                    prefix = p, name = cleanName,
                                    allowDuplicate = allowDuplicate, countEnabled = countEnabled,
                                    checkHistory = checkHistory, ignoreRepeat = ignore
                                )
                            )
                            existing.add(p)
                            added++
                        }
                    }
                }
                if (conflicts.isNotEmpty()) {
                    _ruleLibMessage.value = buildString {
                        append("新增 $added 条")
                        append("\n前缀已存在（未新增）：${conflicts.joinToString("、")}")
                        append("\n（可在列表中点该行「编辑」改设置）")
                    }
                }
            } else {
                val p = cleaned.first()
                val conflict = ruleLibDao.getAll().any { it.prefix == p && it.id != id }
                if (conflict) {
                    _ruleLibMessage.value = "前缀已存在：$p"
                    return@launch
                }
                ruleLibDao.updateEntry(
                    RuleLibEntry(
                        id = id, prefix = p, name = cleanName,
                        allowDuplicate = allowDuplicate, countEnabled = countEnabled,
                        checkHistory = checkHistory, ignoreRepeat = ignore,
                        updatedAt = Date()
                    )
                )
            }
        }
    }

    fun deleteLibRule(id: Long) {
        viewModelScope.launch { ruleLibDao.delete(id) }
    }

    // ---------- 规则库型号（v1.7.5：先建型号，再逐条添加规则） ----------

    /** 新增型号（名称唯一；允许暂无规则） */
    fun addRuleModel(name: String) {
        val n = name.trim()
        if (n.isEmpty()) {
            _ruleLibMessage.value = "请输入型号名称"
            return
        }
        viewModelScope.launch {
            val exists = ruleModelDao.findByName(n) != null || ruleLibDao.getAll().any { it.name == n }
            if (exists) {
                _ruleLibMessage.value = "型号已存在：$n"
                return@launch
            }
            ruleModelDao.insert(RuleModel(name = n))
        }
    }

    /** 型号改名：同步更新该型号下全部规则的名称 */
    fun renameRuleModel(id: Long, oldName: String, newName: String) {
        val n = newName.trim()
        if (n.isEmpty()) {
            _ruleLibMessage.value = "型号名称不能为空"
            return
        }
        if (n == oldName) return
        viewModelScope.launch {
            val exists = ruleModelDao.findByName(n) != null || ruleLibDao.getAll().any { it.name == n }
            if (exists) {
                _ruleLibMessage.value = "型号已存在：$n"
                return@launch
            }
            app.database.withTransaction {
                val now = Date()
                ruleModelDao.rename(id, n, now)
                if (oldName.isNotBlank()) ruleLibDao.renameModelRules(oldName, n, now)
            }
        }
    }

    /** 删除型号：连同其下全部规则一起删除 */
    fun deleteRuleModel(id: Long, name: String) {
        viewModelScope.launch {
            app.database.withTransaction {
                if (name.isNotBlank()) ruleLibDao.deleteByModelName(name)
                ruleModelDao.delete(id)
            }
        }
    }

    /**
     * 规则源导入（v1.8.3）：解析 .xlsx/.xls/.csv/.txt，先生成「新增 / 覆盖」预览，
     * 用户在弹窗里核对并确认后才写入；取消不改规则库、不生成备份。
     * 备份在确认后、写库前执行（失败则整体取消）；结果通过 [ruleLibMessage] 提示。
     */
    fun importRuleSource(context: Context, uri: Uri) {
        viewModelScope.launch {
            _ruleImporting.value = true
            try {
                val parsed = withContext(Dispatchers.IO) { RuleSourceImporter.parse(context, uri) }
                if (parsed.isEmpty()) {
                    _ruleLibMessage.value = "未解析到有效规则。\n格式要求：第一列=条码前缀（字母+数字），第二列可选=名称，" +
                        "第 3~6 列可选=可重复扫/计入统计/查历史串号/忽略（1/0）"
                    return@launch
                }
                val ruleRows = parsed.filter { it.prefix.isNotBlank() }
                val modelNames = parsed.map { it.name.trim() }.filter { it.isNotBlank() }.distinct()
                val oldModels = ruleModelDao.getAll()
                val oldRules = ruleLibDao.getAll()
                val existing = oldRules.associateBy { it.prefix }
                val overwrite = ruleRows.filter { it.prefix in existing }
                val existingNames = (oldModels.map { it.name } + oldRules.map { it.name }).toSet()
                _ruleImportPreview.value = RuleImportPreview(
                    rules = ruleRows,
                    modelNames = modelNames,
                    newRules = ruleRows.size - overwrite.size,
                    overwriteRules = overwrite.size,
                    newModels = modelNames.count { it !in existingNames },
                    overwriteSamples = overwrite.map { it.prefix }.take(12)
                )
            } catch (e: Exception) {
                _ruleLibMessage.value = "导入失败（规则库未修改）：${e.message ?: "文件无法解析"}"
            } finally {
                _ruleImporting.value = false
            }
        }
    }

    /** 确认导入（v1.8.3）：先按日期备份旧规则库，再写入；备份失败则整体取消 */
    fun confirmRuleImport() {
        val preview = _ruleImportPreview.value ?: return
        _ruleImportPreview.value = null
        viewModelScope.launch {
            _ruleImporting.value = true
            val result = runCatching {
                val oldModels = ruleModelDao.getAll()
                val oldRules = ruleLibDao.getAll()
                // 覆盖导入前先按日期备份当前规则库；备份失败则不导入，避免旧内容丢失
                val backup = withContext(Dispatchers.IO) {
                    RuleExporter.backupBeforeImport(oldModels, oldRules)
                }
                var added = 0
                var updated = 0
                app.database.withTransaction {
                    preview.modelNames.forEach { ruleModelDao.insert(RuleModel(name = it)) }
                    val existing = oldRules.associateBy { it.prefix }
                    preview.rules.forEach { p ->
                        val old = existing[p.prefix]
                        if (old != null) updated++ else added++
                        // 导出的 XLSX 带开关 → 以文件为准；普通两列文件 → 保留库内原开关
                        val keep = p.hasFlags || old == null
                        ruleLibDao.upsert(
                            RuleLibEntry(
                                prefix = p.prefix,
                                name = p.name,
                                allowDuplicate = if (keep) p.allowDuplicate else old.allowDuplicate,
                                countEnabled = if (keep) p.countEnabled else old.countEnabled,
                                checkHistory = if (keep) p.checkHistory else old.checkHistory,
                                ignoreRepeat = if (keep) p.ignoreRepeat else old.ignoreRepeat,
                                updatedAt = Date()
                            )
                        )
                    }
                }
                buildString {
                    append("导入完成：新增 $added 条，覆盖 $updated 条，共 ${preview.rules.size} 条")
                    if (preview.modelNames.isNotEmpty()) append("；型号 ${preview.modelNames.size} 个")
                    if (backup != null) {
                        append("\n\n导入前已备份（含日期，不覆盖旧文件）：\n")
                        append("${RuleExporter.displayDir()}/${backup.name}")
                    }
                }
            }.getOrElse { "导入失败（规则库未修改）：${it.message ?: "无法写入规则库"}" }
            _ruleImporting.value = false
            _ruleLibMessage.value = result
        }
    }

    /** 取消待确认的规则导入 */
    fun cancelRuleImport() {
        _ruleImportPreview.value = null
    }

    /**
     * 规则库导出（v1.7.5）：XLSX（型号名称 / 前缀 / 四个开关），
     * 拷到其他 PDA 后用「导入」选该文件即可完整还原。
     */
    fun exportRuleLibrary() {
        viewModelScope.launch {
            _ruleExporting.value = true
            val msg = runCatching {
                val models = ruleModelDao.getAll()
                val rules = ruleLibDao.getAll()
                if (models.isEmpty() && rules.isEmpty()) {
                    "规则库为空，没有可导出的规则"
                } else {
                    val file = withContext(Dispatchers.IO) { RuleExporter.export(models, rules) }
                    "已导出 ${models.size} 个型号 / ${rules.size} 条规则：\n" +
                        "${RuleExporter.displayDir()}/${file.name}\n\n" +
                        "拷贝到其他 PDA 后，在「规则库」点「导入」选择该文件即可（含型号与四个开关）。"
                }
            }.getOrElse { "导出失败：${it.message ?: "无法写入存储，请检查存储权限"}" }
            _ruleExporting.value = false
            _ruleLibMessage.value = msg
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
        const val KEY_LAST_OPERATOR = "last_operator"
    }
}
