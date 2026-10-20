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

    /**
     * 一条导入规则：前缀 + 名称 + 四个开关（v1.7.4）。
     * [hasFlags] = 源文件是否带了第 3~6 列开关；没带时覆盖导入应保留库内原开关。
     */
    data class ParsedRule(
        val prefix: String,
        val name: String,
        val allowDuplicate: Boolean = false,
        val countEnabled: Boolean = true,
        val checkHistory: Boolean = false,
        val ignoreRepeat: Boolean = false,
        val hasFlags: Boolean = false
    )

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
                val c3 = row.getCell(2)?.let { fmt.formatCellValue(it) }
                val c4 = row.getCell(3)?.let { fmt.formatCellValue(it) }
                val c5 = row.getCell(4)?.let { fmt.formatCellValue(it) }
                val c6 = row.getCell(5)?.let { fmt.formatCellValue(it) }
                out.putIfAbsent(prefix, ruleOf(prefix, c1, c3, c4, c5, c6))
            }
        }
        return out.values.toList()
    }

    private fun parseText(input: InputStream): List<ParsedRule> {
        val text = input.readBytes().toString(Charsets.UTF_8)
        return parseTextContent(text)
    }

    /** 纯文本解析（导出文件 / CSV / TXT 共用；单测直接调用） */
    internal fun parseTextContent(text: String): List<ParsedRule> {
        val out = LinkedHashMap<String, ParsedRule>()
        var firstRow = true
        text.removePrefix("\uFEFF").lineSequence().forEach { line ->
            val raw = line.trim()
            if (raw.isEmpty()) return@forEach
            // 优先按制表符拆分（导出文件即 TSV）；否则兼容逗号/分号
            val parts = if ('\t' in raw) raw.split('\t') else raw.split(',', ';')
            val prefix = cleanPrefix(parts.getOrNull(0)) ?: return@forEach
            if (firstRow) {
                firstRow = false
                if (prefix.lowercase() in HEADER_WORDS) return@forEach
            }
            val name = parts.getOrNull(1)?.trim().orEmpty()
            out.putIfAbsent(
                prefix,
                ruleOf(prefix, name, parts.getOrNull(2), parts.getOrNull(3), parts.getOrNull(4), parts.getOrNull(5))
            )
        }
        return out.values.toList()
    }

    /** 前缀 + 名称 + 可选四列开关（第 3~6 列），缺省 = 旧行为（不可重复 / 计入 / 不查 / 不忽略） */
    private fun ruleOf(
        prefix: String,
        name: String,
        f1: String?,
        f2: String?,
        f3: String?,
        f4: String?
    ): ParsedRule {
        val hasFlags = listOf(f1, f2, f3, f4).any { !it.isNullOrBlank() }
        return ParsedRule(
            prefix = prefix,
            name = name,
            allowDuplicate = parseFlag(f1) ?: false,
            countEnabled = parseFlag(f2) ?: true,
            checkHistory = parseFlag(f3) ?: false,
            ignoreRepeat = parseFlag(f4) ?: false,
            hasFlags = hasFlags
        )
    }

    /** 1/0、是/否、true/false、√/× 等；空/无法识别 = null（按默认值） */
    private fun parseFlag(raw: String?): Boolean? {
        val s = raw?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        return when (s) {
            "1", "是", "true", "y", "yes", "√" -> true
            "0", "否", "false", "n", "no", "×", "-" -> false
            else -> null
        }
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
