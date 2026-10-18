package com.zhuanggui.pda.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/** 规则源文件类型（解析端按扩展名判断，这里限定选择范围） */
private val RULE_FILE_MIMES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx
    "application/vnd.ms-excel",                                           // .xls
    "text/csv",                                                           // .csv
    "text/plain"                                                          // .txt
)

/**
 * 规则库管理页：规则编辑（新增 / 修改 / 删除）+ 规则源导入（.xlsx / .xls / .csv / .txt）。
 * 「规则选择」在装柜设置页的「从规则库选择」完成。
 */
@Composable
fun RulesScreen(
    rules: List<RuleLibEntry>,
    importing: Boolean,
    message: String?,
    onMessageShown: () -> Unit,
    onSave: (id: Long?, prefix: String, name: String) -> Unit,
    onDelete: (Long) -> Unit,
    onImport: (Uri) -> Unit,
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
            onSave = { prefix, name ->
                onSave(target.entry?.id, prefix, name)
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
                Text("规则库", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            runCatching { importLauncher.launch(RULE_FILE_MIMES) }
                                .onFailure { localMessage = "无法打开文件选择器：本机缺少文件管理应用" }
                        },
                        enabled = !importing
                    ) {
                        Text(if (importing) "导入中…" else "导入规则源", fontSize = 14.sp)
                    }
                    OutlinedButton(onClick = onBack) { Text("返回", fontSize = 14.sp) }
                }
            }

            Text(
                "导入支持 .xlsx / .xls / .csv / .txt：第一列 = 条码前缀（字母+数字），第二列 = 名称（可选）",
                fontSize = 12.sp, color = Color.Gray
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
                "共 ${rules.size} 条" + (if (filter.isNotBlank()) " · 匹配 ${filtered.size} 条" else ""),
                fontSize = 12.sp, color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )
            HorizontalDivider()

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(filtered, key = { it.id }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.prefix, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            if (entry.name.isNotBlank()) {
                                Text(entry.name, fontSize = 13.sp, color = Color.Gray)
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

@Composable
private fun RuleEditDialog(
    initial: RuleLibEntry?,
    onDismiss: () -> Unit,
    onSave: (prefix: String, name: String) -> Unit
) {
    var prefix by remember { mutableStateOf(initial?.prefix ?: "") }
    var name by remember { mutableStateOf(initial?.name ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增规则" else "编辑规则") },
        text = {
            Column {
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it.filter { c -> c.isLetterOrDigit() }.uppercase() },
                    label = { Text("前缀（字母+数字）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(prefix, name) },
                enabled = prefix.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
