package com.zhuanggui.pda

import android.app.Application
import com.zhuanggui.pda.data.AppDatabase
import com.zhuanggui.pda.data.WorkOrderRepository

class ZhuangGuiApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: WorkOrderRepository by lazy { WorkOrderRepository(database) }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
    }

    companion object {
        lateinit var INSTANCE: ZhuangGuiApp
            private set
    }
}
