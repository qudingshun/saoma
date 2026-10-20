package com.zhuanggui.pda

import android.app.Application
import com.zhuanggui.pda.data.AppDatabase
import com.zhuanggui.pda.net.SyncRepository
import com.zhuanggui.pda.scan.ScannerManager
import com.zhuanggui.pda.sync.SyncScheduler

class ZhuangGuiApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    /** 扫码枪管理器：进程级单例，重复注册也只注册一次广播接收器 */
    val scannerManager: ScannerManager by lazy { ScannerManager(this) }

    /** WiFi 同步（v1.8.0）：局域网 HTTP，纯旁挂，不参与扫码判定 */
    val syncRepository: SyncRepository by lazy {
        SyncRepository(this, database, BuildConfig.VERSION_NAME)
    }

    override fun onCreate() {
        super.onCreate()
        // 周期同步（15 分钟）；未配对时 Worker 会静默跳过
        SyncScheduler.ensurePeriodic(this)
    }
}
