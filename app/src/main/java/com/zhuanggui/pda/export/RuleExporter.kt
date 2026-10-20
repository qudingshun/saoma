package com.zhuanggui.pda.export

import com.zhuanggui.pda.data.entity.RuleLibEntry
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 规则库导出（v1.7.4）：把规则库写成与「导入规则源」兼容的 TSV，方便拷到其他 PDA 维护。
 *
 * 列：前缀 / 名称型号 / 可重复扫 / 计入统计 / 查历史串号 / 忽略（1/0）
 * 编码：UTF-8 带 BOM（Excel 打开中文不乱码；导入端按 UTF-8 解析）
 * 路径：/sdcard/ZhuangGui/规则库/规则库_yyyyMMdd_HHmmss.tsv（每次导出新文件，便于选最新）
 */
object RuleExporter {

    private const val DIR = "ZhuangGui/规则库"
    private const val HEADER = "前缀\t名称/型号\t可重复扫\t计入统计\t查历史串号\t忽略\n"
    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

    fun displayDir(): String = "/sdcard/$DIR"

    /** 导出全部规则（按前缀排序）；返回写入的文件 */
    fun export(entries: List<RuleLibEntry>): File {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), DIR)
        dir.mkdirs()
        val file = File(dir, "规则库_${timeFormat.format(LocalDateTime.now())}.tsv")
        val sb = StringBuilder("\uFEFF")
        sb.append(HEADER)
        entries.sortedBy { it.prefix }.forEach { e ->
            sb.append(e.prefix).append('\t')
                .append(e.name).append('\t')
                .append(if (e.allowDuplicate) "1" else "0").append('\t')
                .append(if (e.countEnabled) "1" else "0").append('\t')
                .append(if (e.checkHistory) "1" else "0").append('\t')
                .append(if (e.ignoreRepeat) "1" else "0").append('\n')
        }
        file.writeBytes(sb.toString().toByteArray(Charsets.UTF_8))
        return file
    }
}
