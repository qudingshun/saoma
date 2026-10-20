package com.zhuanggui.pda.importer

import com.zhuanggui.pda.data.entity.RuleLibEntry
import com.zhuanggui.pda.data.entity.RuleModel
import com.zhuanggui.pda.export.RuleExporter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** XLSX 导出/导入往返单测（v1.7.5）：型号主显示、四开关、空型号、旧格式兼容。 */
class RuleExcelTest {

    @Test
    fun exportThenImport_roundTrip() {
        val models = listOf(
            RuleModel(id = 1, name = "T45LW"),
            RuleModel(id = 2, name = "T70"),
            RuleModel(id = 3, name = "空型号")
        )
        val rules = listOf(
            RuleLibEntry(prefix = "411", name = "T45LW"),
            RuleLibEntry(prefix = "311", name = "T70", checkHistory = true),
            RuleLibEntry(prefix = "ZZ1", name = "T70", allowDuplicate = true, ignoreRepeat = true)
        )
        val file = File.createTempFile("rules", ".xlsx")
        try {
            RuleExporter.exportTo(file, models, rules)
            val parsed = file.inputStream().use { RuleSourceImporter.parseExcel(it) }

            val byPrefix = parsed.filter { it.prefix.isNotBlank() }.associateBy { it.prefix }
            assertEquals(3, byPrefix.size)
            with(byPrefix.getValue("411")) {
                assertEquals("T45LW", name)
                assertFalse(allowDuplicate)
                assertTrue(countEnabled)
                assertFalse(checkHistory)
            }
            with(byPrefix.getValue("311")) {
                assertEquals("T70", name)
                assertTrue(checkHistory)
            }
            with(byPrefix.getValue("ZZ1")) {
                assertTrue(allowDuplicate)
                assertTrue(ignoreRepeat)
            }
            // 空型号：文件里有一行只有型号名称、没有前缀
            val modelOnly = parsed.filter { it.prefix.isBlank() }.map { it.name }
            assertEquals(listOf("空型号"), modelOnly)
        } finally {
            file.delete()
        }
    }

    @Test
    fun legacyPrefixFirst_withHeader_isParsed() {
        val bytes = workbook(
            listOf(
                listOf("前缀", "名称/型号", "可重复扫", "计入统计", "查历史串号", "忽略"),
                listOf("844", "惠而浦冰箱", "0", "1", "0", "0")
            )
        )
        val parsed = RuleSourceImporter.parseExcel(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(1, parsed.size)
        assertEquals("844", parsed[0].prefix)
        assertEquals("惠而浦冰箱", parsed[0].name)
        assertTrue(parsed[0].hasFlags)
    }

    @Test
    fun nameFirstWithoutHeader_defaultsToPrefixFirst() {
        // 无表头的旧文件：第一列仍是前缀（保持兼容）
        val bytes = workbook(
            listOf(
                listOf("844", "惠而浦冰箱", "", "", "", ""),
                listOf("845", "惠而浦冰箱2", "", "", "", "")
            )
        )
        val parsed = RuleSourceImporter.parseExcel(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(listOf("844", "845"), parsed.map { it.prefix })
    }

    private fun workbook(rows: List<List<String>>): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("规则库")
            rows.forEachIndexed { r, cols ->
                val row = sheet.createRow(r)
                cols.forEachIndexed { c, v -> row.createCell(c).setCellValue(v) }
            }
            wb.write(out)
        }
        return out
    }
}
