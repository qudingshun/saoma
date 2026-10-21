package com.zhuanggui.pda.export

import com.zhuanggui.pda.data.entity.RuleLibEntry
import com.zhuanggui.pda.data.entity.RuleModel
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 规则库导出（v1.7.5）：XLSX（Excel），方便查看/对齐/维护，并可原样导入其他 PDA。
 *
 * 列：型号名称 / 前缀 / 可重复扫 / 计入统计 / 查历史串号 / 忽略（是/否）
 * - 以型号为组：先列型号，再列该型号下的前缀规则（每个前缀一行，可逐条维护）
 * - 空型号也会导出一行（前缀为空），导入时用于建空型号
 *
 * 说明：为兼容 Android（POI 写 XLSX 依赖 java.awt，现场机不可用），
 * 这里用最小 SpreadsheetML（zip + xml）直接生成，Excel/WPS 均可正常打开。
 *
 * 路径：/sdcard/ZhuangGui/规则库/规则库_yyyyMMdd_HHmmss.xlsx
 * 导入前备份：规则库_导入前备份_yyyyMMdd_HHmmss.xlsx（同名自动 _2、_3，绝不覆盖旧文件）
 */
object RuleExporter {

    private const val DIR = "zhuanggui/规则库"
    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

    private val HEADERS = listOf("型号名称", "前缀", "可重复扫", "计入统计", "查历史串号", "忽略")

    fun displayDir(): String = "/sdcard/$DIR"

    /** 同名文件已存在时追加 _2、_3…，避免同一秒内导出/备份互相覆盖 */
    internal fun uniqueFile(dir: File, baseName: String): File {
        var file = File(dir, "$baseName.xlsx")
        var n = 2
        while (file.exists()) {
            file = File(dir, "${baseName}_$n.xlsx")
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
     * 导入前备份（v1.8.2）：规则导入会按前缀覆盖，先把当前规则库原样导出到
     * 规则库_导入前备份_yyyyMMdd_HHmmss.xlsx，供核对/回退。
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

    /** 纯写入（单测可直接用临时文件） */
    internal fun exportTo(file: File, models: List<RuleModel>, rules: List<RuleLibEntry>) {
        val byName = rules.groupBy { it.name }
        val names = (models.map { it.name } + rules.map { it.name }.filter { it.isNotBlank() })
            .distinct()
            .sorted()

        val rows = mutableListOf<List<String>>()
        fun addRow(name: String, e: RuleLibEntry?) {
            rows.add(
                listOf(
                    name,
                    e?.prefix ?: "",
                    yesNo(e?.allowDuplicate ?: false),
                    yesNo(e?.countEnabled ?: true),
                    yesNo(e?.checkHistory ?: false),
                    yesNo(e?.ignoreRepeat ?: false)
                )
            )
        }
        names.forEach { name ->
            val list = byName[name].orEmpty().sortedBy { it.prefix }
            if (list.isEmpty()) addRow(name, null) else list.forEach { addRow(name, it) }
        }
        byName[""].orEmpty().sortedBy { it.prefix }.forEach { addRow("", it) }

        val sheetXml = buildSheet(rows)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", WORKBOOK)
            put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put("xl/styles.xml", STYLES)
            put("xl/worksheets/sheet1.xml", sheetXml)
        }
    }

    private fun buildSheet(rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<sheetViews><sheetView workbookViewId="0">""")
        sb.append("""<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>""")
        sb.append("""</sheetView></sheetViews>""")
        sb.append("""<cols>""")
        sb.append("""<col min="1" max="1" width="22" customWidth="1"/>""")
        sb.append("""<col min="2" max="2" width="18" customWidth="1"/>""")
        sb.append("""<col min="3" max="6" width="10" customWidth="1"/>""")
        sb.append("""</cols><sheetData>""")

        // 表头（加粗样式 s=1）
        sb.append("""<row r="1">""")
        HEADERS.forEachIndexed { i, h ->
            sb.append("""<c r="${cellRef(i, 1)}" t="inlineStr" s="1"><is><t>${esc(h)}</t></is></c>""")
        }
        sb.append("""</row>""")

        rows.forEachIndexed { index, cols ->
            val r = index + 2
            sb.append("""<row r="$r">""")
            cols.forEachIndexed { i, v ->
                sb.append("""<c r="${cellRef(i, r)}" t="inlineStr"><is><t>${esc(v)}</t></is></c>""")
            }
            sb.append("""</row>""")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun cellRef(col: Int, row: Int): String = "${('A' + col)}$row"

    private fun esc(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    private fun yesNo(v: Boolean) = if (v) "是" else "否"

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private const val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="规则库" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2">
<font><sz val="11"/><name val="Calibri"/></font>
<font><b/><sz val="11"/><name val="Calibri"/></font>
</fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border/></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
</cellXfs>
</styleSheet>"""
}
