package com.zhuanggui.pda.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** 同步调度（v1.8.0）：周期 15 分钟 + 扫码后 5 秒合并触发 + 手动立即同步 */
object SyncScheduler {
    private const val PERIODIC = "sync_periodic"
    private const val ONE_SHOT = "sync_now"

    fun ensurePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** 扫码后触发一次；5 秒内的多次扫码合并为一次请求 */
    fun requestSync(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(5, TimeUnit.SECONDS)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, req)
    }

    /** 手动「立即同步」：不延迟 */
    fun syncNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, req)
    }
}
