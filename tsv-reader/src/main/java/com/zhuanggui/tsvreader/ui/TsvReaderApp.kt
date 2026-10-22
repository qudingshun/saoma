package com.zhuanggui.tsvreader.ui

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhuanggui.tsvreader.BuildConfig
import com.zhuanggui.tsvreader.TsvDocument
import com.zhuanggui.tsvreader.TsvReaderViewModel

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF0D3C78),
    secondary = Color(0xFF00695C),
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    error = Color(0xFFC62828)
)

private val HeaderBlue = Color(0xFF0D3C78)
private val Gray = Color(0xFF6B7280)

@Composable
fun TsvReaderApp(
    viewModel: TsvReaderViewModel,
    onOpenFile: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val document = state.document
    val error = state.error

    MaterialTheme(colorScheme = LightColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                ReaderHeader(state.fileName, onOpenFile)

                when {
                    state.loading -> LoadingState()
                    error != null -> ErrorState(error, onOpenFile)
                    document == null -> EmptyState(onOpenFile)
                    else -> DocumentContent(
                        document = document,
                        query = state.query,
                        onQueryChange = viewModel::onQueryChange
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderHeader(fileName: String, onOpenFile: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(HeaderBlue)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "TSV 阅读器",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            if (fileName.isNotBlank()) {
                Text(
                    fileName,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        TextButton(onClick = onOpenFile) {
            Text("打开文件", color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在读取 TSV…", fontSize = 13.sp, color = Gray)
        }
    }
}

@Composable
private fun ErrorState(message: String, onOpenFile: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "无法打开文件",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Gray,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpenFile) { Text("选择其他文件") }
            Spacer(Modifier.height(10.dp))
            Text(
                "提示：也可以退出后，在文件管理器里长按 TSV 文件 → 打开方式 → TSV 阅读器。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun EmptyState(onOpenFile: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "TSV",
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 2.sp
            )
            Text("成品出库 · 独立阅读器", fontSize = 12.sp, color = Gray)
            Spacer(Modifier.height(24.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("使用方法", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "1. 在文件管理器中找到 .tsv 文件（例如 /sdcard/zhuanggui/装柜记录/…）\n" +
                            "2. 点该文件，选择「打开方式 → TSV 阅读器」\n" +
                            "3. 也可以直接点下方按钮，在 App 内选择文件",
                        fontSize = 12.5.sp,
                        lineHeight = 20.sp,
                        color = Color(0xFF374151)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onOpenFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("选择 TSV 文件", fontSize = 15.sp)
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "支持 UTF-8（含 BOM）、GBK/GB18030；第一行作为表头显示。\n文件只在本机读取，不会上传。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Gray,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Text("v${BuildConfig.VERSION_NAME}", fontSize = 10.sp, color = Gray)
        }
    }
}

@Composable
private fun DocumentContent(
    document: TsvDocument,
    query: String,
    onQueryChange: (String) -> Unit
) {
    val filteredRows = remember(document, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            document.rows
        } else {
            document.rows.filter { row ->
                row.any { cell -> cell.contains(keyword, ignoreCase = true) }
            }
        }
    }
    val knownColumns = remember(document) { KnownColumns.from(document) }
    val listState = rememberLazyListState()
    var selectedRow by remember(document) { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(query) {
        if (filteredRows.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "共 ${document.rows.size} 条 · ${document.header.size} 列",
                fontSize = 11.5.sp,
                color = Gray
            )
            if (query.isNotBlank()) {
                Text(
                    "匹配 ${filteredRows.size} 条",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            shape = RoundedCornerShape(10.dp),
            placeholder = {
                Text("搜索条码 / 柜号 / 任意内容", fontSize = 13.sp, color = Gray)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { onQueryChange("") }) {
                        Text("清除", fontSize = 12.sp)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )

        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = Color(0xFFE5E7EB))

        if (filteredRows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有匹配的记录", fontSize = 14.sp, color = Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(filteredRows, key = { index, _ -> index }) { index, row ->
                    if (knownColumns != null) {
                        KnownRecordCard(
                            displayIndex = index + 1,
                            row = row,
                            columns = knownColumns,
                            onClick = { selectedRow = row }
                        )
                    } else {
                        GenericRecordCard(
                            displayIndex = index + 1,
                            header = document.header,
                            row = row,
                            onClick = { selectedRow = row }
                        )
                    }
                }
            }
        }
    }

    selectedRow?.let { row ->
        RecordDetailDialog(
            document = document,
            row = row,
            barcodeIndex = knownColumns?.barcode,
            onDismiss = { selectedRow = null }
        )
    }
}

@Composable
private fun KnownRecordCard(
    displayIndex: Int,
    row: List<String>,
    columns: KnownColumns,
    onClick: () -> Unit
) {
    val barcode = row.value(columns.barcode)
    val result = if (columns.result >= 0) row.value(columns.result) else ""
    val time = row.value(columns.time)
    val container = row.value(columns.container)
    val operator = row.value(columns.operator)

    RecordCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                barcode.ifBlank { "（无条码）" },
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (result.isNotBlank()) {
                ResultBadge(result)
            }
        }
        Row(
            Modifier.padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("#$displayIndex", fontSize = 10.sp, color = Gray)
            if (time.isNotBlank()) Text("  $time", fontSize = 10.sp, color = Gray)
            if (container.isNotBlank()) {
                Text("  $container", fontSize = 10.sp, color = Gray, maxLines = 1)
            }
        }
        if (operator.isNotBlank()) {
            Text(
                "操作员：$operator",
                fontSize = 10.sp,
                color = Gray,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun GenericRecordCard(
    displayIndex: Int,
    header: List<String>,
    row: List<String>,
    onClick: () -> Unit
) {
    val title = row.firstOrNull { it.isNotBlank() } ?: "（空行）"
    RecordCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#$displayIndex", fontSize = 10.sp, color = Gray)
            Spacer(Modifier.width(6.dp))
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        for (i in 1 until header.size) {
            if (i > 5) break
            val value = row.value(i)
            if (value.isBlank()) continue
            Text(
                "${header.getOrNull(i).orEmpty().ifBlank { "列${i + 1}" }}：$value",
                fontSize = 11.sp,
                color = Color(0xFF374151),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun RecordCard(onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            content()
        }
    }
}

@Composable
private fun ResultBadge(result: String) {
    val color = when {
        result.contains("正常") -> Color(0xFF2E7D32)
        result.contains("重复") || result.contains("串号") -> Color(0xFFC62828)
        result.contains("超量") -> Color(0xFFEF6C00)
        else -> Color(0xFF1565C0)
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            result.ifBlank { "—" },
            color = color,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private data class KnownColumns(
    val time: Int,
    val container: Int,
    val barcode: Int,
    /** 旧版六列文件有「结果」；v1.10.4 四列文件没有，显示时按 -1 跳过 */
    val result: Int,
    val operator: Int
) {
    companion object {
        fun from(document: TsvDocument): KnownColumns? {
            val barcode = document.columnIndex("条码")
            if (barcode < 0) return null
            return KnownColumns(
                time = document.columnIndex("时间"),
                container = document.columnIndex("柜号"),
                barcode = barcode,
                result = document.columnIndex("结果"),
                operator = document.columnIndex("操作员")
            )
        }
    }
}

@Composable
private fun RecordDetailDialog(
    document: TsvDocument,
    row: List<String>,
    barcodeIndex: Int?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val rowText = document.header.indices.joinToString("\t") { row.value(it) }
    val barcode = barcodeIndex?.let { row.value(it) }.orEmpty()

    fun copy(text: String, tip: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录详情", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                for (i in document.header.indices) {
                    Text(
                        document.header.getOrNull(i).orEmpty().ifBlank { "列${i + 1}" },
                        fontSize = 10.5.sp,
                        color = Gray
                    )
                    SelectionContainer {
                        Text(
                            row.value(i).ifBlank { "—" },
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (barcode.isNotBlank()) {
                    TextButton(onClick = { copy(barcode, "已复制条码") }) {
                        Text("复制条码", fontSize = 13.sp)
                    }
                }
                TextButton(onClick = { copy(rowText, "已复制整行") }) {
                    Text("复制整行", fontSize = 13.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", fontSize = 13.sp)
            }
        }
    )
}

private fun List<String>.value(index: Int): String =
    if (index in indices) this[index] else ""
