package com.zhuanggui.pda.export

import com.zhuanggui.pda.data.entity.RuleLibEntry
import com.zhuanggui.pda.data.entity.RuleModel
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 规则库导出（v1.7.5）：XLSX（Excel），方便查看/对齐/维护，并可原样导入其他 PDA。
 *
 * 列：型号名称 / 前缀 / 可重复扫 / 计入统计 / 查历史串号 / 忽略（是/否）
 * - 以型号为组：先列型号，再列该型号下的前缀规则（每个前缀一行，可逐条维护）
 * - 空型号也会导出一行（前缀为空），导入时用于建空型号
 * 路径：/sdcard/ZhuangGui/规则库/规则库_yyyyMMdd_HHmmss.xlsx
 */
object RuleExporter {

    private const val DIR = "ZhuangGui/规则库"
    private const val SHEET = "规则库"
    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

    fun displayDir(): String = "/sdcard/$DIR"

    fun export(models: List<RuleModel>, rules: List<RuleLibEntry>): File {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), DIR)
        dir.mkdirs()
        val file = File(dir, "规则库_${timeFormat.format(LocalDateTime.now())}.xlsx")
        exportTo(file, models, rules)
        return file
    }

    /** 纯写入（单测可直接用临时文件） */
    internal fun exportTo(file: File, models: List<RuleModel>, rules: List<RuleLibEntry>) {
        val byName = rules.groupBy { it.name }
        // 型号名：型号表 + 规则里出现但型号表缺失的名称（容错）；空名称的规则单独成行
        val names = (models.map { it.name } + rules.map { it.name }.filter { it.isNotBlank() })
            .distinct()
            .sorted()

        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet(SHEET)
            val header = sheet.createRow(0)
            val headerStyle = wb.createCellStyle().apply {
                val font = wb.createFont().apply { bold = true }
                setFont(font)
                fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
            }
            listOf("型号名称", "前缀", "可重复扫", "计入统计", "查历史串号", "忽略")
                .forEachIndexed { i, text ->
                    header.createCell(i).apply { setCellValue(text); cellStyle = headerStyle }
                }

            var rowIndex = 1
            fun putRow(name: String, e: RuleLibEntry?) {
                val row = sheet.createRow(rowIndex++)
                row.createCell(0).setCellValue(name)
                row.createCell(1).setCellValue(e?.prefix ?: "")
                row.createCell(2).setCellValue(yesNo(e?.allowDuplicate ?: false))
                row.createCell(3).setCellValue(yesNo(e?.countEnabled ?: true))
                row.createCell(4).setCellValue(yesNo(e?.checkHistory ?: false))
                row.createCell(5).setCellValue(yesNo(e?.ignoreRepeat ?: false))
            }

            names.forEach { name ->
                val list = byName[name].orEmpty().sortedBy { it.prefix }
                if (list.isEmpty()) putRow(name, null) else list.forEach { putRow(name, it) }
            }
            // 未填型号的规则（历史数据容错）：型号列留空
            byName[""].orEmpty().sortedBy { it.prefix }.forEach { putRow("", it) }

            sheet.setColumnWidth(0, 7000)
            sheet.setColumnWidth(1, 6000)
            (2..5).forEach { sheet.setColumnWidth(it, 3200) }
            sheet.createFreezePane(0, 1)

            file.outputStream().use { wb.write(it) }
        }
    }

    private fun yesNo(v: Boolean) = if (v) "是" else "否"
}
