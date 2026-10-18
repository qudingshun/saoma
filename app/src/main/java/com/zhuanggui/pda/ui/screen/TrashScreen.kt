package com.zhuanggui.pda.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanggui.pda.data.entity.ScanRecord
import com.zhuanggui.pda.data.entity.ScanResult
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 回收站（隐藏入口：扫码记录页「长按标题」进入）：
 *  - 扫码页删除、记录页分组删除的记录都暂存在这里（软删除，不真删）
 *  - 支持逐条「撤回」/「全部撤回」恢复；撤回后重新计入本柜统计
 *  - 「清空回收站」为永久删除（三次确认）
 */
@Composable
fun TrashScreen(
    records: List<ScanRecord>,
    onRestore: (Long) -> Unit,
    onRestoreAll: () -> Unit,
    onEmpty: () -> Unit,
    onBack: () -> Unit
) {
    var confirmEmpty by remember { mutableStateOf(false) }

    if (confirmEmpty) {
        TripleConfirmDialog(
            title = "清空回收站",
            steps = listOf(
                "将永久删除回收站内全部 ${records.size} 条记录及其文件。",
                "再次确认：永久删除后无法再撤回。",
                "最后确认：确定清空回收站吗？"
            ),
            onConfirmed = {
                onEmpty()
                confirmEmpty = false
            },
            onDismiss = { confirmEmpty = false }
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
                Text("回收站", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onRestoreAll, enabled = records.isNotEmpty()) {
                        Text("全部撤回", fontSize = 14.sp)
                    }
                    OutlinedButton(onClick = onBack) { Text("返回", fontSize = 14.sp) }
                }
            }

            Text(
                "删除的记录暂存在这里，可逐条「撤回」恢复（计数与落盘文件一并恢复）",
                fontSize = 12.sp, color = Color.Gray
            )
            HorizontalDivider(Modifier.padding(top = 4.dp))

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(records, key = { it.id }) { r ->
                    TrashRow(r = r, onRestore = { onRestore(r.id) })
                }
                if (records.isEmpty()) {
                    item {
                        Text(
                            "回收站是空的",
                            fontSize = 14.sp, color = Color.Gray,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }

            HorizontalDivider()
            Button(
                onClick = { confirmEmpty = true },
                enabled = records.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Text("清空回收站（永久删除，三次确认）", fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun TrashRow(r: ScanRecord, onRestore: () -> Unit) {
    val (tag, tagColor) = when (r.result) {
        ScanResult.OK -> "正常" to Color(0xFF388E3C)
        ScanResult.SERIAL_MISMATCH -> "串号" to Color(0xFFD32F2F)
        ScanResult.DUPLICATE -> "重复" to Color(0xFFD32F2F)
        ScanResult.OVER_LIMIT -> "超量" to Color(0xFFF57F17)
        ScanResult.AUX -> "辅助" to Color(0xFF1976D2)
        else -> r.result.name to Color.Gray
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
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
                "删除于 ${deletedText(r)} · 柜号：${r.containerNo}",
                fontSize = 12.sp, color = Color.Gray
            )
        }
        TextButton(onClick = onRestore) { Text("撤回", fontSize = 13.sp) }
    }
    HorizontalDivider(thickness = 0.5.dp)
}

private val trashFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

private fun deletedText(r: ScanRecord): String =
    r.deletedAt?.let { runCatching { trashFmt.format(it) }.getOrDefault("") }.orEmpty()
