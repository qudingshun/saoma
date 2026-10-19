package com.zhuanggui.pda.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanggui.pda.data.entity.ScanRecord
import com.zhuanggui.pda.data.entity.ScanResult
import com.zhuanggui.pda.export.FileExporter
import com.zhuanggui.pda.ui.OutboundScanState

/**
 * 出库扫码界面（竖屏）：
 *  - 顶部：返回设置（可追加规则）+ 报警设置（蜂鸣/振动开关，v1.5.8）；扫码触发以物理扳机为准（机内软触发按钮经真机验证无效，已移除——审计 15）
 *  - 撤回/前进：本柜当前会话的记录操作可回退、重做（撤销扫码 / 恢复删除）
 *  - 报警弹窗（重复/串号/超量）：点确认停止报警；重复/串号/超量还可一步删除该记录
 *  - 报警三重编码（v1.5.8）：结果一律「颜色 + 图标 + 文字」同现（报警弹窗 / 计数卡 / 记录行 / 最新输出 / 完成弹窗）
 *  - 装柜完成弹窗：本柜「已扫码数量」达到「计划装柜数量」时弹一次，并提示汇总行写入结果（/sdcard/ZhuangGui/装柜完成汇总.tsv）
 *  - 记录明细：重复/串号/辅助码/超量点按一步确认删除；正常记录点按需三步确认；删除均为软删除（可在
 *    回收站或「撤回」恢复）
 *  - 任意触屏操作立即停止报警
 */
@Composable
fun OutboundScanScreen(
    state: OutboundScanState,
    records: List<ScanRecord>,
    canUndo: Boolean,
    canRedo: Boolean,
    alarmBeep: Boolean,
    alarmVibrate: Boolean,
    onAlarmBeep: (Boolean) -> Unit,
    onAlarmVibrate: (Boolean) -> Unit,
    onBackToSetup: () -> Unit,
    onManual: (String) -> Unit,
    onStopAlarm: () -> Unit,
    onDeleteRecord: (Long) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit
) {
    // 系统返回键 = 返回设置（停振 + 复位阶段，防止留在后台继续处理扫码）
    BackHandler { onBackToSetup() }

    // 报警弹窗：按 scanSeq 去重（同一枪只弹一次；确认/触屏后不再弹）
    var ackedSeq by rememberSaveable { mutableStateOf(-1L) }
    var ackedCompleted by rememberSaveable { mutableStateOf(0L) }        // 装柜完成弹窗：同一事件只弹一次（重建后仍防重复弹）
    var deleteTarget by remember { mutableStateOf<ScanRecord?>(null) }   // 重复/串号：一步确认
    var tripleTarget by remember { mutableStateOf<ScanRecord?>(null) }   // 正常记录：三步确认
    var showAlarmSettings by remember { mutableStateOf(false) }          // 报警设置弹窗（蜂鸣/振动开关，v1.5.8）

    val errorResult = state.lastResult?.takeIf { it != ScanResult.OK && it != ScanResult.AUX }
    if (errorResult != null && ackedSeq != state.scanSeq) {
        AlertDialog(
            onDismissRequest = {
                ackedSeq = state.scanSeq
                onStopAlarm()
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ResultIcon(errorResult, 20)
                    Text(
                        when (state.lastResult) {
                            ScanResult.DUPLICATE -> "重复扫码"
                            ScanResult.SERIAL_MISMATCH -> "串号报警"
                            ScanResult.OVER_LIMIT -> "超出计划装柜数量"
                            else -> "报警"
                        },
                        fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            },
            text = {
                Text(
                    buildString {
                        append(state.lastMessage)
                        if (state.lastResult == ScanResult.DUPLICATE ||
                            state.lastResult == ScanResult.SERIAL_MISMATCH ||
                            state.lastResult == ScanResult.OVER_LIMIT
                        ) {
                            append("\n\n可点「删除这条记录」移除刚扫的这条（进回收站）；")
                            append("也可点按下方记录列表中的重复/串号/超量行删除。")
                        }
                    },
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ackedSeq = state.scanSeq
                    onStopAlarm()
                }) { Text("知道了（停止报警）") }
            },
            dismissButton = if (state.lastResult == ScanResult.DUPLICATE ||
                state.lastResult == ScanResult.SERIAL_MISMATCH ||
                state.lastResult == ScanResult.OVER_LIMIT
            ) {
                {
                    TextButton(onClick = {
                        ackedSeq = state.scanSeq
                        onStopAlarm()
                        onDeleteRecord(state.lastRecordId)
                    }) {
                        Text(
                            when (state.lastResult) {
                                ScanResult.DUPLICATE -> "删除这条重复记录"
                                ScanResult.OVER_LIMIT -> "删除这条超量记录"
                                else -> "删除这条串号记录"
                            }
                        )
                    }
                }
            } else null
        )
    }

    // 装柜完成弹窗：本柜装齐那一枪弹一次（有报警弹窗时先让报警；「返回设置」后规则将清空）
    if (errorResult == null && state.completedEvent > 0 && state.completedEvent != ackedCompleted) {
        AlertDialog(
            onDismissRequest = { ackedCompleted = state.completedEvent },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ResultIcon(ScanResult.OK, 20)
                    Text(
                        "装柜完成！",
                        fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            },
            text = {
                Text(
                    "本柜 ${state.activeContainer?.containerNo ?: ""} 已装齐：已扫码 ${state.okCount} / 计划 ${state.total}。\n" +
                        (if (state.completedSummarySaved)
                            "已追加一行到 ${FileExporter.summaryDisplayPath()}。\n"
                        else
                            "⚠ 汇总写入失败，请检查存储权限。\n") +
                        "点「返回设置」后规则将清空，该柜号下批按全新柜处理。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ackedCompleted = state.completedEvent
                    onBackToSetup()
                }) { Text("返回设置", fontSize = 13.sp) }
            },
            dismissButton = {
                TextButton(onClick = { ackedCompleted = state.completedEvent }) {
                    Text("留在本页", fontSize = 13.sp)
                }
            }
        )
    }

    // 报警设置（v1.5.8）：蜂鸣/振动开关，勾选即时生效并持久化（下次启动保持）
    if (showAlarmSettings) {
        AlertDialog(
            onDismissRequest = { showAlarmSettings = false },
            title = { Text("报警设置", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = alarmBeep, onCheckedChange = onAlarmBeep)
                        Text("蜂鸣提醒", fontSize = 14.sp)
                    }
                    Text(
                        "报警（重复/串号/超量）时循环蜂鸣；正常扫码短响一声。关闭后完全静音。",
                        fontSize = 11.sp, color = Color.Gray,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = alarmVibrate, onCheckedChange = onAlarmVibrate)
                        Text("振动提醒", fontSize = 14.sp)
                    }
                    Text(
                        "报警时循环振动；触屏 / 弹窗确认 / 下一枪停振，10 秒兜底自动停。",
                        fontSize = 11.sp, color = Color.Gray,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showAlarmSettings = false }) { Text("完成") } }
        )
    }

    // 重复/串号/辅助码/超量：一步确认删除
    deleteTarget?.let { target ->
        val kind = when (target.result) {
            ScanResult.DUPLICATE -> "重复"
            ScanResult.AUX -> "辅助码"
            ScanResult.OVER_LIMIT -> "超量"
            else -> "串号"
        }
        val effect = if (target.result == ScanResult.AUX) "本柜计数不受影响（辅助码不计入统计）"
        else "本柜「${kind}」计数会同步 -1"
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条${kind}记录？", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "${target.fullCode}\n\n删除后${effect}，" +
                        "记录与落盘文件移入回收站（可撤回），该条码可重新扫码装柜。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteRecord(target.id)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    // 正常记录：三步确认删除（防误点）
    tripleTarget?.let { target ->
        TripleConfirmDialog(
            title = "删除正常记录",
            steps = listOf(
                "将删除这条正常录入记录：\n${target.fullCode}\n\n本柜正常计数会同步 -1。",
                "再次确认：删除后该条码可重新扫码装柜，记录与落盘文件移入回收站。",
                "最后确认：确定删除吗？（扫码页「撤回」可立即恢复）"
            ),
            onConfirmed = {
                onDeleteRecord(target.id)
                tripleTarget = null
            },
            onDismiss = { tripleTarget = null }
        )
    }

    Scaffold { pad ->
        Column(
            Modifier
                .fillMaxSize()
                // 报警底色放在 padding(pad) 之前：edge-to-edge 下也铺满状态栏区域
                .background(state.screenColor)
                // 任意触屏操作立即停止报警振动（不消费事件，不影响按钮/输入框）
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onStopAlarm()
                    }
                }
                .padding(pad)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // ---------- 顶部工具条 ----------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("出库扫码", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { showAlarmSettings = true }) { Text("报警设置", fontSize = 12.sp) }
                    OutlinedButton(onClick = onBackToSetup) { Text("返回设置", fontSize = 12.sp) }
                }
            }

            // ---------- 撤回 / 前进 ----------
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onUndo,
                    enabled = canUndo,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("↶ 撤回上一步", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onRedo,
                    enabled = canRedo,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("↷ 前进（重做）", fontSize = 11.sp)
                }
            }

            // ---------- 柜号 & 规则 ----------
            Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        "柜号：${state.activeContainer?.containerNo ?: "—"}",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "规则：${
                            state.rules.joinToString(" / ") { r ->
                                val tags = buildList {
                                    if (r.allowDuplicate) add("可重复")
                                    if (!r.countEnabled) add("不计")
                                    if (r.checkHistory) add("查历史")
                                }
                                r.prefix + if (tags.isEmpty()) "" else "（${tags.joinToString("/")}）"
                            }.ifBlank { "—" }
                        }",
                        fontSize = 12.sp, color = Color.Gray
                    )
                    Text(
                        "计划装柜数量：${if (state.total > 0) state.total.toString() else "不限"}",
                        fontSize = 14.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已扫码数量：${state.okCount}",
                            fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            color = if (state.total > 0 && state.okCount >= state.total) Color(0xFF388E3C)
                            else Color.Unspecified
                        )
                        Text(
                            "  累计记录：${state.totalRecords}",
                            fontSize = 12.sp, color = Color.Gray
                        )
                    }
                    Text(
                        if (state.lastFullCode.isBlank()) "每扫一码自动保存到 TSV 文件"
                        else if (state.fileSaved) "✓ 已自动保存（${FileExporter.displayPath()}/）"
                        else "⚠ 最近一条未写入文件，请检查存储权限",
                        fontSize = 11.sp,
                        color = if (state.lastFullCode.isNotBlank() && !state.fileSaved)
                            Color(0xFFD32F2F) else Color(0xFF388E3C)
                    )
                }
            }

            // ---------- 计数卡 ----------
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutCount("正常", state.okCount, Color(0xFF388E3C), ScanResult.OK, Modifier.weight(1f))
                OutCount("串号", state.mismatchCount, Color(0xFFD32F2F), ScanResult.SERIAL_MISMATCH, Modifier.weight(1f))
                OutCount("重复", state.duplicateCount, Color(0xFFD32F2F), ScanResult.DUPLICATE, Modifier.weight(1f))
                OutCount("超量", state.overCount, Color(0xFFF57F17), ScanResult.OVER_LIMIT, Modifier.weight(1f))
            }

            // ---------- 最新输出：柜号，条码 ----------
            Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text("输出：", fontSize = 11.sp, color = Color.Gray)
                    Text(
                        state.lastFullCode.ifBlank { "—" },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        state.lastResult?.let { ResultIcon(it, 14) }
                        Text(
                            state.lastMessage,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = if (state.lastResult != null) 4.dp else 0.dp)
                        )
                    }
                }
            }

            // ---------- 扫码录入（HID 扫码枪 / 手动） ----------
            com.zhuanggui.pda.ui.HidScanField(
                onSubmit = { onManual(it) },
                modifier = Modifier.padding(top = 4.dp)
            )

            // ---------- 记录明细 ----------
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("本柜记录", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "共 ${state.totalRecords} 条 · 最近 ${records.size} 条可点按删除（正常记录需三次确认）",
                    fontSize = 11.sp, color = Color.Gray
                )
            }
            HorizontalDivider()

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(records, key = { it.id }) { r ->
                    RecordRow(
                        r = r,
                        deletable = r.result == ScanResult.OK ||
                            r.result == ScanResult.DUPLICATE ||
                            r.result == ScanResult.SERIAL_MISMATCH ||
                            r.result == ScanResult.AUX ||
                            r.result == ScanResult.OVER_LIMIT,
                        onDeleteClick = {
                            if (r.result == ScanResult.OK) tripleTarget = r
                            else deleteTarget = r
                        }
                    )
                }
            }
        }
    }
}

/** 计数卡（v1.5.8：颜色 + 图标 + 文字三重编码，不只靠颜色区分） */
@Composable
private fun OutCount(label: String, count: Int, color: Color, result: ScanResult, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = resultIcon(result),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Text(count.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RecordRow(
    r: ScanRecord,
    deletable: Boolean,
    onDeleteClick: () -> Unit
) {
    val (tag, tagColor) = when (r.result) {
        ScanResult.OK -> "正常" to Color(0xFF388E3C)
        ScanResult.SERIAL_MISMATCH -> "串号" to Color(0xFFD32F2F)
        ScanResult.DUPLICATE -> "重复" to Color(0xFFD32F2F)
        ScanResult.OVER_LIMIT -> "超量" to Color(0xFFF57F17)
        ScanResult.AUX -> "辅助" to Color(0xFF1976D2)
        else -> r.result.name to Color.Gray
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (deletable) Modifier.clickable(onClick = onDeleteClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(tagColor)
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Icon(
                imageVector = resultIcon(r.result),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(11.dp)
            )
            Text(
                tag,
                fontSize = 11.sp,
                color = Color.White,
                modifier = Modifier.padding(start = 3.dp)
            )
        }
        Text(
            "  ${r.fullCode}",
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (deletable) {
            Text("点按删除", fontSize = 10.sp, color = Color.Gray)
        }
    }
}

// ---------- 报警三重编码（v1.5.8）：颜色 + 图标 + 文字同现，不只靠颜色区分 ----------

/** 结果色（与计数卡/记录行标签一致；报警弹窗、最新输出、完成弹窗的图标 tint 也取自这里） */
private fun resultColor(result: ScanResult): Color = when (result) {
    ScanResult.OK -> Color(0xFF388E3C)
    ScanResult.SERIAL_MISMATCH, ScanResult.DUPLICATE -> Color(0xFFD32F2F)
    ScanResult.OVER_LIMIT -> Color(0xFFF57F17)
    ScanResult.AUX -> Color(0xFF1976D2)
    else -> Color.Gray
}

/** 结果图标（Material 内置矢量图标，不依赖设备字体/表情符号） */
private fun resultIcon(result: ScanResult): ImageVector = when (result) {
    ScanResult.OK -> Icons.Filled.CheckCircle
    ScanResult.DUPLICATE -> Icons.Filled.Refresh
    ScanResult.SERIAL_MISMATCH -> Icons.Filled.Warning
    ScanResult.OVER_LIMIT -> Icons.Filled.AddCircle
    ScanResult.AUX -> Icons.Filled.Info
    else -> Icons.Filled.Warning
}

/** 结果文字（无障碍朗读 + 报警三重编码的「文字」通道） */
private fun resultLabel(result: ScanResult): String = when (result) {
    ScanResult.OK -> "正常"
    ScanResult.SERIAL_MISMATCH -> "串号"
    ScanResult.DUPLICATE -> "重复"
    ScanResult.OVER_LIMIT -> "超量"
    ScanResult.AUX -> "辅助"
    else -> "提示"
}

/** 结果图标（tint = 结果色；contentDescription = 结果文字，读屏可听） */
@Composable
private fun ResultIcon(result: ScanResult, sizeDp: Int) {
    Icon(
        imageVector = resultIcon(result),
        contentDescription = resultLabel(result),
        tint = resultColor(result),
        modifier = Modifier.size(sizeDp.dp)
    )
}
