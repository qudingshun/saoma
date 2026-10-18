package com.zhuanggui.pda.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanggui.pda.data.entity.WorkOrderItem
import com.zhuanggui.pda.ui.AppViewModel
import com.zhuanggui.pda.ui.ScanMode
import com.zhuanggui.pda.ui.ScanUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

/**
 * 扫码录入主界面（竖屏）。
 * 布局自上而下：
 * 1. 顶部工具条（返回 / 导出报表 / 完工）
 * 2. 工单信息（工单名、柜号、备注 —— 可点击编辑）
 * 3. 计数卡（已扫 / 允许 / 混料 / 超量）
 * 4. 手动录入条码输入框（扫码枪之外的备用录入方式）
 * 5. 最新扫码结果提示（背景随结果变色）
 * 6. 条码明细进度列表（可滚动）
 */
@Composable
fun ScanScreen(
    state: ScanUiState,
    items: List<WorkOrderItem>,
    onBack: () -> Unit,
    onExport: (java.io.OutputStream) -> Unit,
    vm: AppViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showEditDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { onExport(it) }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) "报表已导出" else "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    val order = state.activeOrder

    if (showEditDialog && order != null) {
        EditOrderDialog(
            initialContainerNo = order.containerNo,
            initialRemark = order.remark,
            onDismiss = { showEditDialog = false },
            onSave = { c, r ->
                vm.updateOrderInfo(c, r)
                showEditDialog = false
            }
        )
    }

    Scaffold { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .background(state.screenColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // ---------- 顶部工具条 ----------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "装柜防错 v${com.zhuanggui.pda.BuildConfig.VERSION_NAME}",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { vm.triggerScan() }) {
                        Text("扫描", fontSize = 14.sp)
                    }
                    Button(onClick = { exportLauncher.launch("装柜报表-${System.currentTimeMillis()}.xlsx") }) {
                        Text("导出", fontSize = 14.sp)
                    }
                    OutlinedButton(onClick = onBack) { Text("返回", fontSize = 14.sp) }
                }
            }

            if (order == null) {
                Text(
                    "未选择工单",
                    fontSize = 28.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 48.dp)
                )
                return@Column
            }

            // ---------- 工单信息（可编辑） ----------
            Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(order.name, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        TextButton(onClick = { showEditDialog = true }) { Text("编辑柜号/备注") }
                    }
                    Text("柜号：${order.containerNo.ifBlank { "—" }}", fontSize = 16.sp)
                    Text("备注：${order.remark.ifBlank { "—" }}", fontSize = 16.sp)
                    Text(
                        "状态：" + when (order.status) {
                            "ACTIVE" -> "进行中"
                            "FINISHED" -> "已完成"
                            else -> "已关闭"
                        },
                        fontSize = 14.sp, color = Color.Gray
                    )
                }
            }

            // ---------- 模式切换 ----------
            if (order.status == "ACTIVE") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ModeButton(
                        label = "计数保存",
                        selected = state.mode == ScanMode.COUNT,
                        modifier = Modifier.weight(1f)
                    ) { vm.setMode(ScanMode.COUNT) }
                    ModeButton(
                        label = "校验防错",
                        selected = state.mode == ScanMode.VERIFY,
                        modifier = Modifier.weight(1f)
                    ) { vm.setMode(ScanMode.VERIFY) }
                }
            }

            // ---------- 计数卡 ----------
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CountCard("已扫码", state.totalScanned, Color(0xFF1976D2), Modifier.weight(1f))
                CountCard("允许", state.okCount, Color(0xFF388E3C), Modifier.weight(1f))
                CountCard("混料", state.errorCount, Color(0xFFD32F2F), Modifier.weight(1f))
                CountCard("超量", state.overLimitCount, Color(0xFFF57F17), Modifier.weight(1f))
            }

            // ---------- 扫码录入（HID 扫码枪 / 手动） ----------
            com.zhuanggui.pda.ui.HidScanField(
                onSubmit = { vm.manualScan(it) },
                modifier = Modifier.padding(top = 6.dp)
            )

            // ---------- 最新结果提示 ----------
            Card(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = state.screenColor)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "最后条码：${state.lastBarcode.ifBlank { "—" }}",
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(state.lastMessage, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
            }

            // ---------- 明细进度 ----------
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("条码明细进度", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("共 ${items.size} 项", fontSize = 14.sp, color = Color.Gray)
            }
            HorizontalDivider()

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    ItemProgressRow(item)
                }
            }

            // ---------- 底部：完工 ----------
            Spacer(Modifier.height(6.dp))
            val finished = order.status != "ACTIVE"
            Button(
                onClick = {
                    if (!finished) {
                        vm.finishOrder()
                        Toast.makeText(context, "已标记装柜完成", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !finished,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (finished) Color.Gray else Color(0xFF1976D2)
                )
            ) {
                Text(
                    if (finished) "工单已完工" else "装柜完成（结束本工单）",
                    fontSize = 18.sp
                )
            }
        }
    }
}

/** 单个条码的进度行 */
@Composable
private fun ItemProgressRow(item: WorkOrderItem) {
    val done = item.scannedQuantity
    val total = item.allowedQuantity
    val color = when {
        total > 0 && done >= total -> Color(0xFF388E3C)   // 已装满
        done > 0 -> Color(0xFF1976D2)                      // 装了一部分
        else -> Color(0xFF757575)                          // 未开始
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.barcode, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.productName.isNotBlank()) {
                    Text(item.productName, fontSize = 12.sp, color = Color.Gray)
                }
            }
            Text(
                "$done / ${if (total <= 0) "∞" else total}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    total <= 0 -> if (done > 0) "已扫" else "未扫"
                    done >= total -> "已满"
                    done > 0 -> "装柜中"
                    else -> "未扫"
                },
                fontSize = 13.sp,
                color = color
            )
        }
    }
}

/** 模式切换按钮（选中态高亮） */
@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF1976D2) else Color(0xFFE0E0E0),
            contentColor = if (selected) Color.White else Color(0xFF424242)
        )
    ) {
        Text(
            (if (selected) "✓ " else "") + label,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/** 计数小卡片 */
@Composable
private fun CountCard(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(count.toString(), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 14.sp)
        }
    }
}

/** 编辑柜号 / 备注对话框 */
@Composable
private fun EditOrderDialog(
    initialContainerNo: String,
    initialRemark: String,
    onDismiss: () -> Unit,
    onSave: (containerNo: String, remark: String) -> Unit
) {
    var containerNo by remember { mutableStateOf(initialContainerNo) }
    var remark by remember { mutableStateOf(initialRemark) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑柜号 / 备注") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = containerNo,
                    onValueChange = { containerNo = it },
                    label = { Text("柜号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(containerNo, remark) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
