package com.zhuanggui.pda.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanggui.pda.data.entity.ScanRecord
import com.zhuanggui.pda.data.entity.ScanResult
import com.zhuanggui.pda.ui.OutboundScanState

/**
 * 出库扫码界面（竖屏）：
 *  - 顶部：返回设置（可追加第二条规则）/ 触发扫描 / 保存状态
 *  - 柜号 + 进度计数
 *  - 最新输出：柜号，条码（逗号拼接）
 *  - 手动录入框（自动聚焦，HID 扫码枪直接打字 + 回车）
 *  - 本柜记录明细（最近 100 条，自动滚动）
 */
@Composable
fun OutboundScanScreen(
    state: OutboundScanState,
    records: List<ScanRecord>,
    onBackToSetup: () -> Unit,
    onManual: (String) -> Unit,
    onTrigger: () -> Unit
) {

    Scaffold { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .background(state.screenColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // ---------- 顶部工具条 ----------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("出库扫码", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onTrigger) { Text("扫描", fontSize = 14.sp) }
                    OutlinedButton(onClick = onBackToSetup) { Text("返回设置", fontSize = 14.sp) }
                }
            }

            // ---------- 柜号 & 规则 ----------
            Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "柜号：${state.activeContainer?.containerNo ?: "—"}",
                        fontSize = 24.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "规则：${state.rules.joinToString(" / ").ifBlank { "—" }}",
                        fontSize = 14.sp, color = Color.Gray
                    )
                    val totalText = if (state.total > 0) state.total.toString() else "不限"
                    Text(
                        "进度：${state.okCount} / $totalText    累计记录：${state.totalRecords}",
                        fontSize = 17.sp
                    )
                    // 保存状态提示
                    Text(
                        if (state.lastFullCode.isBlank()) "每扫一码自动保存到 CSV"
                        else if (state.fileSaved) "✓ 已自动保存（/sdcard/ZhuangGui/装柜记录/）"
                        else "⚠ 最近一条未写入文件，请检查存储权限",
                        fontSize = 13.sp,
                        color = if (state.lastFullCode.isNotBlank() && !state.fileSaved)
                            Color(0xFFD32F2F) else Color(0xFF388E3C)
                    )
                }
            }

            // ---------- 计数卡 ----------
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutCount("正常", state.okCount, Color(0xFF388E3C), Modifier.weight(1f))
                OutCount("串号", state.mismatchCount, Color(0xFFD32F2F), Modifier.weight(1f))
                OutCount("重复", state.duplicateCount, Color(0xFFD32F2F), Modifier.weight(1f))
                OutCount("超量", state.overCount, Color(0xFFF57F17), Modifier.weight(1f))
            }

            // ---------- 最新输出：柜号，条码 ----------
            Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text("输出：", fontSize = 13.sp, color = Color.Gray)
                    Text(
                        state.lastFullCode.ifBlank { "—" },
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(state.lastMessage, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
            }

            // ---------- 扫码录入（HID 扫码枪 / 手动） ----------
            com.zhuanggui.pda.ui.HidScanField(
                onSubmit = { onManual(it) },
                modifier = Modifier.padding(top = 6.dp)
            )

            // ---------- 记录明细 ----------
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("本柜记录", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${records.size} 条", fontSize = 13.sp, color = Color.Gray)
            }
            HorizontalDivider()

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(records, key = { it.id }) { r -> RecordRow(r) }
            }
        }
    }
}

@Composable
private fun OutCount(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(count.toString(), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RecordRow(r: ScanRecord) {
    val (tag, tagColor) = when (r.result) {
        ScanResult.OK -> "正常" to Color(0xFF388E3C)
        ScanResult.SERIAL_MISMATCH -> "串号" to Color(0xFFD32F2F)
        ScanResult.DUPLICATE -> "重复" to Color(0xFFD32F2F)
        ScanResult.OVER_LIMIT -> "超量" to Color(0xFFF57F17)
        else -> r.result.name to Color.Gray
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            tag,
            fontSize = 13.sp,
            color = Color.White,
            modifier = Modifier
                .background(tagColor)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Spacer(Modifier.height(0.dp))
        Text(
            "  ${r.fullCode}",
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}
