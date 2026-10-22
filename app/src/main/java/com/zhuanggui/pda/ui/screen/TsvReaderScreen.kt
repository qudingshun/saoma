package com.zhuanggui.pda.ui.screen

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.zhuanggui.pda.export.FileExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * TSV 记录阅读器（v1.10.2）
 *
 * 不依赖电脑、不依赖 Excel：PDA 上直接读 /sdcard/zhuanggui 下的原始 TSV。
 *  - 按「日期 → 柜号文件」浏览，显示条数/大小/修改时间
 *  - 打开文件按行显示（最新在上），支持柜内过滤
 *  - 顶部搜索框跨文件搜条码（含回收站），点结果直接跳记录
 *
 * 说明：读者看到的是原始落盘文件，和 Excel/记事本打开的内容完全一致，
 * 用来核对「App 里看到的记录」与「文件里实际写的记录」是否一致。
 */
@Composable
fun TsvReaderScreen(onBack: () -> Unit) {
    val appRoot = remember { File(Environment.getExternalStorageDirectory(), "zhuanggui") }
    var mode by remember { mutableStateOf(0) }          // 0=装柜记录 1=回收站
    var files by remember { mutableStateOf<List<TsvFileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TsvHit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<TsvDetail?>(null) }
    var detailQuery by remember { mutableStateOf("") }

    val root = remember(mode) {
        File(appRoot, if (mode == 0) "装柜记录" else "回收站")
    }

    // 文件列表
    LaunchedEffect(mode) {
        loading = true
        hits = null
        detail = null
        query = ""
        files = withContext(Dispatchers.IO) { listTsvFiles(root) }
        loading = false
    }

    // 跨文件搜索（输入停顿 400ms 后执行；<2 字符不搜）
    LaunchedEffect(query, mode) {
        val q = query.trim()
        if (q.length < 2) {
            hits = null
            return@LaunchedEffect
        }
        delay(400)
        searching = true
        hits = withContext(Dispatchers.IO) { searchAll(root, q, 300) }
        searching = false
    }

    // 系统返回键：详情 → 列表
    androidx.activity.compose.BackHandler(enabled = detail != null) { detail = null }

    if (detail != null) {
        TsvDetail(
            detail = detail!!,
            query = detailQuery,
            onQuery = { detailQuery = it },
            onBack = { detail = null }
        )
    } else {
        Scaffold { pad ->
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("记录阅读器", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = onBack,
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(36.dp)
                    ) { Text("返回", fontSize = 13.sp) }
                }

                Text(
                    "直接读原始 TSV：/sdcard/zhuanggui/${if (mode == 0) "装柜记录" else "回收站"}/",
                    fontSize = 10.sp, color = Color.Gray,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == 0,
                        onClick = { mode = 0 },
                        label = { Text("装柜记录", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = mode == 1,
                        onClick = { mode = 1 },
                        label = { Text("回收站", fontSize = 12.sp) }
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜条码 / 柜号 / 日期（跨文件）", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )

                when {
                    searching -> Text("搜索中…", fontSize = 12.sp, color = Color.Gray)

                    hits != null -> {
                        val list = hits!!
                        Text(
                            if (list.isEmpty()) "没有匹配的记录" else "找到 ${list.size} 条（最新在上，点一条看所在文件）",
                            fontSize = 12.sp, color = Color.Gray
                        )
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            items(list, key = { "${it.file.absolutePath}#${it.index}" }) { hit ->
                                Card(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clickable {
                                            detailQuery = query.trim()
                                            detail = TsvDetail(
                                                file = hit.file,
                                                day = hit.day,
                                                container = hit.container,
                                                lines = parseTsv(hit.file)
                                            )
                                        }
                                ) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(hit.day, fontSize = 11.sp, color = Color.Gray)
                                            Text(
                                                if (hit.result.isBlank()) hit.time
                                                else "${hit.result}  ${hit.time}",
                                                fontSize = 11.sp,
                                                color = if (hit.result.isBlank()) Color.Gray
                                                else resultColor(hit.result)
                                            )
                                        }
                                        Text(
                                            hit.barcode,
                                            fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "柜号 ${hit.container} · ${hit.file.name}",
                                            fontSize = 10.sp, color = Color.Gray,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    loading -> Text("读取中…", fontSize = 13.sp, color = Color.Gray)

                    else -> {
                        val filteredFiles = if (query.isBlank()) files else files.filter {
                            it.container.contains(query.trim(), true) || it.day.contains(query.trim())
                        }
                        Text(
                            "共 ${filteredFiles.size} 个文件、${filteredFiles.sumOf { it.rows }} 条记录",
                            fontSize = 12.sp, color = Color.Gray
                        )
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            val byDay = filteredFiles.groupBy { it.day }
                            byDay.forEach { (day, dayFiles) ->
                                item(key = "h-$day") {
                                    Text(
                                        day, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                    )
                                }
                                items(dayFiles, key = { it.file.absolutePath }) { f ->
                                    Card(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                            .clickable {
                                                detailQuery = ""
                                                detail = TsvDetail(
                                                    file = f.file, day = f.day,
                                                    container = f.container, lines = parseTsv(f.file)
                                                )
                                            }
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    f.container,
                                                    fontSize = 17.sp, fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    "${f.rows} 条 · ${formatSize(f.size)} · 修改 ${formatTime(f.modified)}",
                                                    fontSize = 11.sp, color = Color.Gray
                                                )
                                            }
                                            Text("查看 ›", fontSize = 13.sp, color = Color(0xFF1976D2))
                                        }
                                    }
                                }
                            }
                            if (filteredFiles.isEmpty()) {
                                item {
                                    Text(
                                        "暂无 ${if (mode == 0) "装柜记录" else "回收站记录"} 文件",
                                        fontSize = 13.sp, color = Color.Gray,
                                        modifier = Modifier.padding(top = 12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 详情：单个 TSV 文件 ====================

@Composable
private fun TsvDetail(
    detail: TsvDetail,
    query: String,
    onQuery: (String) -> Unit,
    onBack: () -> Unit
) {
    val shown = remember(detail.lines, query) {
        val q = query.trim()
        if (q.isBlank()) detail.lines
        else detail.lines.filter {
            it.barcode.contains(q, true) || it.container.contains(q, true) ||
                it.result.contains(q, true) || it.time.contains(q, true) ||
                it.operator.contains(q, true)
        }
    }
    // 文件按时间顺序落盘，阅读时最新在上
    val newestFirst = remember(shown) { shown.asReversed() }

    Scaffold { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        detail.container,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${detail.day} · 共 ${detail.lines.size} 条 / 显示 ${shown.size} 条（最新在上）",
                        fontSize = 11.sp, color = Color.Gray
                    )
                }
                OutlinedButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(36.dp)
                ) { Text("文件列表", fontSize = 12.sp) }
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                label = { Text("柜内过滤：条码 / 时间 / 柜号 / 操作员", fontSize = 12.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                detail.file.absolutePath,
                fontSize = 9.sp, color = Color.Gray,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )

            HorizontalDivider()

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(newestFirst, key = { "${it.index}" }) { line ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (line.result.isNotBlank()) {
                                Text(
                                    line.result,
                                    fontSize = 10.sp,
                                    color = Color.White,
                                    modifier = Modifier
                                        .background(resultColor(line.result), RoundedCornerShape(3.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                            Text(
                                line.time,
                                fontSize = 10.sp, color = Color.Gray,
                                modifier = Modifier.padding(start = if (line.result.isBlank()) 0.dp else 6.dp)
                            )
                            if (line.operator.isNotBlank()) {
                                Text(
                                    "操作员 ${line.operator}",
                                    fontSize = 10.sp, color = Color.Gray,
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                        }
                        Text(
                            line.barcode,
                            fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        if (line.fullCode.isNotBlank()) {
                            Text(
                                "完整码：${line.fullCode}",
                                fontSize = 10.sp, color = Color.Gray,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        HorizontalDivider(Modifier.padding(top = 4.dp), thickness = 0.5.dp)
                    }
                }
                if (newestFirst.isEmpty()) {
                    item {
                        Text(
                            "无匹配记录", fontSize = 13.sp, color = Color.Gray,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

// ==================== 数据与工具 ====================

private data class TsvFileItem(
    val file: File,
    val day: String,
    val container: String,
    val rows: Int,
    val size: Long,
    val modified: Long
)

private data class TsvLine(
    val index: Int,
    val time: String,
    val container: String,
    val barcode: String,
    val fullCode: String,
    val result: String,
    val operator: String
)

private data class TsvDetail(
    val file: File,
    val day: String,
    val container: String,
    val lines: List<TsvLine>
)

private data class TsvHit(
    val file: File,
    val day: String,
    val container: String,
    val index: Int,
    val time: String,
    val barcode: String,
    val result: String
)

private fun listTsvFiles(root: File): List<TsvFileItem> {
    if (!root.exists()) return emptyList()
    val out = ArrayList<TsvFileItem>()
    root.listFiles { f -> f.isDirectory }?.forEach { dayDir ->
        dayDir.listFiles { f -> f.isFile && f.extension.equals("tsv", true) }?.forEach { f ->
            val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            val rows = max(0, lines.count { it.isNotBlank() } - 1)
            out.add(
                TsvFileItem(
                    file = f,
                    day = dayDir.name,
                    container = f.nameWithoutExtension,
                    rows = rows,
                    size = f.length(),
                    modified = f.lastModified()
                )
            )
        }
    }
    return out.sortedWith(compareByDescending<TsvFileItem> { it.day }.thenBy { it.container })
}

/**
 * 解析本 App 的记录 TSV。
 * v1.10.4 起为四列（时间/柜号/条码/操作员）；同时兼容旧版六列
 * （时间/柜号/条码/完整码/结果/操作员）和更早的五列。
 * 按表头名称取列，不能再用固定下标——四列时第 4 列是操作员，不是完整码。
 */
private fun parseTsv(file: File): List<TsvLine> {
    val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
    if (lines.isEmpty()) return emptyList()

    val header = lines.first().removePrefix("\uFEFF").split('\t').map { it.trim() }
    val hasHeader = header.any { it == "时间" || it == "条码" || it == "柜号" }
    val dataStart = if (hasHeader) 1 else 0
    fun column(name: String, fallback: Int): Int {
        val i = header.indexOfFirst { it == name }
        return if (i >= 0) i else fallback
    }

    val timeIndex = column("时间", 0)
    val containerIndex = column("柜号", 1)
    val barcodeIndex = column("条码", 2)
    val fullCodeIndex = column("完整码", -1)
    val resultIndex = column("结果", -1)
    val operatorIndex = column("操作员", if (hasHeader) -1 else 5)

    val out = ArrayList<TsvLine>(lines.size)
    lines.forEachIndexed { idx, raw ->
        if (idx < dataStart) return@forEachIndexed
        val line = raw.removePrefix("\uFEFF")
        if (line.isBlank()) return@forEachIndexed
        val c = line.split('\t')
        fun cell(index: Int): String = if (index in c.indices) c[index] else ""
        out.add(
            TsvLine(
                index = idx,
                time = cell(timeIndex),
                container = cell(containerIndex),
                barcode = cell(barcodeIndex),
                fullCode = cell(fullCodeIndex),
                result = cell(resultIndex),
                operator = cell(operatorIndex)
            )
        )
    }
    return out
}

private fun searchAll(root: File, query: String, limit: Int): List<TsvHit> {
    val out = ArrayList<TsvHit>()
    if (!root.exists()) return out
    val dayDirs = root.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: return out
    for (dayDir in dayDirs) {
        val fs = dayDir.listFiles { f -> f.isFile && f.extension.equals("tsv", true) }
            ?.sortedBy { it.name } ?: continue
        for (f in fs) {
            for (line in parseTsv(f)) {
                if (out.size >= limit) return out
                if (line.barcode.contains(query, true) || line.container.contains(query, true)) {
                    out.add(
                        TsvHit(
                            file = f, day = dayDir.name, container = f.nameWithoutExtension,
                            index = line.index,
                            time = line.time,
                            barcode = line.barcode,
                            result = line.result
                        )
                    )
                }
            }
        }
    }
    return out.asReversed()
}

private fun resultColor(result: String): Color = when (result) {
    "正常" -> Color(0xFF388E3C)
    "重复" -> Color(0xFFD32F2F)
    "串号" -> Color(0xFFD32F2F)
    "超量" -> Color(0xFFF57F17)
    "辅助码", "辅助" -> Color(0xFF1976D2)
    else -> Color(0xFF616161)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))
