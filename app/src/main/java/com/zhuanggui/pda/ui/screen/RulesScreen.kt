package com.zhuanggui.pda.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanggui.pda.data.entity.RuleLibEntry
import com.zhuanggui.pda.data.entity.RuleModel
import com.zhuanggui.pda.ui.RuleImportPreview

/** 规则源文件类型（解析端按扩展名判断，这里限定选择范围；含导出的 .xlsx） */
private val RULE_FILE_MIMES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx
    "application/vnd.ms-excel",                                           // .xls
    "text/csv",                                                           // .csv
    "text/plain",                                                         // .txt
    "text/tab-separated-values"                                           // .tsv（旧版导出，兼容）
)

/**
 * 规则库管理页（v1.7.5：型号为组，规则折叠）：
 *  - 维护顺序：先「新增型号」，再在型号行里「添加规则」逐个加前缀
 *  - 列表默认只显示型号名称（含规则条数），点型号行展开/收起其规则
 *  - 型号可改名（同步规则名称）/ 删除（连同其下规则）；规则可逐条编辑/删除
 *  - 导入 / 导出 XLSX（型号名称 / 前缀 / 四个开关），便于拷到其他 PDA
 */
@Composable
fun RulesScreen(
    models: List<RuleModel>,
    rules: List<RuleLibEntry>,
    importing: Boolean,
    exporting: Boolean,
    message: String?,
    importPreview: RuleImportPreview?,
    onMessageShown: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onSaveModel: (id: Long?, oldName: String, newName: String, defaultQuantity: Int) -> Unit,
    onDeleteModel: (Long, String) -> Unit,
    onSaveRule: (
        id: Long?, prefix: String, name: String,
        allowDuplicate: Boolean, countEnabled: Boolean, checkHistory: Boolean, ignoreRepeat: Boolean
    ) -> Unit,
    onDeleteRule: (Long) -> Unit,
    onImport: (Uri) -> Unit,
    onExport: () -> Unit,
    scannedPrefix: String?,
    onRuleScanActive: (Boolean) -> Unit,
    onConsumeRuleScan: () -> Unit,
    onBack: () -> Unit
) {
    var modelDialog by remember { mutableStateOf<ModelTarget?>(null) }
    var ruleDialog by remember { mutableStateOf<RuleTarget?>(null) }
    var deleteModel by remember { mutableStateOf<RuleModel?>(null) }
    var deleteRule by remember { mutableStateOf<RuleLibEntry?>(null) }
    var filter by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }
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

    // v1.8.3：导入前核对——先看新增/覆盖，再决定是否写入
    importPreview?.let { p ->
        AlertDialog(
            onDismissRequest = onCancelImport,
            title = { Text("确认导入规则库？") },
            text = {
                Text(
                    buildString {
                        append("文件内共 ${p.rules.size} 条规则、${p.modelNames.size} 个型号。\n\n")
                        append("将新增 ${p.newRules} 条，覆盖 ${p.overwriteRules} 条")
                        if (p.overwriteSamples.isNotEmpty()) {
                            append("（${p.overwriteSamples.joinToString("、")}")
                            if (p.overwriteRules > p.overwriteSamples.size) append(" 等")
                            append("）")
                        }
                        append("。")
                        if (p.newModels > 0) append("\n新增型号 ${p.newModels} 个。")
                        append("\n\n确认后会先自动备份当前规则库（带日期），再按前缀写入；取消则不改动规则库。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmImport) { Text("继续导入") }
            },
            dismissButton = {
                TextButton(onClick = onCancelImport) { Text("取消") }
            }
        )
    }

    modelDialog?.let { target ->
        RuleModelDialog(
            title = if (target.id == null) "新增型号" else "编辑型号",
            initialName = target.name,
            initialDefaultQuantity = target.defaultQuantity,
            onDismiss = { modelDialog = null },
            onSave = { name, defaultQty ->
                val n = name.trim()
                onSaveModel(target.id, target.name, n, defaultQty)
                modelDialog = null
                // 新增型号后直接打开「添加规则」，可立即扫码录前缀；同一型号可继续添加多条
                val exists = models.any { it.name == n } || rules.any { it.name == n }
                if (target.id == null && n.isNotBlank() && !exists) {
                    expanded = expanded + n
                    ruleDialog = RuleTarget(n, null)
                }
            }
        )
    }

    ruleDialog?.let { target ->
        val modelDefault = models.firstOrNull { it.name == target.modelName }?.defaultQuantity ?: 0
        RuleEditDialog(
            modelName = target.modelName,
            modelDefaultQuantity = modelDefault,
            initial = target.entry,
            scannedPrefix = scannedPrefix,
            onRuleScanActive = onRuleScanActive,
            onConsumeRuleScan = onConsumeRuleScan,
            onDismiss = { ruleDialog = null },
            onSave = { prefix, allowDup, count, checkHist, ignore ->
                onSaveRule(
                    target.entry?.id, prefix, target.modelName,
                    allowDup, count, checkHist, ignore
                )
                ruleDialog = null
            }
        )
    }

    deleteModel?.let { model ->
        val count = rules.count { it.name == model.name }
        AlertDialog(
            onDismissRequest = { deleteModel = null },
            title = { Text("删除型号？") },
            text = {
                Text(
                    "型号：${model.name.ifBlank { "（未填型号）" }}\n" +
                        "其下 $count 条规则将一并删除。\n\n已装柜的柜子不受影响（装柜使用规则快照）。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteModel(model.id, model.name)
                    deleteModel = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteModel = null }) { Text("取消") } }
        )
    }

    deleteRule?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteRule = null },
            title = { Text("删除规则？") },
            text = {
                Text(
                    "将从型号「${entry.name.ifBlank { "未填" }}」删除前缀：${entry.prefix}" +
                        "\n\n已装柜的柜子不受影响（装柜使用规则快照）。"
                )
            },
            confirmButton = {
                TextButton(onClick = { onDeleteRule(entry.id); deleteRule = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteRule = null }) { Text("取消") } }
        )
    }

    // 型号集合 = 型号表 + 规则里出现但型号表缺失的名称（容错）；空名称归到「（未填型号）」
    val modelNames = (models.map { it.name } + rules.map { it.name }).distinct()
        .filter { name ->
            filter.isBlank() ||
                name.contains(filter, ignoreCase = true) ||
                rules.any { it.name == name && it.prefix.contains(filter, ignoreCase = true) }
        }
        .sorted()

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
                        enabled = !exporting && (models.isNotEmpty() || rules.isNotEmpty()),
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
                "先新增型号，再维护多条规则；默认装柜数量按型号只维护 1 个。\n" +
                    "规则前缀可用扫码枪直接录入；导入/导出 XLSX（型号 / 前缀 / 四个开关 / 默认数量）。",
                fontSize = 11.sp, color = Color.Gray,
                modifier = Modifier.padding(top = 2.dp)
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("过滤型号/前缀", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { modelDialog = ModelTarget(null, "", 0) },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(44.dp)
                ) { Text("新增型号", fontSize = 14.sp) }
            }

            Text(
                "共 ${modelNames.size} 个型号 / ${rules.size} 条规则" +
                    if (filter.isNotBlank()) "（已过滤）" else "",
                fontSize = 11.sp, color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )
            HorizontalDivider()

            if (modelNames.isEmpty()) {
                Text(
                    if (models.isEmpty() && rules.isEmpty())
                        "规则库为空：点「新增型号」先建型号，再逐个添加规则"
                    else "无匹配",
                    fontSize = 13.sp, color = Color.Gray,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(modelNames, key = { "m_$it" }) { name ->
                        val model = models.firstOrNull { it.name == name }
                        val entries = rules.filter { it.name == name }.sortedBy { it.prefix }
                        val open = name in expanded
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expanded = if (open) expanded - name else expanded + name
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (open) "▾" else "▸",
                                    fontSize = 12.sp, color = Color.Gray,
                                    modifier = Modifier.width(18.dp)
                                )
                                Text(
                                    name.ifBlank { "（未填型号）" },
                                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                if ((model?.defaultQuantity ?: 0) > 0) {
                                    Text(
                                        "默认 ${model?.defaultQuantity} 件",
                                        fontSize = 11.sp, color = Color(0xFF1976D2),
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                }
                                Text("${entries.size} 条", fontSize = 11.sp, color = Color.Gray)
                            }
                            if (open) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    TextButton(onClick = {
                                        ruleDialog = RuleTarget(name, null)
                                    }) {
                                        Text("＋ 添加规则", fontSize = 12.sp)
                                    }
                                    if (model != null) {
                                        TextButton(onClick = {
                                            modelDialog = ModelTarget(model.id, model.name, model.defaultQuantity)
                                        }) { Text("编辑型号", fontSize = 12.sp) }
                                    }
                                    TextButton(onClick = {
                                        deleteModel = model ?: RuleModel(id = 0, name = name)
                                    }) {
                                        Text("删除型号", fontSize = 12.sp, color = Color(0xFFD32F2F))
                                    }
                                }
                                if (entries.isEmpty()) {
                                    Text(
                                        "暂无规则：点「＋ 添加规则」逐个添加前缀",
                                        fontSize = 11.sp, color = Color.Gray,
                                        modifier = Modifier.padding(start = 30.dp, bottom = 6.dp)
                                    )
                                }
                                entries.forEach { entry ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(entry.prefix, fontSize = 14.sp)
                                            flagSummary(entry)?.let {
                                                Text(it, fontSize = 10.sp, color = Color(0xFF616161))
                                            }
                                        }
                                        TextButton(onClick = {
                                            ruleDialog = RuleTarget(name, entry)
                                        }) {
                                            Text("编辑", fontSize = 12.sp)
                                        }
                                        TextButton(onClick = { deleteRule = entry }) {
                                            Text("删除", fontSize = 12.sp, color = Color(0xFFD32F2F))
                                        }
                                    }
                                    HorizontalDivider(thickness = 0.5.dp)
                                }
                            }
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

private data class ModelTarget(
    val id: Long?,
    val name: String,
    val defaultQuantity: Int = 0
)
private data class RuleTarget(
    val modelName: String,
    val entry: RuleLibEntry?
)

/** 规则开关摘要（列表行第二行；全默认（无特殊设置）时返回 null 不显示）；设置页「从规则库选择」也复用 */
internal fun flagSummary(e: RuleLibEntry): String? {
    val parts = mutableListOf<String>()
    if (e.allowDuplicate) parts.add("可重复扫")
    if (!e.countEnabled) parts.add("辅助码（不计统计）")
    if (e.checkHistory) parts.add("查历史串号")
    if (e.allowDuplicate && e.ignoreRepeat) parts.add("忽略（不记录）")
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

/** 型号新增/编辑弹窗：型号名称 + 默认装柜数量（一个型号只维护 1 个） */
@Composable
private fun RuleModelDialog(
    title: String,
    initialName: String,
    initialDefaultQuantity: Int,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var qty by remember {
        mutableStateOf(if (initialDefaultQuantity > 0) initialDefaultQuantity.toString() else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("型号名称（如 T45LW）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = qty,
                    onValueChange = { v -> qty = v.filter { it.isDigit() }.take(6) },
                    label = { Text("默认装柜数量（0/空 = 不限）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                Text(
                    "默认数量按型号只维护 1 个，适用于该型号下所有规则；" +
                        "设置页从规则库选择该型号时会自动带出计划。\n" +
                        "保存后会自动打开「添加规则」，可继续添加多条前缀。",
                    fontSize = 11.sp, color = Color.Gray,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, qty.toIntOrNull() ?: 0) },
                enabled = name.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 规则新增/编辑弹窗（型号固定；一次一条前缀，逐个添加；前缀支持扫码枪录入） */
@Composable
private fun RuleEditDialog(
    modelName: String,
    modelDefaultQuantity: Int,
    initial: RuleLibEntry?,
    scannedPrefix: String?,
    onRuleScanActive: (Boolean) -> Unit,
    onConsumeRuleScan: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (
        prefix: String,
        allowDuplicate: Boolean, countEnabled: Boolean, checkHistory: Boolean, ignoreRepeat: Boolean
    ) -> Unit
) {
    var prefix by remember { mutableStateOf(initial?.prefix ?: "") }
    var allowDup by remember { mutableStateOf(initial?.allowDuplicate ?: false) }
    var count by remember { mutableStateOf(initial?.countEnabled ?: true) }
    var checkHist by remember { mutableStateOf(initial?.checkHistory ?: false) }
    var ignore by remember { mutableStateOf(initial?.ignoreRepeat ?: false) }

    // 弹窗打开期间，扫码优先填入本弹窗的前缀栏（关闭时解除）
    DisposableEffect(Unit) {
        onRuleScanActive(true)
        onDispose { onRuleScanActive(false) }
    }
    LaunchedEffect(scannedPrefix) {
        val raw = scannedPrefix?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            prefix = raw.filter { it.isLetterOrDigit() || it == '-' || it == '#' }.uppercase()
            onConsumeRuleScan()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加规则" else "编辑规则") },
        text = {
            Column {
                Text(
                    "型号：${modelName.ifBlank { "（未填型号）" }}" +
                        if (modelDefaultQuantity > 0) "  ·  默认 $modelDefaultQuantity 件" else "",
                    fontSize = 13.sp, color = Color(0xFF1976D2)
                )
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { v ->
                        prefix = v.filter { it.isLetterOrDigit() || it == '-' || it == '#' }.uppercase()
                    },
                    label = { Text("条码前缀 / 扫码录入") },
                    placeholder = { Text("可扫条码后删减为固定前缀") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                Text(
                    "扫码枪扫条码会自动填入；如条码含流水号，请删减为固定前缀部分。",
                    fontSize = 11.sp, color = Color.Gray,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    "同一型号可继续点「＋ 添加规则」维护多条前缀；默认装柜数量在型号上统一设置。",
                    fontSize = 11.sp, color = Color.Gray,
                    modifier = Modifier.padding(top = 2.dp)
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
                onClick = {
                    onSave(
                        prefix, allowDup, count, checkHist, allowDup && ignore
                    )
                },
                enabled = prefix.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
