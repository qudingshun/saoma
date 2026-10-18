package com.zhuanggui.pda

import android.app.Application
import com.zhuanggui.pda.data.AppDatabase

class ZhuangGuiApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
    }

    companion object {
        lateinit var INSTANCE: ZhuangGuiApp
            private set
    }
}
