package com.zhuanggui.pda

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

/**
 * 保存根目录配置（v1.13.0）。
 *
 * 默认根目录仍是 `/sdcard/zhuanggui`（与旧版一致）；现场可在「保存目录」里改成
 * 任意可写目录。改目录后，以下内容统一放在新根目录下，结构不变：
 *
 *   <root>/装柜记录/yyyy-MM-dd/柜号.tsv
 *   <root>/回收站/yyyy-MM-dd/柜号.tsv
 *   <root>/规则库/规则库_yyyyMMdd_HHmmss.tsv
 *   <root>/日志/操作日志_yyyyMMdd_HHmmss.txt
 *   <root>/装柜完成汇总.tsv
 *
 * 说明：
 *  - 配置存在 SharedPreferences（storage_prefs/root_path），重启/升级不丢；
 *  - 支持输入 `/sdcard/xxx`，内部会换算成真实的 `/storage/emulated/0/xxx`；
 *  - 未配置 = 默认目录，不写死路径，方便以后调整默认值。
 */
object StorageConfig {

    private const val PREFS = "storage_prefs"
    private const val KEY_ROOT = "root_path"

    /** 默认根目录名（根目录下的 zhuanggui 文件夹） */
    const val DEFAULT_DIR_NAME = "zhuanggui"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }

    /** 系统外部存储根（通常是 /storage/emulated/0） */
    fun externalRoot(): File = Environment.getExternalStorageDirectory()

    /** 默认保存根目录 */
    fun defaultRoot(): File = File(externalRoot(), DEFAULT_DIR_NAME)

    /** 当前保存根目录（用户配置优先，否则默认） */
    fun rootDir(): File {
        val raw = prefs?.getString(KEY_ROOT, null)?.trim().orEmpty()
        return if (raw.isEmpty()) defaultRoot() else normalize(raw)
    }

    /** 是否使用自定义目录 */
    fun isCustom(): Boolean = prefs?.getString(KEY_ROOT, null)?.isNotBlank() == true

    /**
     * 把用户输入的路径规范化：
     *  - `/sdcard`、`/sdcard/xxx` → 真实外部存储路径
     *  - 去掉末尾的 `/`、`\`
     *  - 其他路径原样使用（绝对路径）
     */
    fun normalize(path: String): File {
        val raw = path.trim().trimEnd('/', '\\')
        val ext = externalRoot().absolutePath
        val absolute = when {
            raw.isEmpty() -> ext
            raw.equals("/sdcard", ignoreCase = true) -> ext
            raw.startsWith("/sdcard/", ignoreCase = true) -> ext + raw.removePrefix("/sdcard")
            else -> raw
        }
        return File(absolute)
    }

    /** 保存配置；[path] 为空 = 恢复默认目录。返回生效后的根目录。 */
    fun setRoot(context: Context, path: String?): File {
        val p = prefs(context)
        if (path.isNullOrBlank()) {
            p.edit().remove(KEY_ROOT).apply()
        } else {
            p.edit().putString(KEY_ROOT, normalize(path).absolutePath).apply()
        }
        return rootDir()
    }

    /** 文件管理器里习惯看的 `/sdcard/...` 展示路径 */
    fun pretty(file: File): String {
        val ext = externalRoot().absolutePath
        val abs = file.absolutePath
        return when {
            abs.equals(ext, ignoreCase = true) -> "/sdcard"
            abs.startsWith("$ext/", ignoreCase = true) -> "/sdcard/" + abs.substring(ext.length + 1)
            else -> abs
        }
    }

    fun displayPath(): String = pretty(rootDir())
}
