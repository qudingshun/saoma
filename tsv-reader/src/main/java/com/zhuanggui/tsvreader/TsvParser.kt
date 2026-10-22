package com.zhuanggui.tsvreader

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 解析后的 TSV：第一行作为表头，其余为数据行（各行列数已对齐）。 */
data class TsvDocument(
    val header: List<String>,
    val rows: List<List<String>>
) {
    fun columnIndex(name: String): Int = header.indexOfFirst { it.trim() == name }
}

/**
 * 独立 TSV 阅读器的纯逻辑解析器（便于单元测试）。
 *
 * - 支持 UTF-8（含 BOM）、UTF-16 LE/BE（含 BOM）、GB18030/GBK 兜底
 * - 支持 CRLF/LF、末行无换行、空行忽略
 * - 支持双引号包裹的字段（字段内可含 Tab、换行，"" 表示一个引号）
 * - 第一行固定作为表头；数据行列数不足补空，超出时自动扩展“列N”
 */
object TsvParser {

    const val MAX_FILE_BYTES: Int = 20 * 1024 * 1024

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() ->
                decodeStrict(bytes, 3, Charsets.UTF_8)

            bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xFE.toByte() ->
                decodeStrict(bytes, 2, Charsets.UTF_16LE)

            bytes.size >= 2 &&
                bytes[0] == 0xFE.toByte() &&
                bytes[1] == 0xFF.toByte() ->
                decodeStrict(bytes, 2, Charsets.UTF_16BE)

            else -> try {
                decodeStrict(bytes, 0, Charsets.UTF_8)
            } catch (_: CharacterCodingException) {
                // 现场可能拿到 Excel 另存的 GBK/GB18030 文件
                decodeStrict(bytes, 0, Charset.forName("GB18030"))
            }
        }
    }

    private fun decodeStrict(bytes: ByteArray, offset: Int, charset: Charset): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    }

    fun parse(text: String): TsvDocument {
        val parsed = parseRows(text)
        if (parsed.isEmpty()) return TsvDocument(emptyList(), emptyList())

        val header = parsed.first().mapIndexed { index, value ->
            (if (index == 0) value.removePrefix("\uFEFF") else value).trim()
        }
        val data = parsed.drop(1).filter { row -> row.any { it.isNotBlank() } }
        val columnCount = maxOf(header.size, data.maxOfOrNull { it.size } ?: 0)

        val fixedHeader = if (header.isEmpty()) {
            (1..columnCount).map { "列$it" }
        } else {
            header + (header.size until columnCount).map { "列${it + 1}" }
        }
        val fixedRows = data.map { row ->
            if (row.size == columnCount) row else row + List(columnCount - row.size) { "" }
        }
        return TsvDocument(fixedHeader, fixedRows)
    }

    private fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0

        fun finishCell() {
            row.add(cell.toString())
            cell.setLength(0)
        }

        fun finishRow() {
            finishCell()
            rows.add(row)
            row = mutableListOf()
        }

        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"')
                        i += 2
                        continue
                    }
                    inQuotes = false
                    i++
                    continue
                }
                cell.append(c)
                i++
                continue
            }

            when (c) {
                '"' -> {
                    if (cell.isEmpty()) inQuotes = true else cell.append(c)
                }
                '\t' -> finishCell()
                '\r' -> Unit
                '\n' -> finishRow()
                else -> cell.append(c)
            }
            i++
        }

        if (cell.isNotEmpty() || row.isNotEmpty()) finishRow()
        return rows
    }

    /** 带大小上限读取流，避免误点超大文件导致内存不足。 */
    @Throws(Exception::class)
    fun readLimited(input: InputStream, maxBytes: Int = MAX_FILE_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                throw IllegalArgumentException("文件超过 ${maxBytes / 1024 / 1024}MB，无法在 PDA 上打开")
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
