package com.zhuanggui.pda.importer

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStream

/**
 * 规则源导入解析（.xlsx / .xls / .csv / .txt）。
 *
 * 约定：第一列 = 条码前缀（字母+数字），第二列（可选）= 名称/型号。
 *  - 首行若是表头（barcode/prefix/条码 等）自动跳过
 *  - 前缀统一去空格 + 转大写；同前缀去重（保留首个名称）
 *  - 该解析对规则表这类小文件足够安全；调用方需在 Dispatchers.IO 执行
 */
object RuleSourceImporter {

    data class ParsedRule(val prefix: String, val name: String)

    /** 常见表头词（仅 ASCII，中文表头天然过不了 cleanPrefix 校验） */
    private val HEADER_WORDS = setOf(
        "barcode", "bardcode", "prefix", "rule", "code", "sn", "sku", "ean", "upc"
    )

    /** 前缀清洗：仅接受字母+数字（整串），统一大写；无效返回 null */
    fun cleanPrefix(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || !s.matches(Regex("[A-Za-z0-9]+"))) return null
        return s.uppercase()
    }

    /** 解析 url 指向的规则源文件；无法识别/损坏时抛异常，由调用方提示 */
    fun parse(context: Context, uri: Uri): List<ParsedRule> {
        val fileName = displayName(context, uri).lowercase()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取所选文件")
        input.use { stream ->
            return when {
                fileName.endsWith(".xlsx") || fileName.endsWith(".xls") -> parseExcel(stream)
                else -> parseText(stream) // .csv / .txt / 其他按纯文本尝试
            }
        }
    }

    private fun parseExcel(input: InputStream): List<ParsedRule> {
        val out = LinkedHashMap<String, ParsedRule>()
        val fmt = DataFormatter()
        WorkbookFactory.create(input).use { wb ->
            if (wb.numberOfSheets <= 0) return emptyList()
            val sheet = wb.getSheetAt(0)
            var firstRow = true
            for (row in sheet) {
                val c0 = row.getCell(0)?.let { fmt.formatCellValue(it) }
                val c1 = row.getCell(1)?.let { fmt.formatCellValue(it) }?.trim().orEmpty()
                val prefix = cleanPrefix(c0) ?: continue
                if (firstRow) {
                    firstRow = false
                    if (prefix.lowercase() in HEADER_WORDS) continue
                }
                out.putIfAbsent(prefix, ParsedRule(prefix, c1))
            }
        }
        return out.values.toList()
    }

    private fun parseText(input: InputStream): List<ParsedRule> {
        val out = LinkedHashMap<String, ParsedRule>()
        val text = input.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")
        var firstRow = true
        text.lineSequence().forEach { line ->
            val raw = line.trim()
            if (raw.isEmpty()) return@forEach
            val parts = raw.split(',', '\t', ';', limit = 2)
            val prefix = cleanPrefix(parts[0]) ?: return@forEach
            if (firstRow) {
                firstRow = false
                if (prefix.lowercase() in HEADER_WORDS) return@forEach
            }
            val name = if (parts.size > 1) parts[1].trim() else ""
            out.putIfAbsent(prefix, ParsedRule(prefix, name))
        }
        return out.values.toList()
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment.orEmpty()
    }
}
