package com.zhuanggui.pda.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanggui.pda.StorageConfig
import com.zhuanggui.pda.export.FileExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 保存目录设置（v1.13.0）。
 *
 * 现场可把「装柜记录 / 回收站 / 规则库 / 日志 / 装柜完成汇总」统一换到别的根目录，
 * 方便按产线、班组或 U 盘目录归档；切换时自动迁移旧目录里的本 App 数据。
 */
@Composable
fun StorageSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf(StorageConfig.rootDir()) }
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("保存目录", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(36.dp)
                ) { Text("返回", fontSize = 12.sp) }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text("当前根目录", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        StorageConfig.pretty(root),
                        fontSize = 13.sp,
                        color = Color(0xFF1976D2),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        if (StorageConfig.isCustom()) "自定义目录" else "默认目录",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            editing = true
                            message = null
                        },
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) { Text(if (busy) "正在迁移，请稍候…" else "更改保存目录", fontSize = 14.sp) }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text("目录结构", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    listOf(
                        "装柜记录" to "每日每柜一个 TSV（时间/柜号/条码/操作员/结果）",
                        "回收站" to "删除后的记录文件，可恢复",
                        "规则库" to "规则库导出、导入前备份、同步前备份",
                        "日志" to "操作日志导出文件",
                        "装柜完成汇总.tsv" to "每装齐一柜追加一行"
                    ).forEach { (name, desc) ->
                        Text("• $name", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(desc, fontSize = 11.sp, color = Color.Gray)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text("更改说明", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "1. 切换目录时，会自动把旧目录中的装柜记录、回收站、规则库、日志和完成汇总" +
                            "迁移到新目录；旧目录里的其他文件不受影响。\n" +
                            "2. 新目录必须可写；建议使用 /sdcard 下的文件夹，U 盘拷取方便。\n" +
                            "3. 建议在未装柜时更改；正在装柜也可以改，但请先返回首页。\n" +
                            "4. 多台 PDA 请各用各的目录（或不同文件夹名），避免文件互相覆盖。",
                        fontSize = 11.sp,
                        color = Color(0xFF444444)
                    )
                }
            }

            message?.let {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(it, fontSize = 12.sp, color = Color(0xFF1976D2))
                        TextButton(onClick = { message = null }) { Text("知道了", fontSize = 12.sp) }
                    }
                }
            }

            Text(
                "提示：规则库本身保存在 App 数据库里，切换目录不会丢规则；" +
                    "目录里的规则库文件只是导出/备份。",
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
    }

    if (editing) {
        var input by remember { mutableStateOf(StorageConfig.pretty(root)) }
        val presets = listOf(
            "默认" to StorageConfig.pretty(StorageConfig.defaultRoot()),
            "成品出库" to "/sdcard/成品出库",
            "Download/成品出库" to "/sdcard/Download/成品出库"
        )
        AlertDialog(
            onDismissRequest = { if (!busy) editing = false },
            title = { Text("更改保存目录", fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "填写根目录（可输入 /sdcard/… 或完整路径）。留空 = 恢复默认。",
                        fontSize = 11.sp, color = Color.Gray
                    )
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("根目录", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    presets.forEach { (label, path) ->
                        Text(
                            "$label：$path",
                            fontSize = 11.sp,
                            color = Color(0xFF1976D2),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { input = path }
                                .padding(vertical = 2.dp)
                        )
                    }
                    Text(
                        "切换后会迁移旧目录里的记录/规则库/日志/汇总，迁移完成前请勿退出。",
                        fontSize = 11.sp, color = Color(0xFFF57F17)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            message = null
                            val result = withContext(Dispatchers.IO) {
                                FileExporter.changeRoot(context, input.ifBlank { null })
                            }
                            busy = false
                            result.onSuccess { moved ->
                                root = StorageConfig.rootDir()
                                editing = false
                                message = if (moved > 0)
                                    "已切换到 ${StorageConfig.pretty(root)}，迁移文件 $moved 个"
                                else
                                    "已切换到 ${StorageConfig.pretty(root)}（无需迁移文件）"
                            }.onFailure {
                                message = "更改失败：${it.message ?: "未知错误"}"
                            }
                        }
                    }
                ) { Text(if (busy) "迁移中…" else "保存并迁移") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !busy,
                        onClick = { input = "" }
                    ) { Text("恢复默认") }
                    TextButton(
                        enabled = !busy,
                        onClick = { editing = false }
                    ) { Text("取消") }
                }
            }
        )
    }
}
