package com.zhuanggui.tsvreader

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TsvParserTest {

    @Test
    fun `parses utf8 bom crlf and header`() {
        val text = "\uFEFF时间\t柜号\t条码\t完整码\t结果\t操作员\r\n" +
            "2026-10-21 14:49:49\tCSGU7441210\tEJ18011139E\tCSGU7441210，EJ18011139E\t正常\t\r\n" +
            "2026-10-21 14:49:50\tCSGU7441210\tEJ180111417\tCSGU7441210，EJ180111417\t正常\t\r\n"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.removePrefix("\uFEFF").toByteArray()
        val document = TsvParser.parse(TsvParser.decode(bytes))

        assertEquals(listOf("时间", "柜号", "条码", "完整码", "结果", "操作员"), document.header)
        assertEquals(2, document.rows.size)
        assertEquals("EJ18011139E", document.rows[0][2])
        assertEquals("正常", document.rows[0][4])
        assertEquals(2, document.columnIndex("条码"))
    }

    @Test
    fun `parses four column format without full code or result`() {
        val text = "\uFEFF时间\t柜号\t条码\t操作员\r\n" +
            "2026-10-22 14:49:49\tCSGU7441210\tEJ18011139E\tA01\r\n" +
            "2026-10-22 14:49:50\tCSGU7441210\tEJ180111417\t\r\n"
        val document = TsvParser.parse(text)

        assertEquals(listOf("时间", "柜号", "条码", "操作员"), document.header)
        assertEquals(2, document.rows.size)
        assertEquals("EJ18011139E", document.rows[0][2])
        assertEquals("A01", document.rows[0][3])
        assertEquals(-1, document.columnIndex("完整码"))
        assertEquals(-1, document.columnIndex("结果"))
    }

    @Test
    fun `supports quoted tab newline and escaped quote`() {
        val text = "名称\t备注\n" +
            "\"A\tB\"\t\"第一行\n第二行 \"\"重点\"\"\"\n" +
            "普通\t无\n"
        val document = TsvParser.parse(text)

        assertEquals(2, document.rows.size)
        assertArrayEquals(arrayOf("A\tB", "第一行\n第二行 \"重点\""), document.rows[0].toTypedArray())
        assertArrayEquals(arrayOf("普通", "无"), document.rows[1].toTypedArray())
    }

    @Test
    fun `decodes gb18030 fallback`() {
        val text = "时间\t条码\n2026-10-21 14:49:49\tEJ18011139E\n"
        val bytes = text.toByteArray(Charset.forName("GB18030"))
        val document = TsvParser.parse(TsvParser.decode(bytes))

        assertEquals(listOf("时间", "条码"), document.header)
        assertEquals("EJ18011139E", document.rows[0][1])
    }

    @Test
    fun `decodes utf16 le with bom`() {
        val text = "时间\t条码\n2026-10-21\tEJ1\n"
        val body = text.toByteArray(Charsets.UTF_16LE)
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body
        val document = TsvParser.parse(TsvParser.decode(bytes))

        assertEquals(listOf("时间", "条码"), document.header)
        assertEquals("2026-10-21", document.rows[0][0])
    }

    @Test
    fun `ragged rows are padded and header extended`() {
        val document = TsvParser.parse(
            "时间\t条码\n" +
                "2026-10-21\tEJ1\t多出来的一列\n" +
                "只有一列\n"
        )

        assertEquals(listOf("时间", "条码", "列3"), document.header)
        assertEquals(3, document.rows[0].size)
        assertEquals("多出来的一列", document.rows[0][2])
        assertEquals(3, document.rows[1].size)
        assertEquals("", document.rows[1][1])
        assertEquals("", document.rows[1][2])
    }

    @Test
    fun `ignores blank lines and handles missing trailing newline`() {
        val document = TsvParser.parse(
            "时间\t条码\n" +
                "\n" +
                "2026-10-21 14:49:49\tEJ1\n" +
                "\n" +
                "2026-10-21 14:49:50\tEJ2"
        )

        assertEquals(2, document.rows.size)
        assertEquals("EJ1", document.rows[0][1])
        assertEquals("EJ2", document.rows[1][1])
    }

    @Test
    fun `empty text yields empty document`() {
        val document = TsvParser.parse("")
        assertTrue(document.header.isEmpty())
        assertTrue(document.rows.isEmpty())
    }

    @Test
    fun `readLimited rejects oversized input`() {
        val input = ByteArrayInputStream(ByteArray(32) { 1 })
        assertThrows(IllegalArgumentException::class.java) {
            TsvParser.readLimited(input, maxBytes = 16)
        }
    }

    @Test
    fun `readLimited reads complete input`() {
        val bytes = "时间\t条码\n".toByteArray()
        val read = TsvParser.readLimited(ByteArrayInputStream(bytes), maxBytes = 1024)
        assertArrayEquals(bytes, read)
    }
}
