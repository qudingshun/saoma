package com.zhuanggui.pda.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanggui.pda.data.dao.ScanRecordGroup
import com.zhuanggui.pda.data.entity.ScanRecord
import com.zhuanggui.pda.data.entity.ScanResult
import com.zhuanggui.pda.ui.formatUiDateTime

/**
 * 扫码记录查看页（两级聚合）：
 *  - 一级：按「日期 + 柜号」聚合，点柜号进入二级看全部条码
 *  - 删除：单组删除 / 删除本日 / 批量勾选删除（只删记录，柜子与规则保留）
 *  - 文件落盘在扫码时已自动完成，此页只读展示 + 清理
 */
@Composable
fun RecordsScreen(
    groups: List<ScanRecordGroup>,
    selected: ScanRecordGroup?,
    groupRecords: List<ScanRecord>,
    onOpenGroup: (ScanRecordGroup?) -> Unit,
    onDeleteGroups: (List<ScanRecordGroup>) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenTsv: () -> Unit,
    onBack: () -> Unit
) {
    // 系统返回键：二级 → 一级
    BackHandler(enabled = selected != null) { onOpenGroup(null) }

    if (selected == null) {
        GroupListPage(
            groups = groups,
            onOpenGroup = { onOpenGroup(it) },
            onDeleteGroups = onDeleteGroups,
            onOpenTrash = onOpenTrash,
            onOpenTsv = onOpenTsv,
            onBack = onBack
        )
    } else {
        GroupDetailPage(
            group = selected,
            records = groupRecords,
            onBackToGroups = { onOpenGroup(null) },
            onBack = onBack
        )
    }
}

// ==================== 一级：日期 + 柜号聚合 ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupListPage(
    groups: List<ScanRecordGroup>,
    onOpenGroup: (ScanRecordGroup) -> Unit,
    onDeleteGroups: (List<ScanRecordGroup>) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenTsv: () -> Unit,
    onBack: () -> Unit
) {
    var filter by rememberSaveable { mutableStateOf("") }
    var batchMode by rememberSaveable { mutableStateOf(false) }
    // 勾选集合与 filter/batchMode 一样支持重建恢复（存为 List，Bundle 可携带）
    var checked by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(emptySet<String>()) }
    var pending by remember { mutableStateOf<List<ScanRecordGroup>?>(null) }

    fun key(g: ScanRecordGroup) = "${g.dayLabel}|${g.containerId}"

    val filtered = if (filter.isBlank()) groups
    else groups.filter {
        it.containerNo.contains(filter, ignoreCase = true) || it.dayLabel.contains(filter)
    }
    val byDay = filtered.groupBy { it.dayLabel }

    // 删除确认（三次确认防误触；单个 / 本日 / 批量共用）
    pending?.let { list ->
        val cnt = list.sumOf { it.cnt }
        TripleConfirmDialog(
            title = "删除扫码记录",
            steps = listOf(
                "将删除 ${list.size} 组、共 $cnt 条记录（只删记录，柜子与规则保留）。",
                "再次确认：删除后对应柜子计数将从 0 重新开始；当天落盘文件会移入文件回收站。",
                "最后确认：删除的记录会进入「回收站」，可逐条撤回（记录页长按标题进入）。"
            ),
            onConfirmed = {
                onDeleteGroups(list)
                pending = null
                checked = emptySet()
            },
            onDismiss = { pending = null }
        )
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
                Text(
                    "扫码记录",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onOpenTrash)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (batchMode) {
                        OutlinedButton(onClick = { batchMode = false; checked = emptySet() }) {
                            Text("退出批量", fontSize = 14.sp)
                        }
                    } else {
                        OutlinedButton(onClick = { batchMode = true }) {
                            Text("批量删除", fontSize = 14.sp)
                        }
                    }
                    OutlinedButton(onClick = onBack) { Text("返回", fontSize = 14.sp) }
                }
            }

            Text(
                "按「日期 + 柜号」聚合；点柜号看全部条码，可单个/批量删除（三次确认）；回收站：长按本页标题；TSV 原文：直接读落盘文件",
                fontSize = 12.sp, color = Color.Gray
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("过滤柜号/日期", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onOpenTsv,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(48.dp)
                ) { Text("TSV 原文", fontSize = 12.sp) }
            }

            if (batchMode) {
                val selCount = checked.size
                val selRecords = groups.filter { key(it) in checked }.sumOf { it.cnt }
                Text(
                    "已选 $selCount 组 · $selRecords 条（勾选柜号或整日）",
                    fontSize = 13.sp, color = Color(0xFF1565C0),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            HorizontalDivider(Modifier.padding(top = 4.dp))

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (groups.isEmpty()) "暂无扫码记录" else "无匹配",
                            fontSize = 14.sp, color = Color.Gray,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
                byDay.forEach { (day, dayGroups) ->
                    item(key = "day-$day") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (batchMode) {
                                val allChecked = dayGroups.all { key(it) in checked }
                                Checkbox(
                                    checked = allChecked,
                                    onCheckedChange = {
                                        checked = if (allChecked) {
                                            checked - dayGroups.map { key(it) }.toSet()
                                        } else {
                                            checked + dayGroups.map { key(it) }
                                        }
                                    }
                                )
                            }
                            Text(day, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${dayGroups.sumOf { it.cnt }} 条",
                                fontSize = 12.sp, color = Color.Gray
                            )
                            if (!batchMode) {
                                TextButton(onClick = { pending = dayGroups }) {
                                    Text(
                                        // 过滤态下只覆盖匹配到的分组 → 文案如实标注范围
                                        if (filter.isBlank()) "删除本日" else "删除匹配 ${dayGroups.size} 组",
                                        fontSize = 12.sp, color = Color(0xFFD32F2F)
                                    )
                                }
                            }
                        }
                    }
                    items(dayGroups, key = { key(it) }) { g ->
                        GroupRow(
                            group = g,
                            batchMode = batchMode,
                            checked = key(g) in checked,
                            onToggleCheck = {
                                checked = if (key(g) in checked) checked - key(g)
                                else checked + key(g)
                            },
                            onOpen = { onOpenGroup(g) },
                            onDelete = { pending = listOf(g) }
                        )
                    }
                }
            }

            if (batchMode) {
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { pending = groups.filter { key(it) in checked } },
                        enabled = checked.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("删除选中（${checked.size} 组）", fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: ScanRecordGroup,
    batchMode: Boolean,
    checked: Boolean,
    onToggleCheck: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (batchMode) onToggleCheck() else onOpen() }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (batchMode) {
            Checkbox(checked = checked, onCheckedChange = null)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("柜号 ${group.containerNo}", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("  ${group.cnt} 条", fontSize = 13.sp, color = Color.Gray)
            }
            Text(resultBreakdown(group), fontSize = 12.sp, color = Color.Gray)
        }
        if (!batchMode) {
            TextButton(onClick = onDelete) {
                Text("删除", fontSize = 13.sp, color = Color(0xFFD32F2F))
            }
        }
    }
    HorizontalDivider(thickness = 0.5.dp)
}

private fun resultBreakdown(g: ScanRecordGroup): String {
    val parts = buildList {
        if (g.okCnt > 0) add("正常 ${g.okCnt}")
        if (g.mmCnt > 0) add("串号 ${g.mmCnt}")
        if (g.dupCnt > 0) add("重复 ${g.dupCnt}")
        if (g.overCnt > 0) add("超量 ${g.overCnt}")
    }
    return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
}

// ==================== 二级：某柜某日条码 ====================

@Composable
private fun GroupDetailPage(
    group: ScanRecordGroup,
    records: List<ScanRecord>,
    onBackToGroups: () -> Unit,
    onBack: () -> Unit
) {
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
                Text("柜号 ${group.containerNo}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onBackToGroups) { Text("返回分组", fontSize = 14.sp) }
                    OutlinedButton(onClick = onBack) { Text("返回", fontSize = 14.sp) }
                }
            }
            Text(
                "${group.dayLabel} · 共 ${group.cnt} 条 · 当日条码（新 → 旧）",
                fontSize = 12.sp, color = Color.Gray
            )
            HorizontalDivider(Modifier.padding(top = 4.dp))

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(records, key = { it.id }) { r -> RecordItem(r) }
                if (records.isEmpty()) {
                    item {
                        Text(
                            "该组记录已删除",
                            fontSize = 14.sp, color = Color.Gray,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordItem(r: ScanRecord) {
    val (tag, tagColor) = when (r.result) {
        ScanResult.OK -> "正常" to Color(0xFF388E3C)
        ScanResult.SERIAL_MISMATCH -> "串号" to Color(0xFFD32F2F)
        ScanResult.SERIAL_ALARM -> "报警" to Color(0xFFD32F2F)
        ScanResult.DUPLICATE -> "重复" to Color(0xFFD32F2F)
        ScanResult.OVER_LIMIT -> "超量" to Color(0xFFF57F17)
        ScanResult.AUX -> "辅助" to Color(0xFF1976D2)
        else -> r.result.name to Color.Gray
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tag,
                fontSize = 12.sp, color = Color.White,
                modifier = Modifier
                    .background(tagColor)
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
            Text(
                "  ${r.fullCode}",
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            "${timeText(r)}　柜号：${r.containerNo}" +
                if (r.operatorId.isNotBlank()) "　操作员：${r.operatorId}" else "",
            fontSize = 12.sp, color = Color.Gray
        )
    }
}

private fun timeText(r: ScanRecord): String =
    runCatching { formatUiDateTime(r.scannedAt) }.getOrDefault("")
