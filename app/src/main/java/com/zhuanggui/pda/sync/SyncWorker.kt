package com.zhuanggui.pda.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhuanggui.pda.ZhuangGuiApp
import com.zhuanggui.pda.net.SyncClientException
import com.zhuanggui.pda.net.SyncRepository

/** 一次同步任务（v1.8.0）：成功/未配对 → success；网络/5xx → retry；401/4xx → 不重试 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ZhuangGuiApp
        return try {
            when (app.syncRepository.syncOnce()) {
                is SyncRepository.Result.NotPaired -> Result.success()
                is SyncRepository.Result.Ok -> Result.success()
            }
        } catch (e: SyncClientException) {
            when {
                e.code == 401 -> Result.failure()        // 配对失效，等人工重新配对
                e.code in 400..499 -> Result.success()   // 客户端错误，重试无用
                else -> Result.retry()
            }
        } catch (t: Throwable) {
            Result.retry()
        }
    }
}
