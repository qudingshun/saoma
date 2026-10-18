package com.zhuanggui.pda.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhuanggui.pda.ZhuangGuiApp
import com.zhuanggui.pda.data.entity.LoadContainer
import com.zhuanggui.pda.data.entity.LoadRule
import com.zhuanggui.pda.data.entity.ScanRecord
import com.zhuanggui.pda.data.entity.ScanResult
import com.zhuanggui.pda.data.entity.WorkOrder
import com.zhuanggui.pda.data.entity.WorkOrderItem
import com.zhuanggui.pda.excel.ExcelService
import com.zhuanggui.pda.export.FileExporter
import com.zhuanggui.pda.scan.AlarmManager
import com.zhuanggui.pda.scan.ScanEngine
import com.zhuanggui.pda.scan.ScannerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Date

/** 扫码模式：计数保存（不需要清单）/ 校验防错（需导入清单） */
enum class ScanMode { COUNT, VERIFY }

/** 出库扫码当前所处阶段 */
enum class OutboundStage { SETUP, SCANNING }

data class ScanUiState(
    val activeOrder: WorkOrder? = null,
    val totalScanned: Int = 0,
    val okCount: Int = 0,
    val errorCount: Int = 0,
    val overLimitCount: Int = 0,
    val lastBarcode: String = "",
    val lastMessage: String = "",
    val screenColor: Color = Color.White,
    val result: ScanResult? = null,
    val mode: ScanMode = ScanMode.COUNT
)

/** 出库扫码：设置阶段状态 */
data class OutboundSetupState(
    val containerNo: String = "",
    val totalQuantity: String = "",            // 本柜数量（留空/0 = 不限）
    val rules: List<String> = emptyList(),     // 条码规则（前缀），拼装柜可多条
    val ruleInput: String = "",
    val resumed: Boolean = false               // 命中已有柜号 = 继续装柜
)

/** 出库扫码：扫码阶段状态 */
data class OutboundScanState(
    val activeContainer: LoadContainer? = null,
    val rules: List<String> = emptyList(),
    val total: Int = 0,                        // 本柜数量（0 = 不限）
    val okCount: Int = 0,                      // 正常件数
    val mismatchCount: Int = 0,                // 串号
    val duplicateCount: Int = 0,               // 重复
    val overCount: Int = 0,                    // 超量
    val lastFullCode: String = "",             // 柜号，条码
    val lastBarcode: String = "",
    val lastMessage: String = "",
    val screenColor: Color = Color.White,
    val fileSaved: Boolean = false,            // 最近一次自动落盘是否成功
    val totalRecords: Int = 0                  // 本柜累计记录数
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZhuangGuiApp
    private val repository = app.repository
    private val engine = ScanEngine(app.database)
    private val alarm = AlarmManager(application)
    private val excel = ExcelService()

    private val scanner = ScannerManager(application)
    private val _scanState = MutableStateFlow(ScanUiState())
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()

    // ---------- 出库扫码 ----------
    private val containerDao = app.database.loadContainerDao()
    private val ruleDao = app.database.loadRuleDao()
    private val recordDao = app.database.scanRecordDao()

    private val _outboundStage = MutableStateFlow(OutboundStage.SETUP)
    val outboundStage: StateFlow<OutboundStage> = _outboundStage.asStateFlow()

    private val _setup = MutableStateFlow(OutboundSetupState())
    val setup: StateFlow<OutboundSetupState> = _setup.asStateFlow()

    private val _outbound = MutableStateFlow(OutboundScanState())
    val outbound: StateFlow<OutboundScanState> = _outbound.asStateFlow()

    /** 当前柜最近记录（扫码页明细滚动） */
    private val _outboundRecords = MutableStateFlow<List<ScanRecord>>(emptyList())
    val outboundRecords: StateFlow<List<ScanRecord>> = _outboundRecords.asStateFlow()

    /** 全部扫码记录（记录查看页） */
    val allRecords: StateFlow<List<ScanRecord>> = recordDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var recordsJob: kotlinx.coroutines.Job? = null

    val workOrders: StateFlow<List<WorkOrder>> = repository.observeWorkOrders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前工单的条码明细（计划数 / 已扫数） */
    private val _items = MutableStateFlow<List<WorkOrderItem>>(emptyList())
    val items: StateFlow<List<WorkOrderItem>> = _items.asStateFlow()

    private var activeOrderId: Long? = null
    private var itemsJob: kotlinx.coroutines.Job? = null

    init {
        scanner.register()
        viewModelScope.launch {
            scanner.scans.collect { barcode ->
                // 出库扫码进行中 → 走装柜校验；否则走原有工单流程
                if (_outboundStage.value == OutboundStage.SCANNING &&
                    _outbound.value.activeContainer != null
                ) {
                    handleOutboundScan(barcode)
                } else {
                    handleScan(barcode)
                }
            }
        }
    }

    // ==================== 出库扫码：设置阶段 ====================

    fun onContainerNoChange(v: String) {
        // 柜号只允许字母+数字
        val clean = v.filter { it.isLetterOrDigit() }.uppercase()
        _setup.value = _setup.value.copy(containerNo = clean)
        // 已有柜号 → 显示“继续装柜”提示并载入其规则
        viewModelScope.launch {
            val existing = containerDao.findByNo(clean)
            if (existing != null) {
                val rules = ruleDao.getByContainer(existing.id).map { it.prefix }
                _setup.value = _setup.value.copy(resumed = true, rules = rules,
                    totalQuantity = if (existing.totalQuantity > 0) existing.totalQuantity.toString()
                    else _setup.value.totalQuantity)
            } else {
                _setup.value = _setup.value.copy(resumed = false)
            }
        }
    }

    fun onTotalQuantityChange(v: String) {
        _setup.value = _setup.value.copy(totalQuantity = v.filter { it.isDigit() }.take(6))
    }

    fun onRuleInputChange(v: String) {
        _setup.value = _setup.value.copy(ruleInput = v.filter { it.isLetterOrDigit() }.uppercase())
    }

    fun addRule() {
        val rule = _setup.value.ruleInput.trim()
        if (rule.isEmpty()) return
        if (_setup.value.rules.any { it == rule }) {
            _setup.value = _setup.value.copy(ruleInput = "")
            return
        }
        _setup.value = _setup.value.copy(
            rules = _setup.value.rules + rule,
            ruleInput = ""
        )
    }

    fun removeRule(rule: String) {
        _setup.value = _setup.value.copy(rules = _setup.value.rules - rule)
    }

    /** 进入出库扫码设置页时复位（保留上次柜号方便连续操作） */
    fun resetOutboundSetup() {
        _outboundStage.value = OutboundStage.SETUP
        _setup.value = OutboundSetupState(containerNo = _setup.value.containerNo)
    }

    /**
     * 点【装柜】：柜号/数量/规则落库（同柜号=继续，规则差异增量同步），进入扫码界面。
     * 返回 false 表示参数不完整。
     */
    fun startOutboundScan(): Boolean {
        val s = _setup.value
        if (s.containerNo.isBlank() || s.rules.isEmpty()) return false
        viewModelScope.launch {
            val total = s.totalQuantity.toIntOrNull() ?: 0
            var container = containerDao.findByNo(s.containerNo)
            if (container == null) {
                val id = containerDao.insert(LoadContainer(containerNo = s.containerNo, totalQuantity = total))
                container = LoadContainer(id = id, containerNo = s.containerNo, totalQuantity = total)
            } else if (container.totalQuantity != total) {
                container = container.copy(totalQuantity = total)
                containerDao.update(container)
            }
            // 规则同步：删掉库里的，写入界面维护的（简单可靠；规则变更留痕在数据库快照）
            ruleDao.getByContainer(container.id).forEach { ruleDao.delete(it.id) }
            s.rules.forEach { ruleDao.insert(LoadRule(containerId = container.id, prefix = it)) }

            // 恢复本柜历史计数（同柜号回来继续装柜，计数不丢）
            val ok = recordDao.countByResult(container.id, ScanResult.OK.name)
            val mm = recordDao.countByResult(container.id, ScanResult.SERIAL_MISMATCH.name)
            val dup = recordDao.countByResult(container.id, ScanResult.DUPLICATE.name)
            val over = recordDao.countByResult(container.id, ScanResult.OVER_LIMIT.name)
            val totalRecords = ok + mm + dup + over

            _outbound.value = OutboundScanState(
                activeContainer = container,
                rules = s.rules,
                total = total,
                okCount = ok, mismatchCount = mm, duplicateCount = dup, overCount = over,
                totalRecords = totalRecords,
                lastMessage = if (totalRecords > 0) "继续装柜：已扫 $totalRecords 条"
                else "开始装柜：扫一个码自动保存一条"
            )
            _outboundStage.value = OutboundStage.SCANNING
            recordsJob?.cancel()
            recordsJob = viewModelScope.launch {
                recordDao.observeByContainer(container.id, 100).collect { _outboundRecords.value = it }
            }
        }
        return true
    }

    // ==================== 出库扫码：扫码校验 ====================

    /** 手动录入（出库扫码页） */
    fun manualOutboundScan(barcode: String) {
        if (barcode.isNotBlank()) handleOutboundScan(barcode.trim())
    }

    private fun handleOutboundScan(raw: String) {
        val st = _outbound.value
        val container = st.activeContainer ?: return
        val barcode = raw.trim()
        if (barcode.isEmpty()) return

        viewModelScope.launch {
            val result: ScanResult
            val message: String

            val matched = st.rules.isEmpty() || st.rules.any { barcode.startsWith(it) }
            val dupCount = recordDao.countBarcode(container.id, barcode)
            val okSoFar = st.okCount

            when {
                !matched -> {
                    result = ScanResult.SERIAL_MISMATCH
                    message = "串号报警！条码不符合本柜规则：$barcode"
                }
                dupCount > 0 -> {
                    result = ScanResult.DUPLICATE
                    message = "重复扫码！该条码已录入过：$barcode"
                }
                st.total in 1..okSoFar -> {
                    result = ScanResult.OVER_LIMIT
                    message = "超出本柜数量（$st.total）！条码：$barcode"
                }
                else -> {
                    result = ScanResult.OK
                    message = "装柜成功"
                }
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
            recordDao.insert(record)

            // 每扫一码自动落盘（增量追加一行）
            val saved = withContext(Dispatchers.IO) { FileExporter.append(record) }

            // 报警反馈
            when (result) {
                ScanResult.OK -> alarm.success()
                ScanResult.OVER_LIMIT -> alarm.overLimit()
                else -> alarm.error()
            }

            _outbound.value = st.copy(
                okCount = if (result == ScanResult.OK) st.okCount + 1 else st.okCount,
                mismatchCount = if (result == ScanResult.SERIAL_MISMATCH) st.mismatchCount + 1 else st.mismatchCount,
                duplicateCount = if (result == ScanResult.DUPLICATE) st.duplicateCount + 1 else st.duplicateCount,
                overCount = if (result == ScanResult.OVER_LIMIT) st.overCount + 1 else st.overCount,
                totalRecords = st.totalRecords + 1,
                lastFullCode = fullCode,
                lastBarcode = barcode,
                lastMessage = message,
                screenColor = when (result) {
                    ScanResult.OK -> Color(0xFFE8F5E9)
                    ScanResult.OVER_LIMIT -> Color(0xFFFFF9C4)
                    else -> Color(0xFFFFCDD2)
                },
                fileSaved = saved
            )
        }
    }

    fun selectOrder(order: WorkOrder) {
        activeOrderId = order.id
        _scanState.value = ScanUiState(activeOrder = order)
        itemsJob?.cancel()
        itemsJob = viewModelScope.launch {
            repository.observeItems(order.id).collect { _items.value = it }
        }
        // 恢复历史计数（重启 App 后重新进入工单，计数不丢失）
        viewModelScope.launch {
            val logs = repository.scanLogsSnapshot().filter { it.workOrderId == order.id }
            val st = _scanState.value
            _scanState.value = st.copy(
                totalScanned = logs.size,
                okCount = logs.count { it.result == ScanResult.OK },
                errorCount = logs.count { it.result == ScanResult.NOT_IN_LIST },
                overLimitCount = logs.count { it.result == ScanResult.OVER_LIMIT }
            )
        }
    }

    /** 手动录入条码（扫码枪不可用时键盘录入，与硬件扫码走同一校验逻辑） */
    fun manualScan(barcode: String) {
        if (barcode.isNotBlank()) handleScan(barcode)
    }

    /** 主动触发 PDA 扫码头扫描一次（界面“扫描”按钮） */
    fun triggerScan() {
        scanner.triggerScan()
    }

    /** 更新当前工单的柜号 / 备注 */
    fun updateOrderInfo(containerNo: String, remark: String) {
        val order = _scanState.value.activeOrder ?: return
        viewModelScope.launch {
            val updated = order.copy(containerNo = containerNo.trim(), remark = remark.trim())
            repository.updateWorkOrder(updated)
            _scanState.value = _scanState.value.copy(activeOrder = updated)
        }
    }

    /** 标记装柜完成 */
    fun finishOrder() {
        val id = activeOrderId ?: return
        viewModelScope.launch {
            repository.updateStatus(id, WorkOrder.STATUS_FINISHED)
            repository.getOrder(id)?.let { order ->
                _scanState.value = _scanState.value.copy(activeOrder = order)
            }
        }
    }

    fun createOrderAndLoad(name: String, containerNo: String, items: List<Pair<String, Int>>) {
        viewModelScope.launch {
            val id = repository.createWorkOrder(name, containerNo)
            repository.addItems(id, items)
            repository.getOrder(id)?.let { selectOrder(it) }
        }
    }

    /** 切换扫码模式（计数保存 / 校验防错），切换时清空当前屏显计数与底色 */
    fun setMode(mode: ScanMode) {
        val st = _scanState.value
        _scanState.value = st.copy(
            mode = mode,
            totalScanned = 0, okCount = 0, errorCount = 0, overLimitCount = 0,
            lastBarcode = "", screenColor = Color.White, result = null,
            lastMessage = if (mode == ScanMode.COUNT)
                "计数保存模式：扫码即记录，不校验清单"
            else
                "校验防错模式：条码不在清单内将报警"
        )
    }

    private fun handleScan(raw: String) {
        val id = activeOrderId ?: return
        if (raw.isBlank()) return
        viewModelScope.launch {
            // 计数保存模式：不走清单校验，直接记 OK 日志计数
            val outcome = if (_scanState.value.mode == ScanMode.COUNT) {
                engine.record(id, raw)
            } else {
                engine.process(id, raw)
            }
            val st = _scanState.value
            when (outcome.result) {
                ScanResult.OK -> {
                    alarm.success()
                    _scanState.value = st.copy(
                        totalScanned = st.totalScanned + 1,
                        okCount = st.okCount + 1,
                        lastBarcode = raw,
                        lastMessage = if (st.mode == ScanMode.COUNT)
                            "已记录：$raw"
                        else
                            "扫码成功：$raw",
                        screenColor = Color(0xFFE8F5E9),
                        result = ScanResult.OK
                    )
                }
                ScanResult.NOT_IN_LIST -> {
                    alarm.error()
                    _scanState.value = st.copy(
                        totalScanned = st.totalScanned + 1,
                        errorCount = st.errorCount + 1,
                        lastBarcode = raw,
                        lastMessage = "混料报警！条码不在本柜清单内：$raw",
                        screenColor = Color(0xFFFFCDD2),
                        result = ScanResult.NOT_IN_LIST
                    )
                }
                ScanResult.OVER_LIMIT -> {
                    alarm.overLimit()
                    _scanState.value = st.copy(
                        totalScanned = st.totalScanned + 1,
                        overLimitCount = st.overLimitCount + 1,
                        lastBarcode = raw,
                        lastMessage = "数量超限报警：$raw",
                        screenColor = Color(0xFFFFF9C4),
                        result = ScanResult.OVER_LIMIT
                    )
                }
                // 老工单流程不会产生串号/重复结果，兜底按报警处理
                else -> {
                    alarm.error()
                    _scanState.value = st.copy(
                        totalScanned = st.totalScanned + 1,
                        errorCount = st.errorCount + 1,
                        lastBarcode = raw,
                        lastMessage = "异常扫码：$raw",
                        screenColor = Color(0xFFFFCDD2),
                        result = outcome.result
                    )
                }
            }
        }
    }

    fun exportLogs(output: OutputStream) {
        val id = activeOrderId ?: return
        viewModelScope.launch {
            val logs = repository.scanLogsSnapshot()
            val items = repository.getItemsSnapshot(id)
            val order = repository.getOrder(id)
            excel.exportLogs(output, order?.name ?: "工单", logs.filter { it.workOrderId == id }, items)
        }
    }

    override fun onCleared() {
        scanner.unregister()
        alarm.release()
        super.onCleared()
    }
}
