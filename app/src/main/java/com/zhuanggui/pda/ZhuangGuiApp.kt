package com.zhuanggui.pda

import android.app.Application
import android.util.Log
import com.zhuanggui.pda.data.AppDatabase
import com.zhuanggui.pda.export.FileExporter
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

    override fun onCreate() {
        super.onCreate()
        // v1.10.0：记录落盘根目录改为 /sdcard/zhuanggui；旧目录自动迁移
        FileExporter.init(this)
        FileExporter.migrateLegacyRoot()
        // 按数据库全量重建落盘文件（修「库里已扫、文件没落盘」的缺口）
        appScope.launch {
            runCatching {
                val records = database.scanRecordDao().getAllRecords()
                if (records.isNotEmpty()) {
                    val n = FileExporter.rebuildFromRecords(records)
                    Log.i("ZhuangGuiApp", "rebuilt record files: $n (records=${records.size})")
                }
            }.onFailure { Log.w("ZhuangGuiApp", "rebuild files failed: ${it.message}") }
        }
        // 周期同步（15 分钟）；v1.10.0 起 WiFi 同步默认下线（AppConfig.SYNC_ENABLED）
        if (AppConfig.SYNC_ENABLED) {
            SyncScheduler.ensurePeriodic(this)
        }
    }
}
