package com.zhuanggui.pda

import android.app.Application
import android.util.Log
import com.zhuanggui.pda.data.AppDatabase
import com.zhuanggui.pda.export.FileExporter
import com.zhuanggui.pda.log.OpLog
import com.zhuanggui.pda.net.SyncRepository
import com.zhuanggui.pda.scan.ScannerManager
import com.zhuanggui.pda.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ZhuangGuiApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    /** 扫码枪管理器：进程级单例，重复注册也只注册一次广播接收器 */
    val scannerManager: ScannerManager by lazy { ScannerManager(this) }

    /** WiFi 同步（v1.8.0）：局域网 HTTP，纯旁挂，不参与扫码判定 */
    val syncRepository: SyncRepository by lazy {
        SyncRepository(this, database, BuildConfig.VERSION_NAME)
    }

    /** v1.10.1：崩溃留痕（写完日志再交给系统处理，崩溃弹窗照旧） */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                OpLog.error(
                    "App 崩溃",
                    "${error.javaClass.simpleName}: ${error.message} @${thread.name}"
                )
                error.stackTrace.take(12).forEach { OpLog.error("崩溃栈", it.toString()) }
                OpLog.flushSync()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // v1.10.1：操作日志（试运行追溯）
        OpLog.init(this)
        installCrashHandler()
        // v1.10.0：记录落盘根目录改为 /sdcard/zhuanggui；旧目录自动迁移
        FileExporter.init(this)
        FileExporter.migrateLegacyRoot()
        OpLog.info(
            "App 启动",
            "版本=${BuildConfig.VERSION_NAME} 存储=${FileExporter.storageStatus()} " +
                    "记录目录=${FileExporter.displayPath()}"
        )
        // v1.10.4：先把旧版六列 TSV 迁成四列（时间/柜号/条码/操作员），
        // 再按数据库全量重建落盘文件（修「库里已扫、文件没落盘」的缺口）。
        appScope.launch {
            runCatching {
                val migrated = FileExporter.migrateRecordFiles()
                if (migrated > 0) {
                    Log.i("ZhuangGuiApp", "migrated legacy record files: $migrated")
                    OpLog.info("迁移记录文件", "旧版 TSV 六列 → 四列：$migrated 个文件")
                }
                val records = database.scanRecordDao().getAllRecords()
                if (records.isNotEmpty()) {
                    val n = FileExporter.rebuildFromRecords(records)
                    Log.i("ZhuangGuiApp", "rebuilt record files: $n (records=${records.size})")
                    OpLog.info("启动重建记录文件", "记录 ${records.size} 条 → 文件 $n 个")
                }
            }.onFailure { Log.w("ZhuangGuiApp", "rebuild files failed: ${it.message}") }
        }
        // 周期同步（15 分钟）；v1.10.0 起 WiFi 同步默认下线（AppConfig.SYNC_ENABLED）
        if (AppConfig.SYNC_ENABLED) {
            SyncScheduler.ensurePeriodic(this)
        }
    }
}
