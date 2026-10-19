package com.zhuanggui.pda.export

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.zhuanggui.pda.data.entity.ScanRecord
import com.zhuanggui.pda.data.entity.ScanResult
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * 装柜记录自动落盘（TSV，制表符分隔）：
 *
 *   /sdcard/ZhuangGui/装柜记录/yyyy-MM-dd/柜号.tsv   —— 未删除（活动）记录
 *   /sdcard/ZhuangGui/回收站/yyyy-MM-dd/柜号.tsv     —— 已删除（软删除）记录
 *   /sdcard/ZhuangGui/装柜完成汇总.tsv               —— 每装齐一柜追加一行汇总（v1.5.8）
 *
 * - 每扫一码调用 append() 增量写一行（不重写全文件）
 * - 删除 / 撤回 / 恢复后调用 syncDay() 按数据库重写当天两侧文件，保持与 APP 动作一致：
 *   整组删空 = 文件从「装柜记录」移动到「回收站」目录；撤回 = 移回；部分删除 = 两侧各留一份
 * - 编码 UTF-8 带 BOM（Excel 打开中文不乱码）
 *
 * 权限：Android 11+ 需要“所有文件访问”（PDA 离线自用应用常规做法），
 * 低于 Android 11 用 WRITE_EXTERNAL_STORAGE。
 */
object FileExporter {

    private const val ROOT_DIR = "ZhuangGui/装柜记录"
    private const val TRASH_DIR = "ZhuangGui/回收站"
    /** 时间格式化：java.time（SimpleDateFormat 非线程安全，v1.5.1 起替换） */
    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val zone: ZoneId = ZoneId.systemDefault()

    private fun fmtDate(d: Date): String = dayFormat.format(Instant.ofEpochMilli(d.time).atZone(zone))
    private fun fmtTime(d: Date): String = timeFormat.format(Instant.ofEpochMilli(d.time).atZone(zone))

    private const val HEADER = "时间\t柜号\t条码\t完整码\t结果\n"

    /** 装柜完成汇总：单文件累积，每装齐一柜追加一行 */
    private const val SUMMARY_DIR = "ZhuangGui"
    private const val SUMMARY_FILE = "装柜完成汇总.tsv"
    private const val SUMMARY_HEADER = "完成时间\t柜号\t计划数量\t正常\t串号\t重复\t超量\t记录总数\n"

    /** 根目录（PDA 文件管理器里直接可找） */
    fun rootDir(): File = File(Environment.getExternalStorageDirectory(), ROOT_DIR)

    /** 界面展示用路径（/sdcard 与真实路径 /storage/emulated/0 等价，文件管理器里可见；界面文案统一从这里取） */
    fun displayPath(): String = "/sdcard/$ROOT_DIR"

    /** 文件回收站目录（与 APP 删除动作一致：删掉的记录文件挪到这里） */
    fun trashRootDir(): File = File(Environment.getExternalStorageDirectory(), TRASH_DIR)

    /** 当前是否有写入权限 */
    fun hasPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 引导用户授权：11+ 跳“所有文件访问”设置页，低版本请求运行时权限 */
    fun requestPermission(activity: android.app.Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(android.net.Uri.parse("package:${activity.packageName}"))
            runCatching { activity.startActivity(intent) }
                .onFailure {
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
        } else {
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }
    }

    /** 柜号 → 安全文件名（去掉文件系统非法字符） */
    private fun safeName(containerNo: String): String =
        containerNo.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    /** 一行记录（TSV：制表符分隔） */
    private fun lineOf(record: ScanRecord): String = buildString {
        append(fmtTime(record.scannedAt))
        append('\t'); append(record.containerNo)
        append('\t'); append(record.barcode)
        append('\t'); append(record.fullCode)
        append('\t'); append(resultText(record.result))
        append('\n')
    }

    /** 重写整个文件：BOM + 表头 + 按扫描时间排序的记录 */
    private fun writeAll(file: File, records: List<ScanRecord>) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder("\uFEFF$HEADER")
        records.sortedWith(compareBy({ it.scannedAt.time }, { it.id })).forEach { sb.append(lineOf(it)) }
        file.outputStream().use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
    }

    /**
     * 增量追加一条记录。返回 true=写入成功。
     * 失败不抛异常（无权限/存储故障时由调用方提示）。
     */
    fun append(record: ScanRecord): Boolean = runCatching {
        val day = fmtDate(record.scannedAt)
        val dir = File(rootDir(), day)
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = File(dir, "${safeName(record.containerNo)}.tsv")
        if (!file.exists()) {
            file.outputStream().use { it.write("\uFEFF$HEADER".toByteArray(Charsets.UTF_8)) }
        }
        FileOutputStream(file, true).use { it.write(lineOf(record).toByteArray(Charsets.UTF_8)) }
        true
    }.getOrDefault(false)

    /** 装柜完成汇总文件在文件管理器里的展示路径 */
    fun summaryDisplayPath(): String = "/sdcard/$SUMMARY_DIR/$SUMMARY_FILE"

    /**
     * 装柜完成（正常数首次达到本柜数量）时，把本柜统计追加一行到
     * /sdcard/ZhuangGui/装柜完成汇总.tsv（单文件累积；BOM 表头只在文件首次创建时写入）。
     * 返回 true=写入成功；失败不抛异常（无权限/存储故障时由调用方提示）。
     */
    fun appendCompletion(
        containerNo: String,
        total: Int,
        ok: Int,
        mismatch: Int,
        duplicate: Int,
        over: Int,
        totalRecords: Int
    ): Boolean = runCatching {
        val dir = File(Environment.getExternalStorageDirectory(), SUMMARY_DIR)
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = File(dir, SUMMARY_FILE)
        if (!file.exists()) {
            file.outputStream().use { it.write("\uFEFF$SUMMARY_HEADER".toByteArray(Charsets.UTF_8)) }
        }
        val line = buildString {
            append(fmtTime(Date()))
            append('\t'); append(containerNo)
            append('\t'); append(total)
            append('\t'); append(ok)
            append('\t'); append(mismatch)
            append('\t'); append(duplicate)
            append('\t'); append(over)
            append('\t'); append(totalRecords)
            append('\n')
        }
        FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        true
    }.getOrDefault(false)

    /**
     * 删除 / 撤回 / 恢复后：按数据库现状重写「某柜某日」两侧文件。
     *  - 装柜记录文件 = [active]（空则删除文件 → 等价于整组移出）
     *  - 回收站文件   = [deleted]（空则删除文件 → 等价于整组移回）
     *  - 两侧都空（清空回收站）= 永久删除，旧 CSV 也一并清理
     *  - v1.5.0 之前的旧 .csv 文件：同步时挪进回收站目录（不再留在装柜记录里造成不一致）
     */
    fun syncDay(dayLabel: String, containerNo: String, active: List<ScanRecord>, deleted: List<ScanRecord>) {
        runCatching {
            val name = safeName(containerNo)
            val activeDir = File(rootDir(), dayLabel)
            val trashDir = File(trashRootDir(), dayLabel)
            val activeFile = File(activeDir, "$name.tsv")
            val trashFile = File(trashDir, "$name.tsv")
            val legacyActive = File(activeDir, "$name.csv")   // v1.5.0 之前的旧 CSV 落盘
            val legacyTrash = File(trashDir, "$name.csv")

            // 旧 .csv 不再参与新逻辑：先挪去回收站目录归档（目标已存在则保留原位，不删数据）
            if (legacyActive.exists() && !legacyTrash.exists()) {
                trashDir.mkdirs()
                legacyActive.renameTo(legacyTrash)
            }

            if (active.isNotEmpty()) {
                writeAll(activeFile, active)
            } else {
                activeFile.delete()
            }

            if (deleted.isNotEmpty()) {
                writeAll(trashFile, deleted)
            } else {
                trashFile.delete()
                // 两侧都空 = 清空回收站（永久删除）→ 旧文件一并清理
                if (active.isEmpty()) {
                    legacyTrash.delete()
                    legacyActive.delete()
                }
            }
        }
    }

    private fun resultText(r: ScanResult): String = when (r) {
        ScanResult.OK -> "正常"
        ScanResult.NOT_IN_LIST -> "不在清单"
        ScanResult.OVER_LIMIT -> "超量"
        ScanResult.SERIAL_MISMATCH -> "串号"
        ScanResult.DUPLICATE -> "重复"
        ScanResult.AUX -> "辅助"
    }
}
