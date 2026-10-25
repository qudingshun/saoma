package com.zhuanggui.pda.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zhuanggui.pda.ui.screen.HomeScreen
import com.zhuanggui.pda.ui.screen.OpLogScreen
import com.zhuanggui.pda.ui.screen.OutboundScanScreen
import com.zhuanggui.pda.ui.screen.OutboundSetupScreen
import com.zhuanggui.pda.ui.screen.RecordsScreen
import com.zhuanggui.pda.ui.screen.TsvReaderScreen
import com.zhuanggui.pda.ui.screen.RulesScreen
import com.zhuanggui.pda.ui.screen.ScannerSetupScreen
import com.zhuanggui.pda.ui.screen.SyncSettingsScreen
import com.zhuanggui.pda.ui.screen.TrashScreen
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val OUTBOUND_SETUP = "outbound_setup"
    const val OUTBOUND_SCAN = "outbound_scan"
    const val RECORDS = "records"
    const val RULE_LIBRARY = "rule_library"
    const val TRASH = "trash"
    const val SYNC = "sync"
    const val SCANNER_SETUP = "scanner_setup"
    const val OP_LOG = "op_log"
    const val TSV_READER = "tsv_reader"
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 注：各状态流下沉到对应目的地收集（v1.5.5）——离开页面后 WhileSubscribed(5000) 才会真正停止上游查询，
    // 避免记录页 / 回收站的聚合查询在整个前台会话常驻（每扫一码都会重跑全表 GROUP BY）。
    NavHost(navController = navController, startDestination = Routes.HOME) {

        // ---------- 首页 ----------
        composable(Routes.HOME) {
            val lastContainerNo by vm.lastContainerNo.collectAsStateWithLifecycle()
            val syncPending by vm.syncPending.collectAsStateWithLifecycle()
            HomeScreen(
                lastContainerNo = lastContainerNo,
                syncPending = syncPending,
                onOutbound = {
                    vm.resetOutboundSetup()
                    navController.navigate(Routes.OUTBOUND_SETUP)
                },
                onContinueLast = { no ->
                    // 一键继续：复用设置页「柜号变更」（自动载入原规则 + 已扫码数量；计划留空则按该柜已存计划继续）
                    vm.resetOutboundSetup()
                    vm.onContainerNoChange(no)
                    navController.navigate(Routes.OUTBOUND_SETUP)
                },
                onRecords = { navController.navigate(Routes.RECORDS) },
                onSync = {
                    vm.refreshSyncState()
                    navController.navigate(Routes.SYNC)
                },
                onScannerSetup = { navController.navigate(Routes.SCANNER_SETUP) },
                onOpLog = { navController.navigate(Routes.OP_LOG) }
            )
        }

        // ---------- 扫描设置（新机引导 + 自检） ----------
        composable(Routes.SCANNER_SETUP) {
            val lastScan by vm.lastScan.collectAsStateWithLifecycle()
            ScannerSetupScreen(
                lastScan = lastScan,
                onBack = { navController.popBackStack() }
            )
        }

        // ---------- 出库扫码：设置 ----------
        composable(Routes.OUTBOUND_SETUP) {
            val setup by vm.setup.collectAsStateWithLifecycle()
            val ruleLibrary by vm.ruleLibrary.collectAsStateWithLifecycle()
            val ruleModels by vm.ruleModels.collectAsStateWithLifecycle()
            val setupLibMessage by vm.setupLibMessage.collectAsStateWithLifecycle()
            OutboundSetupScreen(
                state = setup,
                library = ruleLibrary,
                ruleModels = ruleModels,
                onContainerNo = vm::onContainerNoChange,
                onTotal = vm::onTotalQuantityChange,
                onOperatorId = vm::onOperatorIdChange,
                onScanTarget = vm::onSetupScanTarget,
                onRuleInput = vm::onRuleInputChange,
                onRuleAllowDup = vm::onRuleAllowDupChange,
                onRuleCount = vm::onRuleCountChange,
                onRuleCheckHistory = vm::onRuleCheckHistoryChange,
                onRuleSerialMin = vm::onRuleSerialMinChange,
                onRuleSerialMax = vm::onRuleSerialMaxChange,
                onRuleSerialTailLen = vm::onRuleSerialTailLenChange,
                onRuleIgnore = vm::onRuleIgnoreChange,
                onAddRule = vm::addRule,
                onRemoveRule = vm::removeRule,
                onRuleFlags = vm::updateRuleFlags,
                onRuleSerial = vm::updateRuleSerial,
                onAddRules = vm::addRulesFromSetup,
                onOpenRuleLibrary = { navController.navigate(Routes.RULE_LIBRARY) },
                saveMessage = setupLibMessage,
                onSaveToRuleLibrary = vm::saveSetupToRuleLibrary,
                onSaveMessageShown = vm::clearSetupLibMessage,
                onStart = {
                    // 落库/恢复计数完成后再导航，避免“界面已开、状态未就绪”丢首枪
                    scope.launch {
                        if (vm.startOutboundScan()) {
                            val current = navController.currentBackStackEntry?.destination?.route
                            if (current != Routes.OUTBOUND_SCAN) {
                                navController.navigate(Routes.OUTBOUND_SCAN)
                            }
                        }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ---------- 出库扫码：扫码 ----------
        composable(Routes.OUTBOUND_SCAN) {
            val outbound by vm.outbound.collectAsStateWithLifecycle()
            val outboundRecords by vm.outboundRecords.collectAsStateWithLifecycle()
            val undoRedo by vm.undoRedo.collectAsStateWithLifecycle()
            val alarmSoundOk by vm.alarmSoundOk.collectAsStateWithLifecycle()
            val alarmSoundError by vm.alarmSoundError.collectAsStateWithLifecycle()
            val alarmVibOk by vm.alarmVibOk.collectAsStateWithLifecycle()
            val alarmVibError by vm.alarmVibError.collectAsStateWithLifecycle()
            val alarmSpeak by vm.alarmSpeak.collectAsStateWithLifecycle()
            // 进程被杀恢复：导航栈还原在扫码页，但 ViewModel 已重置（activeContainer 为空）
            // 正常流程进入本页时 activeContainer 必非空（startOutboundScan 成功后才导航），不会误触发
            if (outbound.activeContainer == null) {
                LaunchedEffect(Unit) {
                    Toast.makeText(context, "上次装柜会话已失效，请重新设置后开始", Toast.LENGTH_LONG).show()
                    if (!navController.popBackStack(Routes.OUTBOUND_SETUP, inclusive = false)) {
                        navController.navigate(Routes.OUTBOUND_SETUP)
                    }
                }
            } else {
                OutboundScanScreen(
                    state = outbound,
                    records = outboundRecords,
                    onBackToSetup = {
                        vm.backToOutboundSetup()
                        navController.popBackStack()
                    },
                    onManual = vm::manualOutboundScan,
                    onStopAlarm = vm::stopAlarm,
                    onDeleteRecord = vm::deleteRecord,
                    onUndo = vm::undoLastRecordOp,
                    onRedo = vm::redoLastRecordOp,
                    canUndo = undoRedo.undoCount > 0,
                    canRedo = undoRedo.redoCount > 0,
                    alarmSoundOk = alarmSoundOk,
                    alarmSoundError = alarmSoundError,
                    alarmVibOk = alarmVibOk,
                    alarmVibError = alarmVibError,
                    alarmSpeak = alarmSpeak,
                    onAlarmSoundOk = vm::setAlarmSoundOk,
                    onAlarmSoundError = vm::setAlarmSoundError,
                    onAlarmVibOk = vm::setAlarmVibOk,
                    onAlarmVibError = vm::setAlarmVibError,
                    onAlarmSpeak = vm::setAlarmSpeak
                )
            }
        }

        // ---------- 扫码记录（两级聚合 + 删除） ----------
        composable(Routes.RECORDS) {
            val recordGroups by vm.recordGroups.collectAsStateWithLifecycle()
            val selectedGroup by vm.selectedGroup.collectAsStateWithLifecycle()
            val selectedGroupRecords by vm.selectedGroupRecords.collectAsStateWithLifecycle()
            // 每次进入记录页都从一级（分组列表）开始，不残留上次的分组详情
            LaunchedEffect(Unit) { vm.openRecordGroup(null) }
            RecordsScreen(
                groups = recordGroups,
                selected = selectedGroup,
                groupRecords = selectedGroupRecords,
                onOpenGroup = vm::openRecordGroup,
                onDeleteGroups = vm::deleteRecordGroups,
                onOpenTrash = { navController.navigate(Routes.TRASH) },
                onOpenTsv = { navController.navigate(Routes.TSV_READER) },
                onBack = { navController.popBackStack() }
            )
        }

        // ---------- TSV 记录阅读器（原始文件） ----------
        composable(Routes.TSV_READER) {
            TsvReaderScreen(onBack = { navController.popBackStack() })
        }

        // ---------- 回收站（隐藏入口：记录页长按标题） ----------
        composable(Routes.TRASH) {
            val trashRecords by vm.trashRecords.collectAsStateWithLifecycle()
            TrashScreen(
                records = trashRecords,
                onRestore = vm::restoreTrashRecord,
                onRestoreAll = vm::restoreAllTrash,
                onEmpty = vm::emptyTrash,
                onBack = { navController.popBackStack() }
            )
        }

        // ---------- 规则库（规则源导入 / 规则编辑） ----------
        composable(Routes.RULE_LIBRARY) {
            val ruleLibrary by vm.ruleLibrary.collectAsStateWithLifecycle()
            val ruleModels by vm.ruleModels.collectAsStateWithLifecycle()
            val ruleImporting by vm.ruleImporting.collectAsStateWithLifecycle()
            val ruleExporting by vm.ruleExporting.collectAsStateWithLifecycle()
            val ruleLibMessage by vm.ruleLibMessage.collectAsStateWithLifecycle()
            val ruleImportPreview by vm.ruleImportPreview.collectAsStateWithLifecycle()
            val ruleScanData by vm.ruleScanData.collectAsStateWithLifecycle()
            RulesScreen(
                models = ruleModels,
                rules = ruleLibrary,
                importing = ruleImporting,
                exporting = ruleExporting,
                message = ruleLibMessage,
                importPreview = ruleImportPreview,
                onMessageShown = vm::clearRuleLibMessage,
                onConfirmImport = vm::confirmRuleImport,
                onCancelImport = vm::cancelRuleImport,
                onSaveModel = vm::saveRuleModel,
                onDeleteModel = vm::deleteRuleModel,
                onSaveRule = { id, prefix, name, allowDup, count, checkHist, ignore ->
                    vm.saveLibRule(id, prefix, name, allowDup, count, checkHist, ignore)
                },
                onDeleteRule = vm::deleteLibRule,
                onImport = { uri -> vm.importRuleSource(context, uri) },
                onExport = vm::exportRuleLibrary,
                scannedPrefix = ruleScanData,
                onRuleScanActive = vm::setRuleScanActive,
                onConsumeRuleScan = vm::consumeRuleScanData,
                onBack = { navController.popBackStack() }
            )
        }


        // ---------- 操作日志（试运行追溯） ----------
        composable(Routes.OP_LOG) {
            OpLogScreen(onBack = { navController.popBackStack() })
        }

        // ---------- WiFi 同步（v1.8.0） ----------
        composable(Routes.SYNC) {
            val syncUi by vm.syncUi.collectAsStateWithLifecycle()
            val pending by vm.syncPending.collectAsStateWithLifecycle()
            SyncSettingsScreen(
                state = syncUi,
                pending = pending,
                onBack = { navController.popBackStack() },
                onStartPairing = vm::startPairing,
                onCancelPairing = vm::cancelPairing,
                onPairManual = { text -> vm.pairWithCode(text) },
                onTest = vm::testSyncConnection,
                onSyncNow = vm::syncNow,
                onUnpair = vm::unpairSync,
                onClearMessage = vm::clearSyncMessage,
                onRefresh = vm::refreshSyncState,
                onRefreshRuleLib = vm::refreshRuleLibInfo,
                onUploadRuleLib = vm::uploadRuleLibrary,
                onDownloadRuleLib = vm::downloadRuleLibrary
            )
        }
    }
}
