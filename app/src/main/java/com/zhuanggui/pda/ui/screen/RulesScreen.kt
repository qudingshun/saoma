package com.zhuanggui.pda.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanggui.pda.data.entity.RuleLibEntry

/** 规则源文件类型（解析端按扩展名判断，这里限定选择范围；含导出的 .tsv） */
private val RULE_FILE_MIMES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx
    "application/vnd.ms-excel",                                           // .xls
    "text/csv",                                                           // .csv
    "text/plain",                                                         // .txt
    "text/tab-separated-values",                                          // .tsv（规则库导出）
    "application/octet-stream"                                            // 部分文件管理器对 .tsv 的兜底类型
)

/**
 * 规则库管理页：规则编辑（新增 / 修改 / 删除）+ 规则源导入（.xlsx / .xls / .csv / .txt / .tsv）+ 导出（v1.7.4）。
 * v1.7.0（第 6 条）：同一型号可挂多条前缀（列表按型号分组）；每条规则带四个开关
 * （可重复扫 / 计入统计 / 查历史串号 / 忽略），新增时支持一次录入多个前缀。
 * 「规则选择」在装柜设置页的「从规则库选择」完成（连同四个开关一起复制到本柜）。
 */
@Composable
fun RulesScreen(
    rules: List<RuleLibEntry>,
    importing: Boolean,
    exporting: Boolean,
    message: String?,
    onMessageShown: () -> Unit,
    onSave: (
        id: Long?, prefix: String, name: String,
        allowDuplicate: Boolean, countEnabled: Boolean, checkHistory: Boolean, ignoreRepeat: Boolean
    ) -> Unit,
    onDelete: (Long) -> Unit,
    onImport: (Uri) -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit
) {
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<RuleLibEntry?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    var localMessage by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImport(uri)
    }

    (localMessage ?: message)?.let { shown ->
        AlertDialog(
            onDismissRequest = { localMessage = null; onMessageShown() },
            title = { Text("规则库") },
            text = { Text(shown) },
            confirmButton = {
                TextButton(onClick = { localMessage = null; onMessageShown() }) { Text("知道了") }
            }
        )
    }

    editTarget?.let { target ->
        RuleEditDialog(
            initial = target.entry,
            onDismiss = { editTarget = null },
            onSave = { prefix, name, allowDup, count, checkHist, ignore ->
                onSave(target.entry?.id, prefix, name, allowDup, count, checkHist, ignore)
                editTarget = null
            }
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除规则？") },
            text = {
                Text(
                    "将从规则库删除：${entry.prefix}" +
                        (if (entry.name.isNotBlank()) "（${entry.name}）" else "") +
                        "\n\n已装柜的柜子不受影响（装柜使用规则快照）。"
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(entry.id); deleteTarget = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    val filtered = if (filter.isBlank()) rules
    else rules.filter {
        it.prefix.contains(filter, ignoreCase = true) || it.name.contains(filter, ignoreCase = true)
    }

    Scaffold { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("规则库", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = {
                            runCatching { importLauncher.launch(RULE_FILE_MIMES) }
                                .onFailure { localMessage = "无法打开文件选择器：本机缺少文件管理应用" }
                        },
                        enabled = !importing,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(if (importing) "导入中…" else "导入", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onExport,
                        enabled = !exporting && rules.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(if (exporting) "导出中…" else "导出", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onBack,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(34.dp)
                    ) { Text("返回", fontSize = 12.sp) }
                }
            }

            Text(
                "导入支持 .xlsx / .xls / .csv / .txt：第一列 = 前缀，第二列 = 名称（可选），" +
                    "第 3~6 列 = 四个开关（可选，1/0）；导出为同格式 TSV（含开关）",
                fontSize = 11.sp, color = Color.Gray
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("过滤前缀/名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { editTarget = EditTarget(null) }) { Text("新增", fontSize = 15.sp) }
            }

            Text(
                "共 ${rules.size} 条（按型号分组）" + (if (filter.isNotBlank()) " · 匹配 ${filtered.size} 条" else ""),
                fontSize = 12.sp, color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )
            HorizontalDivider()

            val grouped = filtered
                .groupBy { it.name.ifBlank { "（未填型号）" } }
                .toSortedMap()

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                grouped.forEach { (model, entries) ->
                    item(key = "h_$model") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(model, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
                            Text("  ${entries.size} 条", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.prefix, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                flagSummary(entry)?.let {
                                    Text(it, fontSize = 12.sp, color = Color(0xFF616161))
                                }
                            }
                            TextButton(onClick = { editTarget = EditTarget(entry) }) {
                                Text("编辑", fontSize = 13.sp)
                            }
                            TextButton(onClick = { deleteTarget = entry }) {
                                Text("删除", fontSize = 13.sp, color = Color(0xFFD32F2F))
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (rules.isEmpty()) {
                                "规则库为空：点右上角「导入规则源」导入 Excel/CSV，或点「新增」手动添加"
                            } else {
                                "无匹配"
                            },
                            fontSize = 14.sp, color = Color.Gray,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 编辑目标：entry = null 表示新增长 */
private data class EditTarget(val entry: RuleLibEntry?)

/** 规则开关摘要（列表行第二行；全默认（无特殊设置）时返回 null 不显示）；设置页「从规则库选择」也复用 */
internal fun flagSummary(e: RuleLibEntry): String? {
    val parts = mutableListOf<String>()
    if (e.allowDuplicate) parts.add("可重复扫")
    if (!e.countEnabled) parts.add("辅助码（不计统计）")
    if (e.checkHistory) parts.add("查历史串号")
    if (e.allowDuplicate && e.ignoreRepeat) parts.add("忽略（不记录）")
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

@Composable
private fun RuleEditDialog(
    initial: RuleLibEntry?,
    onDismiss: () -> Unit,
    onSave: (
        prefix: String, name: String,
        allowDuplicate: Boolean, countEnabled: Boolean, checkHistory: Boolean, ignoreRepeat: Boolean
    ) -> Unit
) {
    var prefix by remember { mutableStateOf(initial?.prefix ?: "") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var allowDup by remember { mutableStateOf(initial?.allowDuplicate ?: false) }
    var count by remember { mutableStateOf(initial?.countEnabled ?: true) }
    var checkHist by remember { mutableStateOf(initial?.checkHistory ?: false) }
    var ignore by remember { mutableStateOf(initial?.ignoreRepeat ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增规则（可多条）" else "编辑规则") },
        text = {
            Column {
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { v ->
                        prefix = if (initial == null) {
                            // 新增：支持一次录入多个前缀（逗号/空格/换行分隔）
                            v.uppercase().filter { c -> c.isLetterOrDigit() || c in ",，、;； \n\t" }
                        } else {
                            v.filter { c -> c.isLetterOrDigit() }.uppercase()
                        }
                    },
                    label = { Text(if (initial == null) "前缀（可多个：逗号/空格分隔）" else "前缀（字母+数字）") },
                    singleLine = initial != null,
                    maxLines = if (initial == null) 3 else 1,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("型号（可选；同型号可挂多条前缀）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowDup, onCheckedChange = { allowDup = it })
                    Text("可重复扫", fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Checkbox(checked = count, onCheckedChange = { count = it })
                    Text("计入统计", fontSize = 14.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checkHist, onCheckedChange = { checkHist = it })
                    Text("查历史串号", fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Checkbox(
                        checked = allowDup && ignore,
                        onCheckedChange = { ignore = it },
                        enabled = allowDup
                    )
                    Text("忽略", fontSize = 14.sp, color = if (allowDup) Color.Unspecified else Color.Gray)
                }
                Text(
                    "可重复扫：同码反复扫不报“重复”。计入统计：不勾 = 辅助码（只留痕不计数）。" +
                        "查历史串号：条码在其他柜出现过 → 报串号。忽略（仅可重复扫）：扫到不记录、不落盘，语音报“辅助码”。",
                    fontSize = 11.sp, color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(prefix, name, allowDup, count, checkHist, allowDup && ignore) },
                enabled = prefix.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
