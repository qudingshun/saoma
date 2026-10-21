package com.zhuanggui.pda.export

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
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
 * 装柜记录自动落盘（TSV，制表符分隔）——v1.10.0 改为 **根目录 zhuanggui**：
 *
 *   /sdcard/zhuanggui/装柜记录/yyyy-MM-dd/柜号.tsv   —— 未删除（活动）记录
 *   /sdcard/zhuanggui/回收站/yyyy-MM-dd/柜号.tsv     —— 已删除（软删除）记录
 *   /sdcard/zhuanggui/装柜完成汇总.tsv               —— 每装齐一柜追加一行汇总
 *   /sdcard/zhuanggui/规则库/…                      —— 规则库导出 / 备份
 *
 * 写入策略（三层保险，**任何情况下不丢记录**）：
 *  1) 主目录 /sdcard/zhuanggui（现场可见、可 U 盘拷贝）；
 *  2) 主目录不可写（权限/空间/ROM 限制）→ 自动改写到 App 专属目录
 *     /sdcard/Android/data/com.zhuanggui.pda/files/records/zhuanggui/…，界面明确提示；
 *  3) 主目录恢复可写后，首页点「重新导出」把暂存记录搬回主目录。
 *
 * 兼容性：旧目录 /sdcard/ZhuangGui 在首次启动时自动迁移（改名或合并拷贝）到 zhuanggui；
 * 大小写不敏感的手机上两者本就是同一目录，直接跳过。
 *
 * 权限：targetSdk 29 + requestLegacyExternalStorage → Android 11 的东集 ROM（legacy sdcardfs）
 * 也能直接写 /sdcard；仍保留「所有文件访问」引导作为双保险。
 */
object FileExporter {

    private const val TAG = "FileExporter"

    /** 根目录名（用户要求：根目录下的 zhuanggui 文件夹） */
    const val ROOT_NAME = "zhuanggui"

    /** 旧目录名（v1.9.x 用过，升级时迁移） */
    private const val LEGACY_ROOT_NAME = "ZhuangGui"

    private const val RECORD_SUBDIR = "装柜记录"
    private const val TRASH_SUBDIR = "回收站"
    private const val SUMMARY_FILE = "装柜完成汇总.tsv"
    private const val FALLBACK_SUBDIR = "records"

    /** 时间格式化：java.time（SimpleDateFormat 非线程安全，v1.5.1 起替换） */
    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val zone: ZoneId = ZoneId.systemDefault()

    private fun fmtDate(d: Date): String = dayFormat.format(Instant.ofEpochMilli(d.time).atZone(zone))
    private fun fmtTime(d: Date): String = timeFormat.format(Instant.ofEpochMilli(d.time).atZone(zone))

    private const val HEADER = "时间\t柜号\t条码\t完整码\t结果\t操作员\n"
    private const val SUMMARY_HEADER = "完成时间\t柜号\t计划数量\t正常\t串号\t重复\t超量\t记录总数\t操作员\n"

    @Volatile
    private var appContext: Context? = null

    /** 最近一次写入失败的原因（界面展示；null = 正常） */
    @Volatile
    var lastWriteError: String? = null
        private set

    /** 主目录是否真的可写（探测文件实测，不只看权限声明） */
    @Volatile
    private var primaryWritableCache: Boolean? = null

    enum class StorageStatus {
        /** 主目录可写，记录正常落在 /sdcard/zhuanggui */
        OK,

        /** 主目录不可写且尚未产生暂存（还没扫过码） */
        NEED_AUTH,

        /** 主目录不可写，记录已暂存在 App 专属目录（可重新导出） */
        FALLBACK
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ---------------- 目录 ----------------

    /** 根目录（PDA 文件管理器里直接可找） */
    fun rootDir(): File = File(externalRoot(), "$ROOT_NAME/$RECORD_SUBDIR")

    /** 文件回收站目录（与 APP 删除动作一致：删掉的记录文件挪到这里） */
    fun trashRootDir(): File = File(externalRoot(), "$ROOT_NAME/$TRASH_SUBDIR")

    /** 规则库目录（与记录同根） */
    fun ruleLibDir(): File = File(externalRoot(), "$ROOT_NAME/规则库")

    /** 界面展示用路径（/sdcard 与真实路径 /storage/emulated/0 等价） */
    fun displayPath(): String = "/sdcard/$ROOT_NAME/$RECORD_SUBDIR"

    /** 装柜完成汇总文件在文件管理器里的展示路径 */
    fun summaryDisplayPath(): String = "/sdcard/$ROOT_NAME/$SUMMARY_FILE"

    private fun externalRoot(): File = Environment.getExternalStorageDirectory()

    /** 兜底目录（App 专属，主目录不可写时用；一定可写） */
    fun fallbackBase(): File {
        val ctx = appContext
        val files = ctx?.getExternalFilesDir(null) ?: ctx?.filesDir ?: File(".")
        return File(files, FALLBACK_SUBDIR)
    }

    fun fallbackDisplayPath(): String = fallbackBase().absolutePath

    // ---------------- 权限 / 可写性 ----------------

    /** 主目录是否可写（实测：写一个探针文件再删） */
    fun primaryWritable(): Boolean {
        primaryWritableCache?.let { return it }
        val probe = File(externalRoot(), "$ROOT_NAME/.write_probe")
        val ok = runCatching {
            probe.parentFile?.mkdirs()
            probe.writeText("ok")
            probe.delete()
            true
        }.getOrElse {
            Log.w(TAG, "probe failed: ${it.message}")
            false
        }
        primaryWritableCache = ok
        return ok
    }

    /** 当前存储状态（首页提示用） */
    fun storageStatus(): StorageStatus {
        if (primaryWritable()) return StorageStatus.OK
        val fb = fallbackBase()
        val hasFallback = fb.exists() && (fb.walkTopDown().count { it.isFile } > 0)
        return if (hasFallback) StorageStatus.FALLBACK else StorageStatus.NEED_AUTH
    }

    /** 权限（兼容旧调用点）：以「实际能不能写」为准 */
    fun hasPermission(context: Context): Boolean = primaryWritable() ||
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED)

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

    // ---------------- 旧目录迁移 ----------------

    /** v1.10.0：旧目录 /sdcard/ZhuangGui → /sdcard/zhuanggui（改名或合并拷贝；同目录则跳过） */
    fun migrateLegacyRoot() {
        val legacy = File(externalRoot(), LEGACY_ROOT_NAME)
        val target = File(externalRoot(), ROOT_NAME)
        if (!legacy.exists()) return
        if (legacy.absolutePath.equals(target.absolutePath, ignoreCase = true)) return   // 同一目录
        runCatching {
            if (!target.exists() && legacy.renameTo(target)) {
                Log.i(TAG, "legacy root renamed -> $target")
            } else {
                copyTree(legacy, target)
                Log.i(TAG, "legacy root merged -> $target")
            }
        }.onFailure { Log.w(TAG, "migrate legacy root failed: ${it.message}") }
    }

    private fun copyTree(src: File, dst: File) {
        if (!src.exists()) return
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyTree(it, File(dst, it.name)) }
        } else {
            if (!dst.exists()) src.copyTo(dst, overwrite = true)
        }
    }

    // ---------------- 写入 ----------------

    private fun recordDirOf(base: File): File = File(base, "$ROOT_NAME/$RECORD_SUBDIR")
    private fun trashDirOf(base: File): File = File(base, "$ROOT_NAME/$TRASH_SUBDIR")
    private fun summaryDirOf(base: File): File = File(base, ROOT_NAME)

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
        append('\t'); append(record.operatorId)
        append('\n')
    }

    /** 重写整个文件：BOM + 表头 + 按扫描时间排序的记录 */
    private fun writeAll(file: File, records: List<ScanRecord>) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder("\uFEFF$HEADER")
        records.sortedWith(compareBy({ it.scannedAt.time }, { it.id })).forEach { sb.append(lineOf(it)) }
        file.outputStream().use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun appendTo(base: File, record: ScanRecord): Boolean = runCatching {
        val day = fmtDate(record.scannedAt)
        val dir = File(recordDirOf(base), day)
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = File(dir, "${safeName(record.containerNo)}.tsv")
        if (!file.exists()) {
            file.outputStream().use { it.write("\uFEFF$HEADER".toByteArray(Charsets.UTF_8)) }
        } else {
            upgradeHeaderIfNeeded(file)
        }
        FileOutputStream(file, true).use { it.write(lineOf(record).toByteArray(Charsets.UTF_8)) }
        true
    }.getOrElse {
        Log.w(TAG, "append failed: ${it.message}")
        false
    }

    /**
     * 增量追加一条记录。返回 true=写入成功（主目录或兜底目录）。
     * 主目录失败 → 自动写兜底目录，并记录原因（界面提示），**不丢记录**。
     */
    fun append(record: ScanRecord): Boolean {
        if (appendTo(externalRoot(), record)) {
            lastWriteError = null
            primaryWritableCache = true
            return true
        }
        val fb = appendTo(fallbackBase(), record)
        lastWriteError = if (fb) {
            "主目录 /sdcard/$ROOT_NAME 不可写，记录已暂存到 ${fallbackDisplayPath()}"
        } else {
            "记录保存失败：主目录与暂存目录都不可写（请检查存储空间/权限）"
        }
        Log.w(TAG, lastWriteError ?: "记录写入失败")
        return fb
    }

    /**
     * v1.6.0：旧版记录文件（表头无「操作员」列）首次追加时升级表头。
     * 数据行原样保留，老行末尾补一个空操作员列（不丢任何已扫记录）。
     */
    private fun upgradeHeaderIfNeeded(file: File) {
        val firstLine = runCatching {
            file.bufferedReader(Charsets.UTF_8).use { it.readLine() }
        }.getOrNull() ?: return
        if (firstLine.removePrefix("\uFEFF") == HEADER.trimEnd('\n')) return
        val lines = file.readLines(Charsets.UTF_8)
        val sb = StringBuilder("\uFEFF$HEADER")
        lines.drop(1).forEach { sb.append(it).append('\t').append('\n') }
        file.outputStream().use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
    }

    /**
     * 装柜完成（正常数首次达到计划装柜数量）时，把本柜统计追加一行到
     * /sdcard/zhuanggui/装柜完成汇总.tsv（单文件累积；BOM 表头只在文件首次创建时写入）。
     */
    fun appendCompletion(
        containerNo: String,
        total: Int,
        ok: Int,
        mismatch: Int,
        duplicate: Int,
        over: Int,
        totalRecords: Int,
        operatorId: String
    ): Boolean {
        val line = buildString {
            append(fmtTime(Date()))
            append('\t'); append(containerNo)
            append('\t'); append(total)
            append('\t'); append(ok)
            append('\t'); append(mismatch)
            append('\t'); append(duplicate)
            append('\t'); append(over)
            append('\t'); append(totalRecords)
            append('\t'); append(operatorId)
            append('\n')
        }
        val ok = appendCompletionTo(externalRoot(), line)
        if (ok) {
            lastWriteError = null
            return true
        }
        val fb = appendCompletionTo(fallbackBase(), line)
        lastWriteError = if (fb) {
            "主目录不可写，完成汇总已暂存到 ${fallbackDisplayPath()}"
        } else {
            "完成汇总写入失败：主目录与暂存目录都不可写"
        }
        return fb
    }

    private fun appendCompletionTo(base: File, line: String): Boolean = runCatching {
        val dir = summaryDirOf(base)
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = File(dir, SUMMARY_FILE)
        if (!file.exists()) {
            file.outputStream().use { it.write("\uFEFF$SUMMARY_HEADER".toByteArray(Charsets.UTF_8)) }
        }
        FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        true
    }.getOrElse {
        Log.w(TAG, "appendCompletion failed: ${it.message}")
        false
    }

    /**
     * 删除 / 撤回 / 恢复后：按数据库现状重写「某柜某日」两侧文件。
     *  - 装柜记录文件 = [active]（空则删除文件 → 等价于整组移出）
     *  - 回收站文件   = [deleted]（空则删除文件 → 等价于整组移回）
     *  - 两侧都空（清空回收站）= 永久删除，旧 CSV 也一并清理
     */
    fun syncDay(dayLabel: String, containerNo: String, active: List<ScanRecord>, deleted: List<ScanRecord>) {
        val done = syncDayInto(externalRoot(), dayLabel, containerNo, active, deleted)
        if (!done) {
            val fb = syncDayInto(fallbackBase(), dayLabel, containerNo, active, deleted)
            lastWriteError = if (fb) {
                "主目录不可写，记录文件已暂存到 ${fallbackDisplayPath()}"
            } else {
                "记录文件写入失败（主目录与暂存目录都不可写）"
            }
            Log.w(TAG, lastWriteError ?: "记录文件写入失败")
        } else {
            lastWriteError = null
        }
    }

    private fun syncDayInto(
        base: File,
        dayLabel: String,
        containerNo: String,
        active: List<ScanRecord>,
        deleted: List<ScanRecord>
    ): Boolean = runCatching {
        val name = safeName(containerNo)
        val activeDir = File(recordDirOf(base), dayLabel)
        val trashDir = File(trashDirOf(base), dayLabel)
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
            if (active.isEmpty()) {
                legacyTrash.delete()
                legacyActive.delete()
            }
        }
        true
    }.getOrElse {
        Log.w(TAG, "syncDay failed: ${it.message}")
        false
    }

    /**
     * v1.10.0：按数据库全量重建记录文件（修「已扫但没落盘」的历史缺口）。
     * 返回写出的文件数。
     */
    fun rebuildFromRecords(records: List<ScanRecord>): Int {
        if (records.isEmpty()) return 0
        val groups = records.groupBy { fmtDate(it.scannedAt) to it.containerNo }
        var files = 0
        groups.forEach { (key, list) ->
            val (day, container) = key
            val active = list.filter { it.deletedAt == null }
            val deleted = list.filter { it.deletedAt != null }
            if (syncDayInto(externalRoot(), day, container, active, deleted)) files += 2
        }
        primaryWritableCache = null
        return files
    }

    /**
     * 把兜底目录里暂存的记录搬回主目录 /sdcard/zhuanggui。
     * 返回搬回的文件数；主目录仍不可写时返回 0（记录保留在暂存目录）。
     */
    fun exportFallbackToPrimary(): Int {
        if (!primaryWritable()) return 0
        val base = fallbackBase()
        if (!base.exists()) return 0
        var count = 0
        base.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(base)
            // rel 以 zhuanggui/ 开头（暂存目录按主目录结构镜像），直接搬到 /sdcard 下
            val target = File(externalRoot(), rel.path)
            runCatching {
                target.parentFile?.mkdirs()
                f.copyTo(target, overwrite = true)
                f.delete()
                count++
            }
        }
        if (count > 0) {
            lastWriteError = null
            primaryWritableCache = null
        }
        return count
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
