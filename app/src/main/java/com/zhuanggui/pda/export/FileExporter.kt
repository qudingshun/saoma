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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 装柜记录自动落盘：
 *
 *   /sdcard/ZhuangGui/装柜记录/yyyy-MM-dd/柜号.csv
 *
 * - 每扫一码调用 append() 增量写一行（不重写全文件）
 * - 文件不存在时先写表头
 * - 编码 UTF-8 带 BOM（Excel 打开中文不乱码）
 *
 * 权限：Android 11+ 需要“所有文件访问”（PDA 离线自用应用常规做法），
 * 低于 Android 11 用 WRITE_EXTERNAL_STORAGE。
 */
object FileExporter {

    private const val ROOT_DIR = "ZhuangGui/装柜记录"
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private const val HEADER = "时间,柜号,条码,完整码,结果\n"

    /** 根目录（PDA 文件管理器里直接可找） */
    fun rootDir(): File = File(Environment.getExternalStorageDirectory(), ROOT_DIR)

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

    /**
     * 增量追加一条记录。返回 true=写入成功。
     * 失败不抛异常（无权限/存储故障时由调用方提示）。
     */
    fun append(record: ScanRecord): Boolean = runCatching {
        val day = dayFormat.format(record.scannedAt)
        val dir = File(rootDir(), day)
        if (!dir.exists() && !dir.mkdirs()) return false
        val safeName = record.containerNo.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val file = File(dir, "$safeName.csv")
        if (!file.exists()) {
            file.outputStream().use { it.write("\uFEFF$HEADER".toByteArray(Charsets.UTF_8)) }
        }
        val line = buildString {
            append(timeFormat.format(record.scannedAt))
            append(','); append(record.containerNo)
            append(','); append(record.barcode)
            append(','); append(record.fullCode)
            append(','); append(resultText(record.result))
            append('\n')
        }
        FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        true
    }.getOrDefault(false)

    private fun resultText(r: ScanResult): String = when (r) {
        ScanResult.OK -> "正常"
        ScanResult.NOT_IN_LIST -> "不在清单"
        ScanResult.OVER_LIMIT -> "超量"
        ScanResult.SERIAL_MISMATCH -> "串号"
        ScanResult.DUPLICATE -> "重复"
    }
}
