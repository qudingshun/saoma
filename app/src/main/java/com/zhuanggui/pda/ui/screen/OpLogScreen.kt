package com.zhuanggui.pda.ui.screen

import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanggui.pda.StorageConfig
import com.zhuanggui.pda.export.FileExporter
import com.zhuanggui.pda.log.OpLog

/**
 * 操作日志（v1.10.1）：试运行阶段的问题追溯。
 *
 * - 记录内容：每枪扫码（柜号/条码/判定结果/是否落盘成功）、落盘失败原因、删除/撤回/恢复、
 *   回收站清空、启动与存储状态、崩溃堆栈等
 * - 落盘位置：App 内部 files/logs/oplog_yyyyMMdd.log（卸载才清）
 * - 导出：点「导出到 <保存目录>/日志/」后，U 盘直接拷该目录即可
 */
@Composable
fun OpLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(emptyList<String>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun reload() {
        lines = OpLog.recent(context, 500)
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { reload() }

    Scaffold { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("操作日志", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(36.dp)
                ) { Text("返回", fontSize = 12.sp) }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        "用于试运行追溯：每枪扫码的判定与落盘结果、删除/撤回/恢复、启动与存储状态、崩溃堆栈。",
                        fontSize = 11.sp, color = Color.Gray
                    )
                    Text(
                        "日志文件：App 内部 files/logs/oplog_日期.log（每天一个，2MB 轮转）",
                        fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { reload() },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f).height(40.dp)
                        ) { Text("刷新", fontSize = 12.sp) }
                        Button(
                            onClick = {
                                val dir = FileExporter.logDir()
                                val f = OpLog.export(context, dir)
                                message = if (f != null) "已导出：${StorageConfig.pretty(f)}"
                                else "导出失败：请检查「所有文件访问」权限或存储空间"
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f).height(40.dp)
                        ) { Text("导出到 ${StorageConfig.pretty(FileExporter.logDir())}/", fontSize = 11.sp) }
                        OutlinedButton(
                            onClick = { confirmClear = true },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f).height(40.dp)
                        ) { Text("清空", fontSize = 12.sp) }
                    }
                }
            }

            if (lines.isEmpty()) {
                Text("暂无日志", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
            } else {
                Text("最近 ${lines.size} 条（最新在上）", fontSize = 12.sp, color = Color.Gray)
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(lines) { line ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(line, fontSize = 11.sp, color = Color(0xFF333333))
                            HorizontalDivider(Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空操作日志？") },
            text = { Text("日志会全部删除，且不可恢复。一般在导出并交给项目组后清空。") },
            confirmButton = {
                TextButton(onClick = {
                    OpLog.clear(context)
                    confirmClear = false
                    reload()
                    message = "已清空"
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("操作日志") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }
        )
    }
}
