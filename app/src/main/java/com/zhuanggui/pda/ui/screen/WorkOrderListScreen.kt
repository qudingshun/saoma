package com.zhuanggui.pda.ui.screen

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanggui.pda.data.entity.WorkOrder
import com.zhuanggui.pda.excel.ExcelService
import com.zhuanggui.pda.ui.AppViewModel
import com.zhuanggui.pda.BuildConfig
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WorkOrderListScreen(
    workOrders: List<WorkOrder>,
    onSelect: (WorkOrder) -> Unit,
    onCrate: (String, String, List<Pair<String, Int>>) -> Unit,
    vm: AppViewModel,
    onBack: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var orderName by remember { mutableStateOf("") }
    var containerNo by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                val items = withContext(Dispatchers.IO) { ExcelService().importItems(context, it) }
                if (items.isEmpty()) {
                    Toast.makeText(context, "未解析到有效条码，请检查Excel表头", Toast.LENGTH_LONG).show()
                } else {
                    val name = if (orderName.isBlank()) "工单-${System.currentTimeMillis()}" else orderName
                    onCrate(name, containerNo, items.map { p -> p.barcode to p.quantity })
                    Toast.makeText(context, "已导入 ${items.size} 个条码", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("装柜防错", fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                        fontSize = 13.sp,
                        color = androidx.compose.ui.graphics.Color.Gray
                    )
                    androidx.compose.material3.OutlinedButton(onClick = onBack) { Text("返回", fontSize = 13.sp) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = orderName,
                    onValueChange = { orderName = it },
                    label = { Text("工单名称") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = containerNo,
                    onValueChange = { containerNo = it },
                    label = { Text("柜号/备注") },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = { importLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel")) }) {
                    Text("从Excel导入工单", fontSize = 18.sp)
                }
            }

            Text("工单列表", fontSize = 24.sp, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(workOrders, key = { it.id }) { order ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${order.name}  ${order.containerNo}", fontSize = 20.sp)
                            Text("状态：${orderStatusText(order.status)}", fontSize = 14.sp)
                        }
                    }
                    androidx.compose.material3.Text("")  // spacer
                    Button(onClick = { onSelect(order) }, modifier = Modifier.fillMaxWidth()) {
                        Text("选择此工单开始装柜", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

private fun orderStatusText(status: String): String = when (status) {
    WorkOrder.STATUS_ACTIVE -> "进行中"
    WorkOrder.STATUS_FINISHED -> "已完成"
    WorkOrder.STATUS_CLOSED -> "已关闭"
    else -> status
}
