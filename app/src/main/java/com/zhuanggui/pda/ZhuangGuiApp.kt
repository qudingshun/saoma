package com.zhuanggui.pda

import android.app.Application
import com.zhuanggui.pda.data.AppDatabase
import com.zhuanggui.pda.scan.ScannerManager

class ZhuangGuiApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    /** 扫码管理器：进程级单例（配合注册防重，结构上杜绝多实例各注册一遍广播接收器） */
    val scannerManager: ScannerManager by lazy { ScannerManager(this) }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
    }

    companion object {
        lateinit var INSTANCE: ZhuangGuiApp
            private set
    }
}
