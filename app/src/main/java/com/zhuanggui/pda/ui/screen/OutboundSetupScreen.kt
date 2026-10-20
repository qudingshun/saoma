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
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.zhuanggui.pda.data.entity.RuleLibEntry
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
    onRuleIgnore: (Boolean) -> Unit,
    onAddRule: () -> Unit,
    onRemoveRule: (String) -> Unit,
    onRuleFlags: (String, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onRuleSerial: (String, String, String) -> Unit,
    onAddRules: (List<String>) -> Unit,
    onOpenRuleLibrary: () -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    var operatorMenu by remember { mutableStateOf(false) }
    var showRuleHelp by remember { mutableStateOf(false) }
    // 非空 = 正在补录/修改该规则的流水号范围
    var serialRule by remember { mutableStateOf<RuleSpec?>(null) }

    serialRule?.let { rule ->
        RuleSerialDialog(
            rule = rule,
            onDismiss = { serialRule = null },
            onSave = { mn, mx ->
                onRuleSerial(rule.prefix, mn, mx)
                serialRule = null
            }
        )
    }

    if (showPicker) {
        RulePickerDialog(
            library = library,
            alreadyIn = state.rules.map { it.prefix },
            onConfirm = { picked ->
                onAddRules(picked)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }

    Scaffold { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("出库扫码设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onBack) { Text("返回", fontSize = 12.sp) }
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
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 13.sp
                    )
                }
            }

            // ---------- 柜号 + 计划装柜数量（并列） ----------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.containerNo,
                    onValueChange = onContainerNo,
                    label = { Text("柜号") },
                    placeholder = { Text("字母+数字") },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.totalQuantity,
                    onValueChange = onTotal,
                    label = { Text("计划装柜数量", fontSize = 14.sp) },
                    placeholder = {
                        Text(
                            if (state.scannedQuantity > 0 && state.storedPlan > 0)
                                "留空 = 已存计划 ${state.storedPlan}"
                            else "0/空 = 不限",
                            fontSize = 12.sp
                        )
                    },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            // ---------- 操作员工号（选填：下拉 A/B/C/D 或扫工牌/手输）+ 已扫码数量（同款只读框） ----------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = state.operatorId,
                        onValueChange = onOperatorId,
                        label = { Text("操作员工号", fontSize = 14.sp) },
                        placeholder = { Text("默认 A/B/C/D，可手输", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 15.sp),
                        trailingIcon = {
                            IconButton(onClick = { operatorMenu = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = "选择工号")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged {
                                // 焦点路由：工号栏聚焦时扫码填入工号（替换），其余位置填规则栏
                                onScanTarget(if (it.isFocused) SetupScanTarget.OPERATOR else SetupScanTarget.RULE)
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
                OutlinedTextField(
                    value = "${state.scannedQuantity} 件",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("已扫码数量（今天）", fontSize = 14.sp) },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        color = if (state.scannedQuantity > 0) Color(0xFF2E7D32) else Color(0xFF555555)
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            // ---------- 录入规则（前缀）+ 流水号范围（v1.7.0：限定数据范围） ----------
            OutlinedTextField(
                value = state.ruleInput,
                onValueChange = onRuleInput,
                label = { Text("录入规则（前缀）", fontSize = 13.sp) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.ruleSerialMin,
                    onValueChange = onRuleSerialMin,
                    label = { Text("流水号最小值", fontSize = 13.sp) },
                    placeholder = { Text("选填，留空 = 不限", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.ruleSerialMax,
                    onValueChange = onRuleSerialMax,
                    label = { Text("流水号最大值", fontSize = 13.sp) },
                    placeholder = { Text("选填，留空 = 不限", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            // 新增规则时的勾选（双码机选项 + 查历史串号 + 忽略）
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = state.ruleAllowDup,
                    onCheckedChange = onRuleAllowDup
                )
                Text("可重复扫", fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Checkbox(
                    checked = state.ruleCount,
                    onCheckedChange = onRuleCount
                )
                Text("计入统计", fontSize = 13.sp)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = state.ruleCheckHistory,
                    onCheckedChange = onRuleCheckHistory
                )
                Text("查历史串号", fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Checkbox(
                    checked = state.ruleAllowDup && state.ruleIgnoreRepeat,
                    onCheckedChange = onRuleIgnore,
                    enabled = state.ruleAllowDup
                )
                Text(
                    "忽略（不记录）",
                    fontSize = 13.sp,
                    color = if (state.ruleAllowDup) Color.Unspecified else Color.Gray
                )
            }

            // 规则说明（默认收起，点击展开；展开时为固定小窗，内部下拉滚动查看）
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showRuleHelp = !showRuleHelp }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (showRuleHelp) "规则说明 ▾（点击收起）" else "规则说明 ▸（点击展开）",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
            }
            if (showRuleHelp) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFF2F2F2))
                        .border(0.5.dp, Color(0xFFD0D0D0), RoundedCornerShape(4.dp))
                ) {
                    Text(
                        "条码须以规则前缀开头；设了流水号范围时，前缀后的数字须落在最小~最大值之间，否则报“串号”。\n" +
                            "流水号范围可在规则行点「流水 …」补录/修改（从规则库导入的规则也可以）；两项留空 = 不限制。\n" +
                            "双码机：固定码 = 勾「可重复扫」+「忽略」（扫到不记录）或取消「计入统计」（辅助码，只留痕）；流水码 = 只勾「计入统计」。\n" +
                            "查历史串号：条码在其他柜记录里出现过 → 报串号（防旧箱混入本柜）；忽略仅「可重复扫」时生效。",
                        fontSize = 11.sp,
                        color = Color(0xFF666666),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 3.dp)
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
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Text("添加规则", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { showPicker = true },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Text("从规则库选择", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onOpenRuleLibrary,
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Text("规则库管理", fontSize = 12.sp)
                }
            }

            Button(
                onClick = onStart,
                enabled = state.containerNo.isNotBlank() && state.rules.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("装柜", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            // ---------- 本柜条码规则（移到【装柜】按钮下方；固定区域，内部下拉滚动） ----------
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "本柜条码规则（前缀）",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (state.containerNo.isBlank() || state.rules.isEmpty()) {
                    Text("请先填写柜号并加规则", fontSize = 10.sp, color = Color(0xFFD32F2F))
                }
            }
            Card(Modifier.fillMaxWidth().weight(1f)) {
                if (state.rules.isEmpty()) {
                    Text(
                        "尚未添加规则（扫码自动填入 / 手动录入 / 从规则库选择；拼装柜可加多条）",
                        fontSize = 13.sp, color = Color(0xFFD32F2F),
                        modifier = Modifier.padding(10.dp)
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        itemsIndexed(state.rules, key = { _, r -> r.prefix }) { i, rule ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "规则${i + 1}：${rule.prefix}",
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // 流水号范围（点击补录/修改；从规则库导入的规则同样可设）
                                    Text(
                                        serialRangeLabel(rule),
                                        fontSize = 11.sp,
                                        color = Color(0xFF1976D2),
                                        textDecoration = TextDecoration.Underline,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .clickable { serialRule = rule }
                                            .padding(horizontal = 4.dp, vertical = 8.dp)
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
                                        fontSize = 11.sp,
                                        color = if (rule.allowDuplicate) Color.Unspecified else Color.Gray
                                    )
                                    Text(
                                        "删除", fontSize = 13.sp, color = Color(0xFFD32F2F),
                                        modifier = Modifier
                                            .clickable { onRemoveRule(rule.prefix) }
                                            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = rule.allowDuplicate,
                                        onCheckedChange = {
                                            onRuleFlags(rule.prefix, it, rule.countEnabled, rule.checkHistory, rule.ignoreRepeat)
                                        }
                                    )
                                    Text("可重复扫", fontSize = 12.sp)
                                    Checkbox(
                                        checked = rule.countEnabled,
                                        onCheckedChange = {
                                            onRuleFlags(rule.prefix, rule.allowDuplicate, it, rule.checkHistory, rule.ignoreRepeat)
                                        }
                                    )
                                    Text("计入统计", fontSize = 12.sp)
                                    Checkbox(
                                        checked = rule.checkHistory,
                                        onCheckedChange = {
                                            onRuleFlags(rule.prefix, rule.allowDuplicate, rule.countEnabled, it, rule.ignoreRepeat)
                                        }
                                    )
                                    Text("查历史串号", fontSize = 12.sp)
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

/** 规则行「流水」标签：点击可补录/修改范围；未设时也显示，提示可设（v1.7.0 补录能力） */
private fun serialRangeLabel(rule: RuleSpec): String = when {
    rule.serialMin.isNotBlank() && rule.serialMax.isNotBlank() -> "流水 ${rule.serialMin}-${rule.serialMax}"
    rule.serialMin.isNotBlank() -> "流水 ≥${rule.serialMin}"
    rule.serialMax.isNotBlank() -> "流水 ≤${rule.serialMax}"
    else -> "流水 不限"
}

/** 流水号范围编辑弹窗（从规则库导入 / 手动录入的规则均可补录；两项留空 = 清除限制） */
@Composable
private fun RuleSerialDialog(
    rule: RuleSpec,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var mn by remember { mutableStateOf(rule.serialMin) }
    var mx by remember { mutableStateOf(rule.serialMax) }
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
                if (invalid) {
                    Text("最小值不能大于最大值", fontSize = 11.sp, color = Color(0xFFD32F2F))
                } else {
                    Text(
                        "设了范围后，前缀后的流水号须为纯数字且落在范围内，否则报“串号”；两项都留空 = 不限制。",
                        fontSize = 11.sp, color = Color(0xFF666666)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(mn, mx) }, enabled = !invalid) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (rule.serialMin.isNotBlank() || rule.serialMax.isNotBlank()) {
                    TextButton(onClick = { onSave("", "") }) { Text("清除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/** 规则选择弹窗：从规则库多选加入本柜（已在本柜的显示为选中且不可点；连同四个开关一起复制） */
@Composable
private fun RulePickerDialog(
    library: List<RuleLibEntry>,
    alreadyIn: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var filter by remember { mutableStateOf("") }
    var checkedPrefixes by remember { mutableStateOf(setOf<String>()) }

    val filtered = if (filter.isBlank()) library
    else library.filter {
        it.prefix.contains(filter, ignoreCase = true) || it.name.contains(filter, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从规则库选择") },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it.uppercase() },
                    label = { Text("过滤前缀/型号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                if (library.isEmpty()) {
                    Text(
                        "规则库为空：请到「规则库管理」导入规则源或新增规则",
                        fontSize = 14.sp, color = Color(0xFFD32F2F)
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.id }) { entry ->
                            val inContainer = entry.prefix in alreadyIn
                            val isChecked = inContainer || entry.prefix in checkedPrefixes
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !inContainer) {
                                        checkedPrefixes =
                                            if (entry.prefix in checkedPrefixes) checkedPrefixes - entry.prefix
                                            else checkedPrefixes + entry.prefix
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    enabled = !inContainer,
                                    onCheckedChange = null
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(entry.prefix, fontSize = 16.sp)
                                    if (entry.name.isNotBlank()) {
                                        Text(entry.name, fontSize = 12.sp, color = Color.Gray)
                                    }
                                    flagSummary(entry)?.let {
                                        Text(it, fontSize = 11.sp, color = Color(0xFF616161))
                                    }
                                }
                                if (inContainer) {
                                    Text("已在本柜", fontSize = 12.sp, color = Color.Gray)
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
                onClick = { onConfirm(checkedPrefixes.toList()) },
                enabled = checkedPrefixes.isNotEmpty()
            ) { Text("添加选中（${checkedPrefixes.size}）") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
