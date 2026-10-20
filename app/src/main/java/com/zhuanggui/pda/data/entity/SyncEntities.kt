package com.zhuanggui.pda.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/** 同步配置与游标：键值对（serverUrl / token / deviceId / lastTaskRev …），v14 */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val k: String,
    val v: String
) {
    companion object {
        const val K_SERVER_URL = "serverUrl"       // http://192.168.1.100:8000
        const val K_TOKEN = "token"
        const val K_DEVICE_ID = "deviceId"         // PDA-XXXXXX（首次生成后固定）
        const val K_LAST_TASK_REV = "lastTaskRev"  // 已消费到的任务 rev
        const val K_LAST_SYNC_AT = "lastSyncAt"    // 上次成功同步时间（epoch ms）
        const val K_LAST_ERROR = "lastError"       // 上次失败原因（界面展示）
        const val K_SERVER_TIME = "serverTime"     // health 返回的服务端时间（界面展示）
        // 规则库同步（v1.9.0）
        const val K_RULELIB_SYNCED_REV = "rulelibSyncedRev"   // 本机已下载/上传的 rev
        const val K_RULELIB_SERVER_REV = "rulelibServerRev"   // 最近一次查到的电脑端 rev
        const val K_RULELIB_UPDATED_AT = "rulelibUpdatedAt"   // 电脑端快照更新时间
        const val K_RULELIB_UPDATED_BY = "rulelibUpdatedBy"   // 电脑端快照来源设备
    }
}

/** 电脑下发、尚未「装载」的装柜任务（同一柜号 REPLACE 覆盖），v14 */
@Entity(
    tableName = "sync_inbox",
    indices = [Index(value = ["containerNo"], unique = true)]
)
data class SyncInbox(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val containerNo: String,
    val totalQuantity: Int,
    val note: String = "",
    val rulesJson: String,          // 原样存 JSON，装载时再解析
    val serverRev: Int,
    val receivedAt: Date = Date(),
    val consumedAt: Date? = null    // 已装载时间；null = 未装载
)
