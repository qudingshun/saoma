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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.zhuanggui.pda.data.entity.RuleLibEntry
import com.zhuanggui.pda.ui.OutboundSetupState
import com.zhuanggui.pda.ui.RuleSpec

/**
 * 出库扫码设置页（整页固定布局，规则列表在固定区域内部滚动）：
 *  - 柜号 + 本柜数量（并列一行，窄框；已有柜号自动载入规则并填入已装数量 → 进入扫码页再扫提示超量；补装需手动改大数量）
 *  - 条码规则：按扫描键扫码 → 完整条码自动填入规则栏（人工删减成前缀）；也可手动录入、从规则库选择、规则库管理
 *  - 规则说明：固定小窗，内部下拉滚动查看
 *  - 规则列表：固定区域，内部下拉滚动；每条可勾「可重复扫」「计入统计」「查历史串号」
 *  - 【装柜】进入扫码界面；扫码页【返回设置】时柜号/数量/规则原样保留（装齐数量后规则清空）
 */
@Composable
fun OutboundSetupScreen(
    state: OutboundSetupState,
    library: List<RuleLibEntry>,
    onContainerNo: (String) -> Unit,
    onTotal: (String) -> Unit,
    onRuleInput: (String) -> Unit,
    onRuleAllowDup: (Boolean) -> Unit,
    onRuleCount: (Boolean) -> Unit,
    onRuleCheckHistory: (Boolean) -> Unit,
    onAddRule: () -> Unit,
    onRemoveRule: (String) -> Unit,
    onRuleFlags: (String, Boolean, Boolean, Boolean) -> Unit,
    onAddRules: (List<String>) -> Unit,
    onOpenRuleLibrary: () -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }

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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("出库扫码设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onBack) { Text("返回", fontSize = 12.sp) }
            }

            if (state.resumed) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9C4))) {
                    Text(
                        "该柜号今天已有装柜记录：已装数量已填入本柜数量，再扫码会提示超量；如需补装请先改大本柜数量",
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 13.sp
                    )
                }
            }

            // ---------- 柜号 + 本柜数量（并列） ----------
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
                    label = { Text("本柜数量") },
                    placeholder = { Text("0 = 不限") },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            // ---------- 条码规则 ----------
            Text("本柜条码规则（前缀）", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            // 说明文字：固定小窗，内部下拉滚动查看
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFF2F2F2))
                    .border(0.5.dp, Color(0xFFD0D0D0), RoundedCornerShape(4.dp))
            ) {
                Text(
                    "条码须以规则前缀开头，否则报“串号”。\n" +
                        "双码机：固定码 = 勾「可重复扫」+ 取消「计入统计」（不计 = 辅助码，只留痕）；流水码 = 只勾「计入统计」。\n" +
                        "查历史串号：条码在其他柜记录里出现过 → 报串号（防旧箱混入本柜）。",
                    fontSize = 11.sp,
                    color = Color(0xFF666666),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            // 录入规则：整行大输入框 —— 扫码自动填入完整条码，人工删减成前缀
            OutlinedTextField(
                value = state.ruleInput,
                onValueChange = onRuleInput,
                label = { Text("录入规则（前缀）", fontSize = 13.sp) },
                supportingText = {
                    Text("扫码自动填入完整条码 → 删减成前缀 → 点【添加规则】", fontSize = 11.sp)
                },
                singleLine = false,
                maxLines = 2,
                textStyle = TextStyle(fontSize = 16.sp),
                modifier = Modifier.fillMaxWidth()
            )

            // 新增规则时的勾选（双码机选项 + 查历史串号）
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = state.ruleAllowDup,
                    onCheckedChange = onRuleAllowDup
                )
                Text("可重复扫", fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Checkbox(
                    checked = state.ruleCount,
                    onCheckedChange = onRuleCount
                )
                Text("计入统计", fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Checkbox(
                    checked = state.ruleCheckHistory,
                    onCheckedChange = onRuleCheckHistory
                )
                Text("查历史串号", fontSize = 13.sp)
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

            // ---------- 规则列表：固定区域，内部下拉滚动查看 ----------
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
                            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "规则${i + 1}：${rule.prefix}",
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "删除", fontSize = 13.sp, color = Color(0xFFD32F2F),
                                        modifier = Modifier
                                            .clickable { onRemoveRule(rule.prefix) }
                                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = rule.allowDuplicate,
                                        onCheckedChange = {
                                            onRuleFlags(rule.prefix, it, rule.countEnabled, rule.checkHistory)
                                        }
                                    )
                                    Text("可重复扫", fontSize = 12.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Checkbox(
                                        checked = rule.countEnabled,
                                        onCheckedChange = {
                                            onRuleFlags(rule.prefix, rule.allowDuplicate, it, rule.checkHistory)
                                        }
                                    )
                                    Text("计入统计", fontSize = 12.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Checkbox(
                                        checked = rule.checkHistory,
                                        onCheckedChange = {
                                            onRuleFlags(rule.prefix, rule.allowDuplicate, rule.countEnabled, it)
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

            Button(
                onClick = onStart,
                enabled = state.containerNo.isNotBlank() && state.rules.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("装柜", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            if (state.containerNo.isBlank() || state.rules.isEmpty()) {
                Text("请先填写柜号并至少添加 1 条条码规则", fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}

/** 规则选择弹窗：从规则库多选加入本柜（已在本柜的显示为选中且不可点） */
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
                    label = { Text("过滤前缀/名称") },
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
