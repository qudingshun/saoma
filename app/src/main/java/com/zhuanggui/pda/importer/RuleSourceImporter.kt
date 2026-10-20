package com.zhuanggui.pda.importer

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStream

/**
 * 规则源导入（v1.7.5：支持 XLSX「型号名称 / 前缀 / 四个开关」两列顺序自动识别）。
 *
 * 约定：
 *  - 首选列顺序（导出格式）：型号名称、前缀、可重复扫、计入统计、查历史串号、忽略
 *  - 兼容旧格式：前缀、名称（可选）、四个开关（可选）
 *  - 只有型号名称、没有前缀的行 = 建一个空型号（后续在 App 里逐个添加规则）
 *  - 首行表头（型号名称/前缀/条码/barcode…）自动跳过；前缀去空格转大写
 *  - 开关支持 1/0、是/否、true/false、√/×
 *
 * 解析对规则表这类小文件足够安全；调用方需在 Dispatchers.IO 执行。
 */
object RuleSourceImporter {

    /**
     * 一条导入规则：前缀 + 名称 + 四个开关（v1.7.4）。
     * [prefix] 为空 = 仅建型号（文件里有型号名称但没有前缀）。
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

    /** 常见表头词（仅 ASCII；中文表头用 isHeaderText 判断） */
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
                else -> parseText(stream) // .csv / .txt / .tsv / 其他按纯文本尝试
            }
        }
    }

    internal fun parseExcel(input: InputStream): List<ParsedRule> {
        val out = LinkedHashMap<String, ParsedRule>()       // key = prefix
        val models = LinkedHashMap<String, ParsedRule>()    // key = 型号名称（空规则）
        val fmt = DataFormatter()
        WorkbookFactory.create(input).use { wb ->
            if (wb.numberOfSheets <= 0) return emptyList()
            val sheet = wb.getSheetAt(0)
            var nameFirst = false
            var first = true
            for (row in sheet) {
                val c0 = cell(row, 0, fmt)
                val c1 = cell(row, 1, fmt)
                if (first) {
                    first = false
                    val h0 = c0?.trim().orEmpty()
                    val h1 = c1?.trim().orEmpty()
                    if (isHeaderText(h0) || isHeaderText(h1)) {
                        // 表头：「型号名称, 前缀」= 新导出格式；否则按旧格式（前缀, 名称）
                        nameFirst = (h0.contains("型号") || h0.contains("名称")) && h1.contains("前缀")
                        continue
                    }
                    // 无表头：首行按旧格式（前缀在前）继续处理
                }
                val nameRaw = if (nameFirst) c0 else c1
                val prefixRaw = if (nameFirst) c1 else c0
                val name = nameRaw?.trim().orEmpty()
                val prefix = cleanPrefix(prefixRaw)
                if (prefix == null) {
                    // 允许「只有型号、没有前缀」的行（建空型号）
                    if (name.isNotBlank()) {
                        models.putIfAbsent(name, ParsedRule(prefix = "", name = name))
                    }
                    continue
                }
                val flags = arrayOf(cell(row, 2, fmt), cell(row, 3, fmt), cell(row, 4, fmt), cell(row, 5, fmt))
                out.putIfAbsent(prefix, ruleOf(prefix, name, flags[0], flags[1], flags[2], flags[3]))
            }
        }
        return out.values.toList() + models.values.toList()
    }

    private fun parseText(input: InputStream): List<ParsedRule> {
        val text = input.readBytes().toString(Charsets.UTF_8)
        return parseTextContent(text)
    }

    /** 纯文本解析（旧 TSV/CSV/TXT；单测直接调用）。前缀在第一列。 */
    internal fun parseTextContent(text: String): List<ParsedRule> {
        val out = LinkedHashMap<String, ParsedRule>()
        var firstRow = true
        text.removePrefix("\uFEFF").lineSequence().forEach { line ->
            val raw = line.trim()
            if (raw.isEmpty()) return@forEach
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

    private fun cell(row: Row, idx: Int, fmt: DataFormatter): String? =
        row.getCell(idx)?.let { fmt.formatCellValue(it) }

    private fun isHeaderText(s: String): Boolean {
        val t = s.trim().lowercase()
        if (t.isEmpty()) return false
        return t in HEADER_WORDS ||
            t.contains("前缀") || t.contains("型号") || t.contains("名称") ||
            t.contains("条码") || t.contains("可重复")
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
