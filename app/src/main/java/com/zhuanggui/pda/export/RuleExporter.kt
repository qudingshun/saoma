package com.zhuanggui.pda.export

import com.zhuanggui.pda.data.entity.RuleLibEntry
import com.zhuanggui.pda.data.entity.RuleModel
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 规则库导出（v1.12.1：TSV 文本格式，Excel/WPS 可直接打开，不再使用 XLSX/POI）。
 *
 * 列：型号名称 / 前缀 / 可重复扫 / 计入统计 / 查历史串号 / 忽略（是/否）/ 默认装柜数量
 * - 以型号为组：先列型号，再列该型号下的前缀规则（每个前缀一行，可逐条维护）
 * - v16：默认装柜数量按型号只维护 1 个；导出时同一型号的每一行都写该型号的默认值
 * - 空型号也会导出一行（前缀为空），导入时用于建空型号
 * - 文件带 UTF-8 BOM + CRLF，Excel/WPS 双击打开中文不乱码
 *
 * 路径：/sdcard/zhuanggui/规则库/规则库_yyyyMMdd_HHmmss.tsv
 * 导入前备份：规则库_导入前备份_yyyyMMdd_HHmmss.tsv（同名自动 _2、_3，绝不覆盖旧文件）
 */
object RuleExporter {

    private const val DIR = "zhuanggui/规则库"
    private const val EXT = "tsv"
    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

    private val HEADERS = listOf(
        "型号名称", "前缀", "可重复扫", "计入统计", "查历史串号", "忽略", "默认装柜数量"
    )

    fun displayDir(): String = "/sdcard/$DIR"

    /** 同名文件已存在时追加 _2、_3…，避免同一秒内导出/备份互相覆盖 */
    internal fun uniqueFile(dir: File, baseName: String): File {
        var file = File(dir, "$baseName.$EXT")
        var n = 2
        while (file.exists()) {
            file = File(dir, "${baseName}_$n.$EXT")
            n++
        }
        return file
    }

    fun export(models: List<RuleModel>, rules: List<RuleLibEntry>): File {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), DIR)
        dir.mkdirs()
        val file = uniqueFile(dir, "规则库_${timeFormat.format(LocalDateTime.now())}")
        exportTo(file, models, rules)
        return file
    }

    /**
     * 导入前备份（v1.8.2）：规则导入会按「前缀 + 型号」覆盖，先把当前规则库原样导出到
     * 规则库_导入前备份_yyyyMMdd_HHmmss.tsv，供核对/回退。
     * 返回 null = 当前规则库为空，无需备份。
     */
    fun backupBeforeImport(models: List<RuleModel>, rules: List<RuleLibEntry>): File? =
        backup(models, rules, "规则库_导入前备份")

    /**
     * WiFi 规则库下载前备份（v1.9.0）：整库覆盖前先留一份本机快照，可随时导入恢复。
     * 返回 null = 本机规则库为空。
     */
    fun backupBeforeSync(models: List<RuleModel>, rules: List<RuleLibEntry>): File? =
        backup(models, rules, "规则库_同步前备份")

    private fun backup(models: List<RuleModel>, rules: List<RuleLibEntry>, prefix: String): File? {
        if (models.isEmpty() && rules.isEmpty()) return null
        val dir = File(android.os.Environment.getExternalStorageDirectory(), DIR)
        dir.mkdirs()
        val file = uniqueFile(dir, "${prefix}_${timeFormat.format(LocalDateTime.now())}")
        exportTo(file, models, rules)
        return file
    }

    /** 纯写入（单测可直接用临时文件）：UTF-8 BOM + CRLF，Tab 分隔。 */
    internal fun exportTo(file: File, models: List<RuleModel>, rules: List<RuleLibEntry>) {
        val byName = rules.groupBy { it.name }
        val names = (models.map { it.name } + rules.map { it.name }.filter { it.isNotBlank() })
            .distinct()
            .sorted()
        // v16：默认装柜数量按型号维护 1 个；导出时同一型号的每一行都写该型号的默认值。
        val modelDefaults = models.associate { it.name to it.defaultQuantity }

        val rows = mutableListOf<List<String>>()
        fun addRow(name: String, e: RuleLibEntry?) {
            rows.add(
                listOf(
                    name,
                    e?.prefix ?: "",
                    yesNo(e?.allowDuplicate ?: false),
                    yesNo(e?.countEnabled ?: true),
                    yesNo(e?.checkHistory ?: false),
                    yesNo(e?.ignoreRepeat ?: false),
                    (modelDefaults[name] ?: 0).toString()
                )
            )
        }
        names.forEach { name ->
            val list = byName[name].orEmpty().sortedBy { it.prefix }
            if (list.isEmpty()) addRow(name, null) else list.forEach { addRow(name, it) }
        }
        byName[""].orEmpty().sortedBy { it.prefix }.forEach { addRow("", it) }

        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append(HEADERS.joinToString("\t")).append("\r\n")
        rows.forEach { row ->
            sb.append(row.joinToString("\t") { cleanField(it) }).append("\r\n")
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    /** 型号/前缀里不允许出现 Tab/换行，否则会破坏 TSV 列结构 */
    private fun cleanField(s: String): String =
        s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    private fun yesNo(v: Boolean) = if (v) "是" else "否"
}
