package com.zhuanggui.pda.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zhuanggui.pda.ui.screen.HomeScreen
import com.zhuanggui.pda.ui.screen.OutboundScanScreen
import com.zhuanggui.pda.ui.screen.OutboundSetupScreen
import com.zhuanggui.pda.ui.screen.RecordsScreen
import com.zhuanggui.pda.ui.screen.ScanScreen
import com.zhuanggui.pda.ui.screen.WorkOrderListScreen

object Routes {
    const val HOME = "home"
    const val OUTBOUND_SETUP = "outbound_setup"
    const val OUTBOUND_SCAN = "outbound_scan"
    const val RECORDS = "records"
    const val SCAN = "scan"          // 老流程：Excel 工单扫码
    const val WORK_ORDERS = "work_orders"
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val navController = rememberNavController()
    val scanState by vm.scanState.collectAsStateWithLifecycle()
    val workOrders by vm.workOrders.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()

    // 出库扫码
    val outboundStage by vm.outboundStage.collectAsStateWithLifecycle()
    val setup by vm.setup.collectAsStateWithLifecycle()
    val outbound by vm.outbound.collectAsStateWithLifecycle()
    val outboundRecords by vm.outboundRecords.collectAsStateWithLifecycle()

    // 扫码记录
    val allRecords by vm.allRecords.collectAsStateWithLifecycle()

    NavHost(navController = navController, startDestination = Routes.HOME) {

        // ---------- 首页 ----------
        composable(Routes.HOME) {
            HomeScreen(
                onOutbound = {
                    vm.resetOutboundSetup()
                    navController.navigate(Routes.OUTBOUND_SETUP)
                },
                onLegacy = { navController.navigate(Routes.WORK_ORDERS) },
                onRecords = { navController.navigate(Routes.RECORDS) }
            )
        }

        // ---------- 出库扫码：设置 ----------
        composable(Routes.OUTBOUND_SETUP) {
            OutboundSetupScreen(
                state = setup,
                onContainerNo = vm::onContainerNoChange,
                onTotal = vm::onTotalQuantityChange,
                onRuleInput = vm::onRuleInputChange,
                onAddRule = vm::addRule,
                onRemoveRule = vm::removeRule,
                onStart = {
                    val ok = vm.startOutboundScan()
                    if (ok) navController.navigate(Routes.OUTBOUND_SCAN)
                    ok
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ---------- 出库扫码：扫码 ----------
        composable(Routes.OUTBOUND_SCAN) {
            OutboundScanScreen(
                state = outbound,
                records = outboundRecords,
                onBackToSetup = {
                    vm.resetOutboundSetup()
                    navController.popBackStack()
                },
                onManual = vm::manualOutboundScan,
                onTrigger = vm::triggerScan
            )
        }

        // ---------- 扫码记录 ----------
        composable(Routes.RECORDS) {
            RecordsScreen(
                records = allRecords,
                onBack = { navController.popBackStack() }
            )
        }

        // ---------- 老流程（Excel 工单） ----------
        composable(Routes.WORK_ORDERS) {
            WorkOrderListScreen(
                workOrders = workOrders,
                onSelect = { order ->
                    vm.selectOrder(order)
                    navController.navigate(Routes.SCAN)
                },
                onCrate = { name, container, items ->
                    vm.createOrderAndLoad(name, container, items)
                },
                vm = vm,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SCAN) {
            ScanScreen(
                state = scanState,
                items = items,
                onBack = { navController.popBackStack() },
                onExport = { out -> vm.exportLogs(out) },
                vm = vm
            )
        }
    }
}
