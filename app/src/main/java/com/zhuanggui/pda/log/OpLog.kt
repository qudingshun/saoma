package com.zhuanggui.pda.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 现场操作日志（v1.10.1）：给试运行阶段留可追溯的痕迹。
 *
 * - 落盘位置：App 内部目录 `files/logs/oplog_yyyyMMdd.log`（卸载才消失，永远可写，不依赖外部存储权限）
 * - 导出：设置页/日志页可一键导出到 `/sdcard/zhuanggui/日志/操作日志_yyyyMMdd_HHmmss.txt`（U 盘直接拷）
 * - 写入：后台单线程顺序写，不阻塞扫码；崩溃时用 flushSync 同步落盘最后几行
 * - 容量：单文件 2 MB 轮转（保留 oplog_yyyyMMdd.log.1），每天一个文件
 */
object OpLog {

    private const val TAG = "OpLog"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val READ_TAIL_BYTES = 256 * 1024

    enum class Level { INFO, WARN, ERROR }

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "oplog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val ready = AtomicBoolean(false)
    private var dir: File? = null
    private val timeFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
    }
    private val dayFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd", Locale.CHINA)
    }
    private val stampFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
    }

    fun init(context: Context) {
        if (ready.get()) return
        val d = File(context.filesDir, "logs")
        d.mkdirs()
        dir = d
        ready.set(true)
    }

    private fun currentFile(): File? {
        val d = dir ?: return null
        return File(d, "oplog_${dayFmt.get().format(Date())}.log")
    }

    /** 记一条日志（异步写，不阻塞调用方） */
    fun log(level: Level, event: String, detail: String = "") {
        val line = buildString {
            append(timeFmt.get().format(Date()))
            append(" [").append(level.name).append("] ")
            append(event)
            if (detail.isNotEmpty()) append(" | ").append(detail.replace('\n', ' '))
        }
        Log.i(TAG, line)
        if (!ready.get()) return
        io.execute {
            runCatching {
                val f = currentFile() ?: return@runCatching
                if (f.exists() && f.length() > MAX_FILE_BYTES) {
                    val bak = File(f.parentFile, f.name + ".1")
                    bak.delete()
                    f.renameTo(bak)
                }
                f.appendText(line + "\n", Charsets.UTF_8)
            }
        }
    }

    fun info(event: String, detail: String = "") = log(Level.INFO, event, detail)
    fun warn(event: String, detail: String = "") = log(Level.WARN, event, detail)
    fun error(event: String, detail: String = "") = log(Level.ERROR, event, detail)

    /** 立即把待写内容落盘（崩溃前调用） */
    fun flushSync() {
        runCatching {
            val f = currentFile() ?: return
            f.appendText("", Charsets.UTF_8)
        }
    }

    /** 读取最近 n 条（从当天日志文件尾部读；跨天按修改时间倒序取最近的文件） */
    fun recent(context: Context, n: Int = 500): List<String> {
        val d = File(context.filesDir, "logs")
        if (!d.exists()) return emptyList()
        val files = d.listFiles { f -> f.isFile && f.name.startsWith("oplog_") }
            ?.sortedByDescending { it.lastModified() }
            ?: return emptyList()
        val out = ArrayList<String>()
        for (f in files) {
            if (out.size >= n) break
            val text = runCatching {
                val raf = java.io.RandomAccessFile(f, "r")
                val len = raf.length()
                val start = maxOf(0L, len - READ_TAIL_BYTES)
                raf.seek(start)
                val bytes = ByteArray((len - start).toInt())
                raf.readFully(bytes)
                raf.close()
                String(bytes, Charsets.UTF_8)
            }.getOrDefault("")
            val lines = text.split('\n').filter { it.isNotBlank() }
            for (i in lines.indices.reversed()) {
                out.add(lines[i])
                if (out.size >= n) break
            }
        }
        return out
    }

    /** 导出到 /sdcard/zhuanggui/日志/；返回导出文件（或 null） */
    fun export(context: Context, targetDir: File): File? = runCatching {
        targetDir.mkdirs()
        val out = File(targetDir, "操作日志_${stampFmt.get().format(Date())}.txt")
        val sb = StringBuilder()
        sb.append("成品出库 操作日志（试运行追溯用）\n")
        sb.append("设备：").append(android.os.Build.MODEL).append("  导出时间：")
            .append(timeFmt.get().format(Date())).append("\n")
        sb.append("-".repeat(60)).append("\n")
        val d = File(context.filesDir, "logs")
        d.listFiles { f -> f.isFile && f.name.startsWith("oplog_") }
            ?.sortedBy { it.name }
            ?.forEach { f ->
                sb.append("### ").append(f.name).append("\n")
                sb.append(runCatching { f.readText(Charsets.UTF_8) }.getOrDefault(""))
                sb.append("\n")
            }
        out.writeText(sb.toString(), Charsets.UTF_8)
        out
    }.getOrNull()

    /** 清空日志 */
    fun clear(context: Context) {
        val d = File(context.filesDir, "logs")
        d.listFiles()?.forEach { it.delete() }
    }
}
