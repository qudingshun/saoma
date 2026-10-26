package com.zhuanggui.pda.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.zhuanggui.pda.data.entity.RuleLibEntry
import com.zhuanggui.pda.data.entity.RuleModel
import com.zhuanggui.pda.ui.CompactTextField
import com.zhuanggui.pda.ui.OutboundSetupState
import com.zhuanggui.pda.ui.RuleSpec
import com.zhuanggui.pda.ui.SetupScanTarget

/**
 * 出库扫码设置页（整页固定布局；「本柜条码规则」列表在【装柜】按钮下方，固定区域内部滚动）：
 *  - 柜号 + 计划装柜数量（并列一行，窄框；计划 = 本批目标件数，空/0 = 不限）
 *    · v1.7.0：命中前面已有柜号 → 回显前期存的计划装柜数量；从扫码页返回 → 回显上次设置的计划
 *    · 命中「今天已扫码」的柜号且计划留空 → 装柜时按该柜已存计划继续（提示条与占位符可见）
 *  - 操作员工号：选填；默认下拉 A / B / C / D，也可扫工牌或手输（自动大写）；工号栏聚焦时扫码键填入工号
 *  - 已扫码数量（= 已装柜数量）：与工号栏同款只读框（不可修改），自动统计（今天正常数）
 *  - 录入规则（前缀）+ 流水号最小值/最大值（v1.7.0：限定条码流水号范围，超范围报“串号”）
 *  - 新增规则勾选：可重复扫 / 计入统计 / 查历史串号 / 忽略（仅可重复扫时生效，扫到不记录）
 *  - 【装柜】进入扫码界面；下方为「本柜条码规则」列表（不影响上方设置区排版）
 */
@Composable
fun OutboundSetupScreen(
    state: OutboundSetupState,
    library: List<RuleLibEntry>,
    ruleModels: List<RuleModel>,
    onContainerNo: (String) -> Unit,
    onTotal: (String) -> Unit,
    onOperatorId: (String) -> Unit,
    onScanTarget: (SetupScanTarget) -> Unit,
    onRuleInput: (String) -> Unit,
    onRuleAllowDup: (Boolean) -> Unit,
    onRuleCount: (Boolean) -> Unit,
    onRuleCheckHistory: (Boolean) -> Unit,
    onRuleSerialMin: (String) -> Unit,
    onRuleSerialMax: (String) -> Unit,
    onRuleSerialTailLen: (String) -> Unit,
    onRuleIgnore: (Boolean) -> Unit,
    onAddRule: () -> Unit,
    onRemoveRule: (String) -> Unit,
    onRuleFlags: (String, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onRuleSerial: (String, String, String, Int) -> Unit,
    onAddRules: (List<Long>) -> Unit,
    onOpenRuleLibrary: () -> Unit,
    saveMessage: String?,
    onSaveToRuleLibrary: (String) -> Unit,
    onSaveMessageShown: () -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    var operatorMenu by remember { mutableStateOf(false) }
    var showRuleHelp by remember { mutableStateOf(false) }
    var showSaveToLibrary by remember { mutableStateOf(false) }
    // 非空 = 正在补录/修改该规则的流水号范围
    var serialRule by remember { mutableStateOf<RuleSpec?>(null) }

    serialRule?.let { rule ->
        RuleSerialDialog(
            rule = rule,
            onDismiss = { serialRule = null },
            onSave = { mn, mx, tail ->
                onRuleSerial(rule.prefix, mn, mx, tail)
                serialRule = null
            }
        )
    }

    if (showPicker) {
        RulePickerDialog(
            library = library,
            models = ruleModels,
            alreadyIn = state.rules.map { it.prefix },
            onConfirm = { picked ->
                onAddRules(picked)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }

    if (showSaveToLibrary) {
        SaveToRuleLibraryDialog(
            ruleCount = state.rules.size,
            plan = state.totalQuantity.toIntOrNull() ?: 0,
            existingNames = (ruleModels.map { it.name } + library.map { it.name }).toSet(),
            onDismiss = { showSaveToLibrary = false },
            onSave = { name ->
                onSaveToRuleLibrary(name)
                showSaveToLibrary = false
            }
        )
    }

    saveMessage?.let { msg ->
        val savedOk = msg.startsWith("已保存") || msg.startsWith("已覆盖")
        AlertDialog(
            onDismissRequest = onSaveMessageShown,
            title = { Text("保存到规则库") },
            text = { Text(msg, fontSize = 13.sp) },
            confirmButton = {
                if (savedOk) {
                    TextButton(onClick = {
                        onSaveMessageShown()
                        onOpenRuleLibrary()
                    }) { Text("去规则库查看") }
                } else {
                    TextButton(onClick = onSaveMessageShown) { Text("知道了") }
                }
            },
            dismissButton = if (savedOk) {
                { TextButton(onClick = onSaveMessageShown) { Text("知道了") } }
            } else null
        )
    }

    Scaffold { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 0.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("出库扫码设置", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(34.dp)
                ) { Text("返回", fontSize = 11.sp) }
            }

            if (state.scannedQuantity > 0) {
                // 计划标注：输入框填写优先；留空 = 用该柜已存计划（装柜时按它继续，不改写输入框）
                val plan = state.totalQuantity.toIntOrNull() ?: state.storedPlan
                val planLabel = if (state.totalQuantity.isBlank()) "已存计划" else "计划"
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9C4))) {
                    Text(
                        if (plan > 0 && state.scannedQuantity >= plan)
                            "该柜号今天已装齐（已扫码 ${state.scannedQuantity} / $planLabel $plan）：再扫码将提示超量；如需补装请先改大「计划装柜数量」"
                        else "该柜号今天已扫码 ${state.scannedQuantity} 件" +
                            if (plan > 0) "（$planLabel $plan）：继续扫码将接着计数，扫满计划数量时提示装柜完成"
                            else "（计划不限）：继续扫码将接着计数",
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 11.sp
                    )
                }
            }

            // ---------- 柜号 + 计划装柜数量（并列） ----------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactTextField(
                    value = state.containerNo,
                    onValueChange = onContainerNo,
                    label = "柜号",
                    placeholder = "字母+数字",
                    modifier = Modifier.weight(1f)
                )
                CompactTextField(
                    value = state.totalQuantity,
                    onValueChange = onTotal,
                    label = "计划装柜数量",
                    placeholder = if (state.scannedQuantity > 0 && state.storedPlan > 0)
                        "留空 = 已存计划 ${state.storedPlan}"
                    else "0/空 = 不限",
                    numeric = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // ---------- 操作员工号（选填：下拉 A/B/C/D 或扫工牌/手输）+ 已扫码数量（同款只读框） ----------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    CompactTextField(
                        value = state.operatorId,
                        onValueChange = onOperatorId,
                        label = "操作员工号",
                        placeholder = "默认 A/B/C/D，可手输",
                        trailing = {
                            Icon(
                                Icons.Filled.ArrowDropDown,
                                contentDescription = "选择工号",
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable { operatorMenu = true }
                            )
                        },
                        onFocusChanged = {
                            // 焦点路由：工号栏聚焦时扫码填入工号（替换），其余位置填规则栏
                            onScanTarget(if (it) SetupScanTarget.OPERATOR else SetupScanTarget.RULE)
                        }
                    )
                    DropdownMenu(
                        expanded = operatorMenu,
                        onDismissRequest = { operatorMenu = false }
                    ) {
                        listOf("A", "B", "C", "D").forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v, fontSize = 15.sp) },
                                onClick = {
                                    onOperatorId(v)
                                    operatorMenu = false
                                }
                            )
                        }
                    }
                }
                // 已扫码数量（= 已装柜数量）：与工号栏同款布局，数字不可修改（只读）
                CompactTextField(
                    value = "${state.scannedQuantity} 件",
                    onValueChange = {},
                    label = "已扫码数量（今天）",
                    readOnly = true,
                    textColor = if (state.scannedQuantity > 0) Color(0xFF2E7D32) else Color(0xFF555555),
                    modifier = Modifier.weight(1f)
                )
            }

            // ---------- 录入规则（前缀）+ 流水号范围（v1.7.0：限定数据范围） ----------
            CompactTextField(
                value = state.ruleInput,
                onValueChange = onRuleInput,
                label = "录入规则（前缀）"
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CompactTextField(
                    value = state.ruleSerialMin,
                    onValueChange = onRuleSerialMin,
                    label = "流水号最小值",
                    placeholder = "留空=不限",
                    numeric = true,
                    textSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                CompactTextField(
                    value = state.ruleSerialMax,
                    onValueChange = onRuleSerialMax,
                    label = "流水号最大值",
                    placeholder = "留空=不限",
                    numeric = true,
                    textSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                CompactTextField(
                    value = state.ruleSerialTailLen,
                    onValueChange = onRuleSerialTailLen,
                    label = "末尾位数",
                    placeholder = "0=整段",
                    numeric = true,
                    textSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
            }

            // 新增规则时的勾选（双码机选项 + 查历史串号 + 忽略）；紧凑行高
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = state.ruleAllowDup,
                            onCheckedChange = onRuleAllowDup
                        )
                        Text("可重复扫", fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Checkbox(
                            checked = state.ruleCount,
                            onCheckedChange = onRuleCount
                        )
                        Text("计入统计", fontSize = 12.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = state.ruleCheckHistory,
                            onCheckedChange = onRuleCheckHistory
                        )
                        Text("查历史串号", fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Checkbox(
                            checked = state.ruleAllowDup && state.ruleIgnoreRepeat,
                            onCheckedChange = onRuleIgnore,
                            enabled = state.ruleAllowDup
                        )
                        Text(
                            "忽略（不记录）",
                            fontSize = 12.sp,
                            color = if (state.ruleAllowDup) Color.Unspecified else Color.Gray
                        )
                    }
                }
            }

            // 规则说明（默认收起，点击展开；展开时为固定小窗，内部下拉滚动查看）
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showRuleHelp = !showRuleHelp }
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (showRuleHelp) "规则说明 ▾（点击收起）" else "规则说明 ▸（点击展开）",
                    fontSize = 10.sp,
                    color = Color(0xFF666666)
                )
            }
            if (showRuleHelp) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFF2F2F2))
                        .border(0.5.dp, Color(0xFFD0D0D0), RoundedCornerShape(4.dp))
                ) {
                    Text(
                        "条码须以规则前缀开头；设了流水号范围时，前缀后的数字须落在最小~最大值之间，否则报“串号”。\n" +
                            "条码中间还有无关字符/数字、流水号在末尾时：填「末尾位数」（如 3），则 ASV12SDFWG05455056$ 取末尾 3 位 056 核对（末尾符号忽略）。\n" +
                            "流水号范围可在规则行点「设范围 / 流水 …」补录/修改（从规则库导入的规则也可以）；两项留空 = 不限制。\n" +
                            "双码机：固定码 = 勾「可重复扫」+「忽略」（扫到不记录）或取消「计入统计」（辅助码，只留痕）；流水码 = 只勾「计入统计」。\n" +
                            "查历史串号：条码在其他柜记录里出现过 → 报串号（防旧箱混入本柜）；忽略仅「可重复扫」时生效。",
                        fontSize = 10.sp,
                        color = Color(0xFF666666),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // 添加规则 + 规则库入口（一行三个按钮，紧凑）
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAddRule,
                    enabled = state.ruleInput.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.weight(1f).height(34.dp)
                ) {
                    Text("添加规则", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { showPicker = true },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.weight(1f).height(34.dp)
                ) {
                    Text("从规则库选择", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onOpenRuleLibrary,
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.weight(1f).height(34.dp)
                ) {
                    Text("规则库管理", fontSize = 11.sp)
                }
            }

            // v16：当前设置直接保存到规则库型号；以后用「从规则库选择」载入
            OutlinedButton(
                onClick = { showSaveToLibrary = true },
                enabled = state.rules.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.fillMaxWidth().height(34.dp)
            ) {
                Text("保存到规则库", fontSize = 11.sp)
            }

            Button(
                onClick = onStart,
                enabled = state.containerNo.isNotBlank() && state.rules.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) {
                Text("装柜", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }

            // ---------- 本柜条码规则（移到【装柜】按钮下方；固定区域，内部下拉滚动） ----------
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "本柜条码规则（前缀）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (state.containerNo.isBlank() || state.rules.isEmpty()) {
                    Text("请先填写柜号并加规则", fontSize = 9.sp, color = Color(0xFFD32F2F))
                }
            }
            Card(Modifier.fillMaxWidth().weight(1f)) {
                if (state.rules.isEmpty()) {
                    Text(
                        "尚未添加规则（扫码自动填入 / 手动录入 / 从规则库选择；拼装柜可加多条）",
                        fontSize = 11.sp, color = Color(0xFFD32F2F),
                        modifier = Modifier.padding(6.dp)
                    )
                } else {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 0.dp)
                        ) {
                            itemsIndexed(state.rules, key = { _, r -> r.prefix }) { i, rule ->
                                Column(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "规则${i + 1}：${rule.prefix}",
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        // 流水号范围（点击补录/修改；从规则库导入的规则同样可设）
                                        Text(
                                            serialRangeLabel(rule),
                                            fontSize = 10.sp,
                                            color = Color(0xFF1976D2),
                                            textDecoration = TextDecoration.Underline,
                                            maxLines = 1,
                                            modifier = Modifier
                                                .clickable { serialRule = rule }
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                        // 忽略（仅可重复扫时生效）：扫到不记录、不落盘、不计数
                                        Checkbox(
                                            checked = rule.allowDuplicate && rule.ignoreRepeat,
                                            onCheckedChange = {
                                                onRuleFlags(rule.prefix, rule.allowDuplicate, rule.countEnabled, rule.checkHistory, it)
                                            },
                                            enabled = rule.allowDuplicate
                                        )
                                        Text(
                                            "忽略",
                                            fontSize = 10.sp,
                                            color = if (rule.allowDuplicate) Color.Unspecified else Color.Gray
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = rule.allowDuplicate,
                                            onCheckedChange = {
                                                onRuleFlags(rule.prefix, it, rule.countEnabled, rule.checkHistory, rule.ignoreRepeat)
                                            }
                                        )
                                        Text("可重复扫", fontSize = 11.sp)
                                        Checkbox(
                                            checked = rule.countEnabled,
                                            onCheckedChange = {
                                                onRuleFlags(rule.prefix, rule.allowDuplicate, it, rule.checkHistory, rule.ignoreRepeat)
                                            }
                                        )
                                        Text("计入统计", fontSize = 11.sp)
                                        Checkbox(
                                            checked = rule.checkHistory,
                                            onCheckedChange = {
                                                onRuleFlags(rule.prefix, rule.allowDuplicate, rule.countEnabled, it, rule.ignoreRepeat)
                                            }
                                        )
                                        Text("查历史串号", fontSize = 11.sp)
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            "删除", fontSize = 12.sp, color = Color(0xFFD32F2F),
                                            modifier = Modifier
                                                .clickable { onRemoveRule(rule.prefix) }
                                                .padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 2.dp)
                                        )
                                    }
                                }
                                if (i < state.rules.lastIndex) HorizontalDivider(thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 规则行「流水」标签：点击可补录/修改范围；未设时显示「设范围」，末尾位数模式带「末N位」前缀 */
private fun serialRangeLabel(rule: RuleSpec): String {
    val tail = if (rule.serialTailLen > 0) "末${rule.serialTailLen}位 " else "流水 "
    return when {
        rule.serialMin.isNotBlank() && rule.serialMax.isNotBlank() -> "$tail${rule.serialMin}-${rule.serialMax}"
        rule.serialMin.isNotBlank() -> "$tail≥${rule.serialMin}"
        rule.serialMax.isNotBlank() -> "$tail≤${rule.serialMax}"
        rule.serialTailLen > 0 -> "末${rule.serialTailLen}位（未设范围）"
        else -> "设范围"
    }
}

/** 流水号范围编辑弹窗（从规则库导入 / 手动录入的规则均可补录；两项留空 = 清除限制） */
@Composable
private fun RuleSerialDialog(
    rule: RuleSpec,
    onDismiss: () -> Unit,
    onSave: (String, String, Int) -> Unit
) {
    var mn by remember { mutableStateOf(rule.serialMin) }
    var mx by remember { mutableStateOf(rule.serialMax) }
    var tail by remember { mutableStateOf(if (rule.serialTailLen > 0) rule.serialTailLen.toString() else "") }
    val invalid = mn.isNotBlank() && mx.isNotBlank() &&
        runCatching { java.math.BigInteger(mn) > java.math.BigInteger(mx) }.getOrDefault(false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("流水号范围", fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("规则：${rule.prefix}", fontSize = 13.sp, color = Color.Gray)
                OutlinedTextField(
                    value = mn,
                    onValueChange = { mn = it.filter { c -> c.isDigit() }.take(12) },
                    label = { Text("最小值（留空 = 不限）", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mx,
                    onValueChange = { mx = it.filter { c -> c.isDigit() }.take(12) },
                    label = { Text("最大值（留空 = 不限）", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tail,
                    onValueChange = { tail = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("末尾流水号位数（0/空 = 前缀后整段）", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (invalid) {
                    Text("最小值不能大于最大值", fontSize = 11.sp, color = Color(0xFFD32F2F))
                } else {
                    Text(
                        "普通条码：前缀后整段须为纯数字并在范围内。\n" +
                            "条码中间有无关字符/数字、流水号在末尾时：位数填 3，则 ASV12SDFWG05455056$ 取「056」核对（末尾符号忽略）。",
                        fontSize = 11.sp, color = Color(0xFF666666)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(mn, mx, tail.toIntOrNull() ?: 0) },
                enabled = !invalid
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (rule.serialMin.isNotBlank() || rule.serialMax.isNotBlank() || rule.serialTailLen > 0) {
                    TextButton(onClick = { onSave("", "", 0) }) { Text("清除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/**
 * 规则选择弹窗（v1.11.2）：按型号整组选择。
 * 同一型号下有多条前缀规则时，勾选型号即把该型号的全部规则一起加入本柜；
 * 若该型号部分规则已在本柜，则只补加剩余规则。
 * v1.12.0：按规则 id 取开关，避免同一前缀被多个型号共用时取错型号的设置。
 */
@Composable
private fun RulePickerDialog(
    library: List<RuleLibEntry>,
    models: List<RuleModel>,
    alreadyIn: List<String>,
    onConfirm: (List<Long>) -> Unit,
    onDismiss: () -> Unit
) {
    var filter by remember { mutableStateOf("") }
    var checkedGroupKeys by remember { mutableStateOf(setOf<String>()) }
    val modelDefaults = models.associate { it.name to it.defaultQuantity }
    val alreadyInSet = alreadyIn.toSet()
    val allGroups = buildRulePickGroups(library, modelDefaults, alreadyInSet, "")
    val filtered = if (filter.isBlank()) allGroups
    else buildRulePickGroups(library, modelDefaults, alreadyInSet, filter)
    val selectedGroups = selectedPickGroups(allGroups, checkedGroupKeys)
    val selectedMissing = selectedMissingRuleIds(allGroups, checkedGroupKeys, alreadyInSet)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从规则库选择") },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it.uppercase() },
                    label = { Text("过滤型号/前缀") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "按型号整组添加：一个型号下的多条规则会一起加入本柜。",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Spacer(Modifier.height(6.dp))
                if (library.isEmpty()) {
                    Text(
                        "规则库为空：请到「规则库管理」导入规则源或新增规则",
                        fontSize = 14.sp, color = Color(0xFFD32F2F)
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.key }) { group ->
                            val inContainer = group.allInContainer
                            val isChecked = inContainer || group.key in checkedGroupKeys
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !inContainer && group.missingCount > 0) {
                                        checkedGroupKeys =
                                            if (group.key in checkedGroupKeys) checkedGroupKeys - group.key
                                            else checkedGroupKeys + group.key
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    enabled = !inContainer && group.missingCount > 0,
                                    onCheckedChange = null
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        group.name,
                                        fontSize = 16.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val detail = buildString {
                                        append("${group.rules.size} 条规则：")
                                        append(group.rules.joinToString("、") { it.prefix })
                                        if (group.defaultQuantity > 0) {
                                            append("  ·  默认 ${group.defaultQuantity} 件")
                                        }
                                    }
                                    Text(
                                        detail,
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (group.inContainerCount > 0) {
                                        Text(
                                            if (inContainer) {
                                                "已在本柜（${group.rules.size} 条）"
                                            } else {
                                                "已在本柜 ${group.inContainerCount}/${group.rules.size} 条，" +
                                                    "选择后补加剩余 ${group.missingCount} 条"
                                            },
                                            fontSize = 12.sp,
                                            color = Color(0xFF1976D2)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                        if (filtered.isEmpty()) {
                            item {
                                Text(
                                    "无匹配",
                                    fontSize = 14.sp, color = Color.Gray,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedMissing) },
                enabled = selectedMissing.isNotEmpty()
            ) {
                Text("添加选中（${selectedGroups.size} 个型号 / ${selectedMissing.size} 条规则）")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 把当前设置保存为规则库型号：型号名称 + 当前规则 + 型号级默认数量 */
@Composable
private fun SaveToRuleLibraryDialog(
    ruleCount: Int,
    plan: Int,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val exists = name.trim().isNotEmpty() && existingNames.any { it == name.trim() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存到规则库", fontSize = 18.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("型号名称（如 T45LW）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "将把当前 $ruleCount 条规则（前缀 + 四个开关）保存为该型号，计划装柜数量 " +
                        (if (plan > 0) "$plan 件" else "不限") + " 作为该型号的默认数量。\n" +
                        "普通计数码不能与其他型号重复；可重复扫 / 辅助码 / 查历史串号 / 忽略码可跨型号共用。\n" +
                        "流水号范围 / 末尾位数只在本柜有效，不写入规则库。\n" +
                        "以后在设置页点「从规则库选择」选该型号即可载入。",
                    fontSize = 11.sp, color = Color.Gray,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (exists) {
                    Text(
                        "型号「${name.trim()}」已存在：保存后会覆盖该型号原有规则，其他型号不受影响。",
                        fontSize = 11.sp, color = Color(0xFFD32F2F),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) {
                Text(if (exists) "覆盖保存" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
