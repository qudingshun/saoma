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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
 * 扫码记录查看页：全部记录（新→旧），支持按柜号/条码过滤。
 * 文件落盘在扫码时已自动完成，此页只读展示。
 */
@Composable
fun RecordsScreen(
    records: List<ScanRecord>,
    onBack: () -> Unit
) {
    var filter by remember { mutableStateOf("") }
    val shown = if (filter.isBlank()) records
    else records.filter {
        it.containerNo.contains(filter.trim(), true) ||
            it.barcode.contains(filter.trim(), true)
    }

    Scaffold { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("扫码记录", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onBack) { Text("返回", fontSize = 14.sp) }
            }

            Text(
                "自动保存路径：/sdcard/ZhuangGui/装柜记录/日期/柜号.csv",
                fontSize = 12.sp, color = Color.Gray
            )

            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("过滤：柜号或条码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("共 ${shown.size} 条", fontSize = 14.sp, color = Color.Gray)
                Text("新 → 旧", fontSize = 12.sp, color = Color.Gray)
            }
            HorizontalDivider()

            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(shown, key = { it.id }) { r ->
                    val (tag, tagColor) = when (r.result) {
                        ScanResult.OK -> "正常" to Color(0xFF388E3C)
                        ScanResult.SERIAL_MISMATCH -> "串号" to Color(0xFFD32F2F)
                        ScanResult.DUPLICATE -> "重复" to Color(0xFFD32F2F)
                        ScanResult.OVER_LIMIT -> "超量" to Color(0xFFF57F17)
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
                            "${timeText(r)}　柜号：${r.containerNo}",
                            fontSize = 12.sp, color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

private fun timeText(r: ScanRecord): String = runCatching { timeFmt.format(r.scannedAt) }.getOrDefault("")
