package com.zhuanggui.pda.net

import android.content.Context
import android.provider.Settings
import androidx.room.withTransaction
import com.zhuanggui.pda.data.AppDatabase
import com.zhuanggui.pda.data.entity.RuleLibEntry
import com.zhuanggui.pda.data.entity.RuleModel
import com.zhuanggui.pda.data.entity.SyncInbox
import com.zhuanggui.pda.data.entity.SyncState
import com.zhuanggui.pda.export.RuleExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.UUID

/**
 * 同步编排（v1.8.0）：
 *  1) 上行：取 syncedAt IS NULL 的扫码记录，按 200 条一批 POST /scans，成功回写 syncedAt
 *  2) 下行：GET /tasks?since=lastTaskRev → 落 sync_inbox（P2 使用），POST /ack
 *  - 幂等：服务端按 uid 去重；重复提交只会增加 duplicated
 *  - rejected 的 uid 也回写 syncedAt，避免一条坏数据永久卡住队列
 */
class SyncRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val appVersion: String
) {

    data class Config(val baseUrl: String, val token: String, val deviceId: String)

    sealed class Result {
        data class Ok(val pushed: Int, val duplicated: Int, val pulled: Int, val rejected: Int) : Result()
        data object NotPaired : Result()
    }

    /** 规则库下载结果：snapshot = 电脑端版本；backup = 覆盖前的本机备份（空库时 null） */
    data class RuleLibDownloadResult(
        val snapshot: RuleLibSnapshot,
        val backup: File?,
        val modelCount: Int,
        val ruleCount: Int
    )

    private val stateDao get() = db.syncStateDao()
    private val recordDao get() = db.scanRecordDao()
    private val inboxDao get() = db.syncInboxDao()
    private val ruleLibDao get() = db.ruleLibDao()
    private val ruleModelDao get() = db.ruleModelDao()

    fun observePendingCount(): Flow<Int> = recordDao.observePendingCount()

    suspend fun config(): Config? {
        val url = stateDao.get(SyncState.K_SERVER_URL)?.takeIf { it.isNotBlank() } ?: return null
        val token = stateDao.get(SyncState.K_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        val deviceId = ensureDeviceId()
        return Config(url, token, deviceId)
    }

    suspend fun ensureDeviceId(): String {
        stateDao.get(SyncState.K_DEVICE_ID)?.takeIf { it.isNotBlank() }?.let { return it }
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val suffix = androidId.takeLast(6).ifBlank { UUID.randomUUID().toString().take(6) }.uppercase()
        val id = "PDA-$suffix"
        stateDao.put(SyncState(SyncState.K_DEVICE_ID, id))
        return id
    }

    /** 配对：先验证 /health，再用带 token 的接口确认鉴权，成功后再保存地址与 token（deviceId 不变） */
    suspend fun pair(info: PairingInfo): Config = withContext(Dispatchers.IO) {
        val deviceId = ensureDeviceId()
        val client = SyncClient(info.baseUrl, info.token, deviceId)
        val health = client.health()
        // /health 免鉴权，这里再拉 1 条任务验证 token（401 会在配对阶段直接提示）
        client.pullTasks(0, 1)
        stateDao.put(SyncState(SyncState.K_SERVER_URL, info.baseUrl))
        stateDao.put(SyncState(SyncState.K_TOKEN, info.token))
        stateDao.put(SyncState(SyncState.K_SERVER_TIME, health.optString("time")))
        stateDao.put(SyncState(SyncState.K_LAST_ERROR, ""))
        Config(info.baseUrl, info.token, deviceId)
    }

    /** 解除配对：只清地址与 token，保留 deviceId */
    suspend fun unpair() = withContext(Dispatchers.IO) {
        stateDao.delete(SyncState.K_SERVER_URL)
        stateDao.delete(SyncState.K_TOKEN)
        stateDao.delete(SyncState.K_LAST_TASK_REV)
        stateDao.delete(SyncState.K_LAST_SYNC_AT)
        stateDao.put(SyncState(SyncState.K_LAST_ERROR, ""))
        stateDao.put(SyncState(SyncState.K_SERVER_TIME, ""))
        // 换服务器后 rev 不可比，清掉规则库同步游标（本机规则库内容保留）
        stateDao.delete(SyncState.K_RULELIB_SYNCED_REV)
        stateDao.delete(SyncState.K_RULELIB_SERVER_REV)
        stateDao.delete(SyncState.K_RULELIB_UPDATED_AT)
        stateDao.delete(SyncState.K_RULELIB_UPDATED_BY)
    }

    /** 测试连接（已配对时） */
    suspend fun health(): JSONObject = withContext(Dispatchers.IO) {
        val cfg = config() ?: throw SyncClientException(0, "尚未配对")
        val h = SyncClient(cfg.baseUrl, cfg.token, cfg.deviceId).health()
        stateDao.put(SyncState(SyncState.K_SERVER_TIME, h.optString("time")))
        h
    }

    // ---------- 规则库同步（v1.9.0） ----------

    /** 查电脑端规则库快照（只读，不改本机规则库） */
    suspend fun fetchRuleLibrary(): RuleLibSnapshot = withContext(Dispatchers.IO) {
        val cfg = config() ?: throw SyncClientException(0, "尚未配对")
        val snap = RuleLibSnapshot.fromJson(SyncClient(cfg.baseUrl, cfg.token, cfg.deviceId).getRuleLibrary())
        stateDao.put(SyncState(SyncState.K_RULELIB_SERVER_REV, snap.rev.toString()))
        stateDao.put(SyncState(SyncState.K_RULELIB_UPDATED_AT, snap.updatedAt))
        stateDao.put(SyncState(SyncState.K_RULELIB_UPDATED_BY, snap.updatedBy))
        snap
    }

    /** 上传本机规则库 → 电脑端（整库替换，rev+1；其他 PDA 可下载） */
    suspend fun uploadRuleLibrary(): RuleLibSnapshot = withContext(Dispatchers.IO) {
        val cfg = config() ?: throw SyncClientException(0, "尚未配对")
        val modelRows = ruleModelDao.getAll()
        val modelDefaults = modelRows.associate { it.name to it.defaultQuantity }
        val models = modelRows.map { it.name }
        val rules = ruleLibDao.getAll().map {
            RuleLibRule(
                prefix = it.prefix,
                name = it.name,
                allowDuplicate = it.allowDuplicate,
                countEnabled = it.countEnabled,
                checkHistory = it.checkHistory,
                ignoreRepeat = it.ignoreRepeat,
                defaultQuantity = modelDefaults[it.name] ?: 0
            )
        }
        if (models.isEmpty() && rules.isEmpty()) {
            throw SyncClientException(0, "本机规则库为空，没有可上传的规则")
        }
        val local = RuleLibSnapshot(
            hasData = true, rev = 0, models = models, rules = rules,
            updatedAt = "", updatedBy = cfg.deviceId
        )
        val snap = RuleLibSnapshot.fromJson(
            SyncClient(cfg.baseUrl, cfg.token, cfg.deviceId)
                .pushRuleLibrary(local.toUploadBody(cfg.deviceId))
        )
        stateDao.put(SyncState(SyncState.K_RULELIB_SYNCED_REV, snap.rev.toString()))
        stateDao.put(SyncState(SyncState.K_RULELIB_SERVER_REV, snap.rev.toString()))
        stateDao.put(SyncState(SyncState.K_RULELIB_UPDATED_AT, snap.updatedAt))
        stateDao.put(SyncState(SyncState.K_RULELIB_UPDATED_BY, snap.updatedBy))
        snap
    }

    /**
     * 下载电脑端规则库并整库覆盖本机：
     *  1) 先备份本机规则库（XLSX，带日期；备份失败则不改库）
     *  2) 事务内清空 rule_library / rule_models，再写入电脑端快照
     *  3) 记录已同步 rev
     * 已装柜的柜子使用各自 load_rules 快照，不受影响。
     */
    suspend fun downloadRuleLibrary(): RuleLibDownloadResult = withContext(Dispatchers.IO) {
        val cfg = config() ?: throw SyncClientException(0, "尚未配对")
        val snap = RuleLibSnapshot.fromJson(
            SyncClient(cfg.baseUrl, cfg.token, cfg.deviceId).getRuleLibrary()
        )
        if (!snap.hasData || (snap.models.isEmpty() && snap.rules.isEmpty())) {
            throw SyncClientException(0, "电脑端还没有规则库：请先在一台 PDA 上传本机规则库")
        }
        val oldModels = ruleModelDao.getAll()
        val oldRules = ruleLibDao.getAll()
        val backup = RuleExporter.backupBeforeSync(oldModels, oldRules)   // 失败会抛出，库不变
        val names = (snap.models + snap.rules.map { it.name })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
        // v16：电脑端快照仍按规则携带默认数量（兼容旧服务器）；下载后归并到型号级。
        val modelDefaults = snap.rules
            .filter { it.name.isNotBlank() && it.defaultQuantity > 0 }
            .groupBy { it.name }
            .mapValues { (_, rows) -> rows.first().defaultQuantity }
        val now = Date()
        db.withTransaction {
            ruleLibDao.deleteAll()
            ruleModelDao.deleteAll()
            names.forEach {
                ruleModelDao.insert(
                    RuleModel(
                        name = it,
                        defaultQuantity = modelDefaults[it] ?: 0,
                        updatedAt = now
                    )
                )
            }
            snap.rules.forEach { r ->
                ruleLibDao.insert(
                    RuleLibEntry(
                        prefix = r.prefix,
                        name = r.name,
                        allowDuplicate = r.allowDuplicate,
                        countEnabled = r.countEnabled,
                        checkHistory = r.checkHistory,
                        ignoreRepeat = r.ignoreRepeat,
                        updatedAt = now
                    )
                )
            }
        }
        stateDao.put(SyncState(SyncState.K_RULELIB_SYNCED_REV, snap.rev.toString()))
        stateDao.put(SyncState(SyncState.K_RULELIB_SERVER_REV, snap.rev.toString()))
        stateDao.put(SyncState(SyncState.K_RULELIB_UPDATED_AT, snap.updatedAt))
        stateDao.put(SyncState(SyncState.K_RULELIB_UPDATED_BY, snap.updatedBy))
        RuleLibDownloadResult(snap, backup, names.size, snap.rules.size)
    }

    /** 一次完整同步：先上行，再下行；异常写 lastError 后抛出 */
    suspend fun syncOnce(): Result = withContext(Dispatchers.IO) {
        val cfg = config() ?: return@withContext Result.NotPaired
        val client = SyncClient(cfg.baseUrl, cfg.token, cfg.deviceId)
        try {
            var pushed = 0
            var duplicated = 0
            var rejectedCount = 0
            val rejectedReasons = mutableListOf<String>()

            while (true) {
                val batch = recordDao.pendingSync(200)
                if (batch.isEmpty()) break
                val resp = client.pushScans(buildPushBody(cfg.deviceId, appVersion, batch))
                val accepted = resp.optInt("accepted", 0)
                val dup = resp.optInt("duplicated", 0)
                pushed += accepted
                duplicated += dup

                val rejected = resp.optJSONArray("rejected") ?: JSONArray()
                val rejectedUids = mutableSetOf<String>()
                for (i in 0 until rejected.length()) {
                    val o = rejected.optJSONObject(i) ?: continue
                    val uid = o.optString("uid")
                    val reason = o.optString("reason")
                    if (uid.isNotBlank()) rejectedUids.add(uid)
                    if (reason.isNotBlank()) rejectedReasons.add(reason)
                }
                rejectedCount += rejectedUids.size
                // 成功 + 被拒 都标记已传，避免坏数据死循环
                recordDao.markSynced(batch.map { it.uid }, Date())
            }

            // 下行：任务增量 → sync_inbox（不直接建柜）
            var pulled = 0
            val since = stateDao.get(SyncState.K_LAST_TASK_REV)?.toIntOrNull() ?: 0
            val tasksResp = client.pullTasks(since)
            val nextRev = tasksResp.optInt("nextRev", since)
            val tasks = tasksResp.optJSONArray("tasks") ?: JSONArray()
            for (i in 0 until tasks.length()) {
                val t = tasks.optJSONObject(i) ?: continue
                val no = t.optString("containerNo").trim()
                if (no.isBlank()) continue
                if (t.optBoolean("deleted", false)) {
                    inboxDao.deleteByNo(no)
                } else {
                    inboxDao.upsert(
                        SyncInbox(
                            containerNo = no,
                            totalQuantity = t.optInt("totalQuantity", 0),
                            note = t.optString("note", ""),
                            rulesJson = t.optJSONArray("rules")?.toString() ?: "[]",
                            serverRev = t.optInt("rev", 0)
                        )
                    )
                }
                pulled++
            }
            stateDao.put(SyncState(SyncState.K_LAST_TASK_REV, nextRev.toString()))
            client.ack(nextRev, appVersion)

            stateDao.put(SyncState(SyncState.K_LAST_SYNC_AT, System.currentTimeMillis().toString()))
            val warn = if (rejectedReasons.isEmpty()) "" else
                "有 ${rejectedReasons.size} 条被服务端拒绝：${rejectedReasons.take(2).joinToString("；")}"
            stateDao.put(SyncState(SyncState.K_LAST_ERROR, warn))
            Result.Ok(pushed, duplicated, pulled, rejectedCount)
        } catch (e: SyncClientException) {
            val msg = when (e.code) {
                0 -> "网络连接失败：${e.message}"
                401 -> "配对失效（token 不匹配），请重新扫描配对码"
                else -> "同步失败（${e.code}）：${e.message}"
            }
            stateDao.put(SyncState(SyncState.K_LAST_ERROR, msg))
            throw e
        } catch (t: Throwable) {
            stateDao.put(SyncState(SyncState.K_LAST_ERROR, "同步失败：${t.message ?: t.javaClass.simpleName}"))
            throw SyncClientException(0, t.message ?: "同步失败")
        }
    }

    /** 待传条数（界面初始化用） */
    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        recordDao.countPending()
    }
}
