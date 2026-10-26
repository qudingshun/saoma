package com.zhuanggui.pda.importer

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.nio.charset.Charset

/**
 * 规则库导入（v1.12.1：恢复 TSV / TXT 文本格式，不再使用 XLSX/POI）。
 *
 * 约定：
 *  - 首选列顺序（App 导出格式）：型号名称、前缀、可重复扫、计入统计、查历史串号、忽略、默认装柜数量
 *  - 兼容旧格式：前缀、名称（可选）、四个开关（可选；无默认数量列）
 *  - 只有型号名称、没有前缀的行 = 建一个空型号（后续在 App 里逐个添加规则）
 *  - 首行表头（型号名称/前缀/条码/barcode…）自动跳过；前缀去空格转大写
 *  - 开关支持 1/0、是/否、true/false、√/×
 *  - 支持 Tab / 逗号 / 分号分隔；UTF-8（带 BOM 也可），检测到乱码时按 GBK 再试一次
 *
 * 说明：XLSX 解析依赖 Apache POI，release 包经 R8 后现场机出现过
 * `DataFormatter` 静态初始化崩溃；因此 v1.12.1 起导入只接受 TSV/TXT/CSV。
 */
object RuleSourceImporter {

    /**
     * 一条导入规则：前缀 + 名称 + 四个开关（v1.7.4）。
     * [prefix] 为空 = 仅建型号（文件里有型号名称但没有前缀）。
     * [hasFlags] = 源文件是否带了第 3~6 列开关；没带时覆盖导入应保留库内原开关。
     * [defaultQuantity] / [hasDefaultQuantity] = 第 7 列默认装柜数量；旧文件没有该列时保留库内原值。
     */
    data class ParsedRule(
        val prefix: String,
        val name: String,
        val allowDuplicate: Boolean = false,
        val countEnabled: Boolean = true,
        val checkHistory: Boolean = false,
        val ignoreRepeat: Boolean = false,
        val hasFlags: Boolean = false,
        val defaultQuantity: Int = 0,
        val hasDefaultQuantity: Boolean = false
    )

    /** 常见表头词（仅 ASCII；中文表头用 contains 判断） */
    private val HEADER_WORDS = setOf(
        "barcode", "bardcode", "prefix", "rule", "code", "sn", "sku", "ean", "upc"
    )

    /**
     * 前缀清洗：接受字母 + 数字，以及机型里真实出现的 '-'（如 HYW-T55）与 '#'（如 #9970）；
     * 统一大写；无效返回 null。v1.9.2：原只允许字母数字，会丢掉含 - # 的真实机型前缀。
     */
    fun cleanPrefix(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || !s.matches(Regex("[A-Za-z0-9#\\-]+"))) return null
        return s.uppercase()
    }

    /** 解析 url 指向的规则源文件；无法识别/损坏时抛异常，由调用方提示 */
    fun parse(context: Context, uri: Uri): List<ParsedRule> {
        val fileName = displayName(context, uri).lowercase()
        if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
            throw IllegalStateException("XLSX 导入已停用：请在 Excel/WPS 中另存为 TSV 或 TXT 后再导入")
        }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取所选文件")
        input.use { stream -> return parseText(stream) }
    }

    private fun parseText(input: InputStream): List<ParsedRule> {
        val bytes = input.readBytes()
        var text = bytes.toString(Charsets.UTF_8)
        // 现场文件可能是 GBK 另存的 TXT；UTF-8 出现替换符时再按 GBK 解一次
        if (text.contains('\uFFFD')) {
            runCatching { bytes.toString(Charset.forName("GBK")) }
                .getOrNull()
                ?.takeIf { !it.contains('\uFFFD') }
                ?.let { text = it }
        }
        return parseTextContent(text)
    }

    /** 纯文本解析（单测直接调用）：Tab / 逗号 / 分号均可，自动识别表头列顺序。 */
    internal fun parseTextContent(text: String): List<ParsedRule> {
        val out = LinkedHashMap<String, ParsedRule>()       // key = prefix + name（同前缀可跨型号）
        val models = LinkedHashMap<String, ParsedRule>()    // key = 型号名称（空规则）
        var firstRow = true
        var nameFirst = false

        text.removePrefix("\uFEFF").lineSequence().forEach { line ->
            val raw = line.trim()
            if (raw.isEmpty()) return@forEach
            val parts = splitLine(raw)
            if (firstRow) {
                firstRow = false
                if (isHeaderRow(parts)) {
                    val h0 = parts.getOrNull(0).orEmpty()
                    val h1 = parts.getOrNull(1).orEmpty()
                    nameFirst = (h0.contains("型号") || h0.contains("名称")) && h1.contains("前缀")
                    return@forEach
                }
            }
            val name = (if (nameFirst) parts.getOrNull(0) else parts.getOrNull(1))
                ?.trim().orEmpty()
            val prefixRaw = if (nameFirst) parts.getOrNull(1) else parts.getOrNull(0)
            val prefix = cleanPrefix(prefixRaw)
            val qty = parts.getOrNull(6)
            if (prefix == null) {
                // 只有前缀栏为空、型号非空 = 建空型号；前缀栏有非法字符则跳过
                if (prefixRaw.isNullOrBlank() && name.isNotBlank()) {
                    models.putIfAbsent(
                        name,
                        ParsedRule(
                            prefix = "",
                            name = name,
                            defaultQuantity = parseQuantity(qty) ?: 0,
                            hasDefaultQuantity = !qty.isNullOrBlank()
                        )
                    )
                }
                return@forEach
            }
            val flags = (2..5).map { parts.getOrNull(it) }
            out.putIfAbsent(
                ruleKey(prefix, name),
                ruleOf(prefix, name, flags[0], flags[1], flags[2], flags[3], qty)
            )
        }
        return out.values.toList() + models.values.toList()
    }

    private fun splitLine(raw: String): List<String> = when {
        '\t' in raw -> raw.split('\t')
        ',' in raw -> raw.split(',')
        ';' in raw -> raw.split(';')
        else -> listOf(raw)
    }

    private fun isHeaderRow(parts: List<String>): Boolean {
        val c0 = parts.getOrNull(0).orEmpty().trim()
        val c1 = parts.getOrNull(1).orEmpty().trim()
        val ascii = setOf(c0.lowercase(), c1.lowercase())
        return c0.contains("前缀") || c0.contains("条码") ||
            c1.contains("前缀") || c1.contains("条码") ||
            ascii.any { it in HEADER_WORDS }
    }

    /** v1.12.0：同前缀可以挂多个型号，导入去重键必须带型号名称 */
    private fun ruleKey(prefix: String, name: String): String = "$prefix\u0000$name"

    /**
     * 前缀 + 名称 + 可选四列开关（第 3~6 列）+ 可选默认装柜数量（第 7 列）。
     * 缺省 = 旧行为（不可重复 / 计入 / 不查 / 不忽略 / 不改默认数量）。
     */
    private fun ruleOf(
        prefix: String,
        name: String,
        f1: String?,
        f2: String?,
        f3: String?,
        f4: String?,
        qty: String? = null
    ): ParsedRule {
        val hasFlags = listOf(f1, f2, f3, f4).any { !it.isNullOrBlank() }
        val hasQty = !qty.isNullOrBlank()
        return ParsedRule(
            prefix = prefix,
            name = name,
            allowDuplicate = parseFlag(f1) ?: false,
            countEnabled = parseFlag(f2) ?: true,
            checkHistory = parseFlag(f3) ?: false,
            ignoreRepeat = parseFlag(f4) ?: false,
            hasFlags = hasFlags,
            defaultQuantity = parseQuantity(qty) ?: 0,
            hasDefaultQuantity = hasQty
        )
    }

    /** 默认装柜数量：只取数字，0~999999；空/非数字 = null（不改库内原值） */
    private fun parseQuantity(raw: String?): Int? {
        val s = raw?.trim()?.filter { it.isDigit() }.orEmpty()
        if (s.isEmpty()) return null
        return s.toIntOrNull()?.coerceIn(0, 999_999)
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
