package com.zhuanggui.pda.importer

import com.zhuanggui.pda.data.entity.RuleLibEntry
import com.zhuanggui.pda.data.entity.RuleModel
import com.zhuanggui.pda.export.RuleExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** TSV 导出/导入往返单测（v1.12.1）：型号主显示、四开关、空型号、同前缀多型号、旧格式兼容。 */
class RuleTextIoTest {

    @Test
    fun exportThenImport_roundTrip() {
        val models = listOf(
            RuleModel(id = 1, name = "T45LW", defaultQuantity = 260),
            RuleModel(id = 2, name = "T70"),
            RuleModel(id = 3, name = "空型号", defaultQuantity = 99)
        )
        val rules = listOf(
            RuleLibEntry(prefix = "411", name = "T45LW"),
            RuleLibEntry(prefix = "311", name = "T70", checkHistory = true),
            RuleLibEntry(prefix = "ZZ1", name = "T70", allowDuplicate = true, ignoreRepeat = true)
        )
        val file = File.createTempFile("rules", ".tsv")
        try {
            RuleExporter.exportTo(file, models, rules)
            val parsed = RuleSourceImporter.parseTextContent(file.readText())

            val byPrefix = parsed.filter { it.prefix.isNotBlank() }.associateBy { it.prefix }
            assertEquals(3, byPrefix.size)
            with(byPrefix.getValue("411")) {
                assertEquals("T45LW", name)
                assertFalse(allowDuplicate)
                assertTrue(countEnabled)
                assertFalse(checkHistory)
                assertEquals(260, defaultQuantity)
                assertTrue(hasDefaultQuantity)
            }
            with(byPrefix.getValue("311")) {
                assertEquals("T70", name)
                assertTrue(checkHistory)
            }
            with(byPrefix.getValue("ZZ1")) {
                assertTrue(allowDuplicate)
                assertTrue(ignoreRepeat)
            }
            val modelOnly = parsed.filter { it.prefix.isBlank() }
            assertEquals(listOf("空型号"), modelOnly.map { it.name })
            assertEquals(99, modelOnly[0].defaultQuantity)
            assertTrue(modelOnly[0].hasDefaultQuantity)
        } finally {
            file.delete()
        }
    }

    @Test
    fun exportThenImport_keepsSamePrefixInTwoModels() {
        val models = listOf(
            RuleModel(id = 1, name = "MODEL_A"),
            RuleModel(id = 2, name = "MODEL_B")
        )
        val rules = listOf(
            RuleLibEntry(prefix = "COMMON", name = "MODEL_A", allowDuplicate = true, ignoreRepeat = true),
            RuleLibEntry(prefix = "COMMON", name = "MODEL_B", allowDuplicate = true, ignoreRepeat = true)
        )
        val file = File.createTempFile("rules-duplicate", ".tsv")
        try {
            RuleExporter.exportTo(file, models, rules)
            val parsed = RuleSourceImporter.parseTextContent(file.readText())
            val common = parsed.filter { it.prefix == "COMMON" }
            assertEquals(2, common.size)
            assertEquals(setOf("MODEL_A", "MODEL_B"), common.map { it.name }.toSet())
            assertTrue(common.all { it.allowDuplicate && it.ignoreRepeat })
        } finally {
            file.delete()
        }
    }

    @Test
    fun legacyPrefixFirst_withHeader_isParsed() {
        val parsed = RuleSourceImporter.parseTextContent(
            "前缀\t名称/型号\t可重复扫\t计入统计\t查历史串号\t忽略\n" +
                "844\t惠而浦冰箱\t0\t1\t0\t0\n"
        )
        assertEquals(1, parsed.size)
        assertEquals("844", parsed[0].prefix)
        assertEquals("惠而浦冰箱", parsed[0].name)
        assertTrue(parsed[0].hasFlags)
    }

    @Test
    fun nameFirstWithoutHeader_defaultsToPrefixFirst() {
        val parsed = RuleSourceImporter.parseTextContent(
            "844,惠而浦冰箱\n845,惠而浦冰箱2\n"
        )
        assertEquals(listOf("844", "845"), parsed.map { it.prefix })
    }

    @Test
    fun uniqueFile_neverOverwrites() {
        val dir = Files.createTempDirectory("rulexport").toFile()
        try {
            val base = "规则库_20261020_120000"
            val a = RuleExporter.uniqueFile(dir, base)
            a.writeText("old")
            val b = RuleExporter.uniqueFile(dir, base)
            assertEquals("$base.tsv", a.name)
            assertEquals("${base}_2.tsv", b.name)
            assertFalse(b.exists())
            assertEquals("old", a.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
