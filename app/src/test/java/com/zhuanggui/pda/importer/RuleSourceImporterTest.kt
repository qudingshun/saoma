package com.zhuanggui.pda.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规则库导出/导入文本解析单测（v1.7.4）：两列兼容、四开关往返、表头跳过。 */
class RuleSourceImporterTest {

    @Test
    fun twoColumns_defaultsAndNoFlagColumns() {
        val list = RuleSourceImporter.parseTextContent(
            "前缀\t名称/型号\n411\tT45LW\n844,惠而浦冰箱\n"
        )
        assertEquals(2, list.size)
        val a = list[0]
        assertEquals("411", a.prefix)
        assertEquals("T45LW", a.name)
        assertFalse(a.hasFlags)
        assertFalse(a.allowDuplicate)
        assertTrue(a.countEnabled)
        assertFalse(a.checkHistory)
        assertFalse(a.ignoreRepeat)
        assertEquals("844", list[1].prefix)
        assertEquals("惠而浦冰箱", list[1].name)
    }

    @Test
    fun exportedTsv_roundTripsAllSwitches() {
        val text = "\uFEFF前缀\t名称/型号\t可重复扫\t计入统计\t查历史串号\t忽略\n" +
            "311\tT70\t0\t1\t1\t0\n" +
            "ZZ1\t测试\t1\t0\t0\t1\n"
        val list = RuleSourceImporter.parseTextContent(text)
        assertEquals(2, list.size)
        with(list[0]) {
            assertEquals("311", prefix)
            assertEquals("T70", name)
            assertTrue(hasFlags)
            assertFalse(allowDuplicate)
            assertTrue(countEnabled)
            assertTrue(checkHistory)
            assertFalse(ignoreRepeat)
        }
        with(list[1]) {
            assertTrue(hasFlags)
            assertTrue(allowDuplicate)
            assertFalse(countEnabled)
            assertFalse(checkHistory)
            assertTrue(ignoreRepeat)
        }
    }

    @Test
    fun headerAndInvalidPrefixesSkipped() {
        val list = RuleSourceImporter.parseTextContent(
            "barcode,name\n!bad,忽略\nA1,型号一\n"
        )
        assertEquals(1, list.size)
        assertEquals("A1", list[0].prefix)
    }

    @Test
    fun yesNoFlagsAccepted() {
        val list = RuleSourceImporter.parseTextContent(
            "A1\t型号\t是\t否\t是\t否\n"
        )
        val r = list.single()
        assertTrue(r.allowDuplicate)
        assertFalse(r.countEnabled)
        assertTrue(r.checkHistory)
        assertFalse(r.ignoreRepeat)
    }

    /** v15：第 7 列默认装柜数量可导入；旧六列文件保持未设置状态 */
    @Test
    fun seventhColumn_defaultQuantity() {
        val text = "\uFEFF前缀\t名称/型号\t可重复扫\t计入统计\t查历史串号\t忽略\t默认装柜数量\n" +
            "311\tT70\t0\t1\t0\t0\t260\n" +
            "312\tT70\t0\t1\t0\t0\t\n"
        val list = RuleSourceImporter.parseTextContent(text)
        assertEquals(260, list[0].defaultQuantity)
        assertTrue(list[0].hasDefaultQuantity)
        assertEquals(0, list[1].defaultQuantity)
        assertFalse(list[1].hasDefaultQuantity)
    }

    /** v1.9.2：真实机型前缀含 - 与 #（如 HYW-T55、#9970），必须能录入 */
    @Test
    fun cleanPrefixAllowsDashAndHash() {
        assertEquals("HYW-T55", RuleSourceImporter.cleanPrefix("hyw-t55"))
        assertEquals("#9970", RuleSourceImporter.cleanPrefix("#9970"))
        assertEquals("ASW-120HTB", RuleSourceImporter.cleanPrefix("ASW-120HTB"))
        assertEquals(null, RuleSourceImporter.cleanPrefix("A B"))
        assertEquals(null, RuleSourceImporter.cleanPrefix(""))
    }
}
