# WiFi 同步方案 · 02 PAD 端实施步骤

> 工程：`E:\saoma`（`com.zhuanggui.pda`，Kotlin + Compose + Room v13，minSdk 26 / targetSdk 35）
> 前置：先读 `00-总览` 与 `01-接口契约`

---

## 0. 改动全景（按文件）

### 新增文件

| 文件 | 作用 |
| --- | --- |
| `data/entity/SyncEntities.kt` | `SyncState`（键值）、`SyncInbox`（收到的任务） |
| `data/dao/SyncDaos.kt` | `SyncStateDao`、`SyncInboxDao` |
| `net/SyncDtos.kt` | 传输用 DTO + JSON 互转（用系统自带 `org.json`，**不引 Gson**） |
| `net/SyncClient.kt` | OkHttp 封装：`health()` / `pullTasks()` / `pushScans()` / `ack()` |
| `net/SyncRepository.kt` | 编排：待传查询 → 批量上传 → 回写 `syncedAt`；拉任务 → 落 `sync_inbox` |
| `sync/SyncWorker.kt` | `CoroutineWorker`，一次同步任务 |
| `sync/SyncScheduler.kt` | 入队（周期 15 分钟 + 扫码后一次性） |
| `ui/screen/SyncSettingsScreen.kt` | 配对 / 测试连接 / 立即同步 / 待传条数 |
| `ui/screen/InboxScreen.kt` | 收到的装柜任务列表 → 「装载」 |
| `res/xml/network_security_config.xml` | 放行局域网明文 HTTP |

### 修改文件

| 文件 | 改动 |
| --- | --- |
| `app/build.gradle.kts` | + `okhttp` + `androidx.work:work-runtime-ktx` |
| `data/AppDatabase.kt` | `version = 14`、注册两个新实体、`MIGRATION_13_14`、两个新 DAO |
| `data/dao/LoadDaos.kt` | `ScanRecordDao` 增：取待传 / 回写 `syncedAt`；**软删除与撤回同时清 `syncedAt`** |
| `data/entity/LoadEntities.kt` | `ScanRecord` 增 `uid`、`syncedAt` |
| `ui/AppViewModel.kt` | 注入 `SyncRepository`；扫码入库后触发一次同步 |
| `ui/AppRoot.kt` | 加两个路由（同步设置、收件箱） |
| `ui/screen/HomeScreen.kt` | 加「WiFi 同步」入口 |
| `ui/screen/OutboundSetupScreen.kt` | 「装载收到的任务」按钮 |
| `app/src/main/AndroidManifest.xml` | 权限 + `networkSecurityConfig` |
| `res/values/strings.xml` | 文案 |

---

## 1. 依赖

`app/build.gradle.kts` 的 `dependencies` 里追加（**版本不要往上跳其他库**）：

```kotlin
    // WiFi 同步：仅这两个依赖
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
```

> `org.json` 是 Android 系统自带，不需要依赖。**禁止**为此引入 Gson / Retrofit / ZXing。

---

## 2. Room v13 → v14

### 2.1 实体改动（`data/entity/LoadEntities.kt`）

`ScanRecord` 追加两个字段（其余不动）：

```kotlin
@Entity(
    tableName = "scan_records",
    foreignKeys = [ /* 保持原样 */ ],
    indices = [
        Index("containerId"),
        Index("barcode"),
        Index(value = ["uid"], unique = true),   // 新增：同步幂等键
        Index("syncedAt")                        // 新增：取待传加速
    ]
)
data class ScanRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val containerId: Long,
    val containerNo: String,
    val barcode: String,
    val fullCode: String,
    val result: ScanResult,
    val scannedAt: Date = Date(),
    val deletedAt: Date? = null,
    val operatorId: String = "",
    val uid: String = java.util.UUID.randomUUID().toString(),  // 同步唯一键
    val syncedAt: Date? = null                                 // null = 待上传
)
```

### 2.2 新实体（`data/entity/SyncEntities.kt`）

```kotlin
package com.zhuanggui.pda.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 同步配置与游标：键值对（serverUrl / token / deviceId / lastTaskRev …） */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val k: String,
    val v: String
) {
    companion object {
        const val K_SERVER_URL = "serverUrl"       // http://192.168.1.100:8000
        const val K_TOKEN = "token"
        const val K_DEVICE_ID = "deviceId"         // PDA-01（首次生成后固定）
        const val K_LAST_TASK_REV = "lastTaskRev"  // 已消费到的任务 rev
        const val K_LAST_SYNC_AT = "lastSyncAt"    // 上次成功同步时间（epoch ms）
        const val K_LAST_ERROR = "lastError"       // 上次失败原因（界面展示）
    }
}

/** 电脑下发、尚未「装载」的装柜任务（REPLACE 覆盖同一柜号） */
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
```

### 2.3 迁移（`data/AppDatabase.kt`）

`@Database` 改：

```kotlin
@Database(
    entities = [
        LoadContainer::class, LoadRule::class, ScanRecord::class,
        RuleLibEntry::class, RuleModel::class,
        SyncState::class, SyncInbox::class          // 新增
    ],
    version = 14,
    exportSchema = true
)
```

新增 DAO 抽象方法：

```kotlin
    abstract fun syncStateDao(): SyncStateDao
    abstract fun syncInboxDao(): SyncInboxDao
```

迁移：

```kotlin
/**
 * v13 → v14：新增 WiFi 同步能力
 *  - scan_records 加 uid（幂等键，回填随机 UUID）+ syncedAt（null = 待上传）
 *  - 新表 sync_state（配置与游标）、sync_inbox（收到的装柜任务）
 */
private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE scan_records ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE scan_records ADD COLUMN syncedAt INTEGER")
        // 历史记录回填唯一 uid，之后才能建唯一索引
        db.execSQL("UPDATE scan_records SET uid = lower(hex(randomblob(16))) WHERE uid = ''")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scan_records_uid` ON `scan_records` (`uid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scan_records_syncedAt` ON `scan_records` (`syncedAt`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_state` (" +
                "`k` TEXT NOT NULL, `v` TEXT NOT NULL, PRIMARY KEY(`k`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_inbox` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`containerNo` TEXT NOT NULL, " +
                "`totalQuantity` INTEGER NOT NULL, " +
                "`note` TEXT NOT NULL, " +
                "`rulesJson` TEXT NOT NULL, " +
                "`serverRev` INTEGER NOT NULL, " +
                "`receivedAt` INTEGER NOT NULL, " +
                "`consumedAt` INTEGER)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_inbox_containerNo` " +
                "ON `sync_inbox` (`containerNo`)"
        )
    }
}
```

并把它加进 `ALL_MIGRATIONS` 数组末尾。

> ✅ 验收：`./gradlew :app:assembleDebug` 通过；已有真机数据升级后 `scan_records` 条数不变、`uid` 全部非空且唯一。

### 2.4 DAO 增量（`data/dao/LoadDaos.kt` 的 `ScanRecordDao`）

```kotlin
    // ---------- 同步用（v14） ----------

    /** 待上传：未同步 + 有效记录（软删除单独一轮，见下） */
    @Query("SELECT * FROM scan_records WHERE syncedAt IS NULL ORDER BY scannedAt, id LIMIT :limit")
    suspend fun pendingSync(limit: Int): List<ScanRecord>

    @Query("SELECT COUNT(*) FROM scan_records WHERE syncedAt IS NULL")
    fun observePendingCount(): Flow<Int>

    /** 上传成功回写；传 null 表示"需要重传"（软删除变化时用） */
    @Query("UPDATE scan_records SET syncedAt = :at WHERE uid IN (:uids)")
    suspend fun markSynced(uids: List<String>, at: Date?)

    @Query("UPDATE scan_records SET syncedAt = NULL WHERE id = :id")
    suspend fun resetSynced(id: Long)
```

**⚠️ 必须同步修改现有的软删除 / 撤回方法**（否则删除状态传不到电脑）：

```kotlin
    @Query("UPDATE scan_records SET deletedAt = :at, syncedAt = NULL WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDeleteById(id: Long, at: Date)

    @Query("UPDATE scan_records SET deletedAt = :at, syncedAt = NULL WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun softDeleteIds(ids: List<Long>, at: Date)

    @Query(
        "UPDATE scan_records SET deletedAt = :at, syncedAt = NULL WHERE containerId = :containerId " +
            "AND strftime('%Y-%m-%d', scannedAt / 1000, 'unixepoch', 'localtime') = :dayLabel " +
            "AND deletedAt IS NULL"
    )
    suspend fun softDeleteGroup(containerId: Long, dayLabel: String, at: Date)

    @Query("UPDATE scan_records SET deletedAt = NULL, syncedAt = NULL WHERE id IN (:ids)")
    suspend fun restoreIds(ids: List<Long>)

    @Query("UPDATE scan_records SET deletedAt = NULL, syncedAt = NULL WHERE deletedAt IS NOT NULL")
    suspend fun restoreAll()
```

**⚠️ 清空回收站不要给服务端发"物理删除"**：`emptyTrash()` 保持原样，但建议在删除前把对应 uid 记为 `deletedAt` 上报。若嫌复杂，**P1 阶段可先不管回收站清空**，只同步"软删除"。

---

## 3. 网络层

### 3.1 `net/SyncDtos.kt`（用 org.json 手搓，零依赖）

```kotlin
package com.zhuanggui.pda.net

import com.zhuanggui.pda.data.entity.ScanRecord
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
    timeZone = TimeZone.getDefault()
}

fun Date.toIso(): String = ISO.format(this)
fun String.toDateOrNull(): Date? = runCatching { ISO.parse(this) }.getOrNull()

/** 上行：单条扫码记录 → JSON */
fun ScanRecord.toUploadJson(): JSONObject = JSONObject().apply {
    put("uid", uid)
    put("containerNo", containerNo)
    put("barcode", barcode)
    put("fullCode", fullCode)
    put("result", result.name)          // 必须与 ScanResult 枚举名一致
    put("scannedAt", scannedAt.toIso())
    put("operatorId", operatorId)
    put("deletedAt", deletedAt?.toIso() ?: JSONObject.NULL)
}

fun buildPushBody(deviceId: String, appVersion: String, rows: List<ScanRecord>): String =
    JSONObject().apply {
        put("deviceId", deviceId)
        put("appVersion", appVersion)
        put("scans", JSONArray().also { arr -> rows.forEach { arr.put(it.toUploadJson()) } })
    }.toString()
```

### 3.2 `net/SyncClient.kt`

```kotlin
package com.zhuanggui.pda.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SyncClient(private val baseUrl: String, private val token: String, private val deviceId: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)     // 局域网，失败要快
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    /** 异常统一抛 SyncException(code, message)，由上层决定是否重试 */
    class SyncException(val code: Int, message: String) : Exception(message)

    fun health(): JSONObject = get("/api/v1/health", auth = false)

    fun pullTasks(sinceRev: Int, limit: Int = 200): JSONObject =
        get("/api/v1/tasks?since=$sinceRev&limit=$limit")

    fun pushScans(body: String): JSONObject = post("/api/v1/scans", body)

    fun ack(rev: Int, appVersion: String): JSONObject =
        post("/api/v1/ack", JSONObject().put("deviceId", deviceId).put("rev", rev).put("appVersion", appVersion).toString())

    private fun get(path: String, auth: Boolean = true): JSONObject =
        exec(Request.Builder().url(baseUrl + path).apply {
            header("X-Device-Id", deviceId)
            if (auth) header("X-Sync-Token", token)
        }.build())

    private fun post(path: String, body: String): JSONObject =
        exec(
            Request.Builder().url(baseUrl + path)
                .header("X-Sync-Token", token)
                .header("X-Device-Id", deviceId)
                .post(body.toRequestBody(json))
                .build()
        )

    private fun exec(req: Request): JSONObject = http.newCall(req).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            SyncClientException(resp.code, text.take(300)).let { throw it }
        }
        JSONObject(text)
    }
}
```

> 注意：`SyncClient` 每次用它自己的 OkHttp 实例即可（不要与扫码枪 SDK 共用）。**不要在 UI 线程调用**，全部放在 `Dispatchers.IO`。

---

## 4. 编排层 `net/SyncRepository.kt`

```kotlin
class SyncRepository(
    private val db: AppDatabase,
    private val appVersion: String
) {
    /** 读配置；未配对返回 null */
    suspend fun config(): Config? {
        val url = state.getString(SyncState.K_SERVER_URL) ?: return null
        val token = state.getString(SyncState.K_TOKEN) ?: return null
        val deviceId = state.getString(SyncState.K_DEVICE_ID) ?: return null
        return Config(url, token, deviceId)
    }

    data class Config(val baseUrl: String, val token: String, val deviceId: String)

    /** 一次完整同步：先上行（防止堆压），再下行 */
    suspend fun syncOnce(): Result {
        val cfg = config() ?: return Result.NotPaired
        val client = SyncClient(cfg.baseUrl, cfg.token, cfg.deviceId)

        var pushed = 0
        // ---- 上行：循环直到没有待传 ----
        while (true) {
            val batch = db.scanRecordDao().pendingSync(200)
            if (batch.isEmpty()) break
            val resp = client.pushScans(buildPushBody(cfg.deviceId, appVersion, batch))
            val okUids = batch.map { it.uid }          // 简化：200 成功即认为该批有效
            db.scanRecordDao().markSynced(okUids, Date())
            pushed += batch.size
        }

        // ---- 下行 ----
        val since = state.getString(SyncState.K_LAST_TASK_REV)?.toIntOrNull() ?: 0
        val resp = client.pullTasks(since)
        val nextRev = resp.optInt("nextRev", since)
        val tasks = resp.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until tasks.length()) {
            val t = tasks.getJSONObject(i)
            val no = t.getString("containerNo")
            if (t.optBoolean("deleted", false)) {
                db.syncInboxDao().deleteByNo(no)
            } else {
                db.syncInboxDao().upsert(
                    SyncInbox(
                        containerNo = no,
                        totalQuantity = t.optInt("totalQuantity", 0),
                        note = t.optString("note", ""),
                        rulesJson = t.optJSONArray("rules")?.toString() ?: "[]",
                        serverRev = t.optInt("rev", 0)
                    )
                )
            }
        }
        state.put(SyncState.K_LAST_TASK_REV, nextRev.toString())
        client.ack(nextRev, appVersion)

        state.put(SyncState.K_LAST_SYNC_AT, System.currentTimeMillis().toString())
        state.put(SyncState.K_LAST_ERROR, "")
        return Result.Ok(pushed, tasks.length())
    }

    sealed class Result {
        data class Ok(val pushed: Int, val pulled: Int) : Result()
        data object NotPaired : Result()
    }
}
```

> **简化点**：上面把"整批 200 条都标记成功"当作成功。若服务端返回了 `rejected` 明细，应对这些 uid 也标记已同步（避免死循环），并把原因写进 `sync_state.lastError`。**这一点必须实现**，否则一条坏数据会永久卡住队列。

---

## 5. 后台调度

### 5.1 `sync/SyncWorker.kt`

```kotlin
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as ZhuangGuiApp).syncRepository
        return try {
            when (val r = repo.syncOnce()) {
                is SyncRepository.Result.NotPaired -> Result.success()   // 没配对，静默跳过
                is SyncRepository.Result.Ok -> Result.success()
            }
        } catch (e: SyncClientException) {
            when {
                e.code == 401 -> Result.failure()                       // 配对失效，别重试
                e.code in 400..499 -> Result.success()                   // 客户端错误，重试无用
                else -> Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
```

### 5.2 `sync/SyncScheduler.kt`

```kotlin
object SyncScheduler {
    private const val PERIODIC = "sync_periodic"
    private const val ONE_SHOT = "sync_now"

    /** 周期性（15 分钟，WorkManager 最小间隔） */
    fun ensurePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** 扫码后触发一次（合并 5 秒内的多次扫码，避免每码一次请求） */
    fun requestSync(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(5, TimeUnit.SECONDS)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, req)
    }
}
```

在 `ZhuangGuiApp.onCreate()` 里调 `SyncScheduler.ensurePeriodic(this)`。

---

## 6. UI

### 6.1 `SyncSettingsScreen`

| 区块 | 内容 |
| --- | --- |
| 状态 | 未配对 / 已连接（显示 `serverUrl` + 服务端时间）/ 配对失效（红字） |
| 待传 | `observePendingCount()` 实时显示「待传 N 条」 |
| 配对 | 「扫描配对码」（用现有扫码枪）/「手动输入」（`ip:port#token`） |
| 操作 | 「测试连接」→ `health()`；「立即同步」→ 手动触发 `SyncWorker` |
| 上次同步 | `lastSyncAt` 人性化显示；失败时显示 `lastError`（可长按复制） |
| 解除配对 | 清空 `serverUrl` / `token`（**不清** `deviceId`） |

### 6.2 `InboxScreen` + 装载

- 列表显示：柜号、计划数量、规则条数、收到时间、`note`
- 点「装载」→ 解析 `rulesJson` → 跳到设置页并**预填**柜号 / 计划数量 / 规则（**不直接建库**）
- 装载完成后写 `consumedAt`
- 已存在同柜号且有未删记录时，按现有"继续装柜"语义处理（不要覆盖）

### 6.3 扫码后触发同步

在现有扫码入库成功的位置（`AppViewModel` 里插入 `scan_records` 之后）加一行：

```kotlin
SyncScheduler.requestSync(getApplication())
```

> 不改变任何扫码结果判定逻辑，只是"顺手敲一下同步"。

---

## 7. Manifest 与网络配置

`AndroidManifest.xml`：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<!-- 仅 P3 自动发现（mDNS）才需要 -->
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

<application
    android:networkSecurityConfig="@xml/network_security_config"
    ... >
```

`res/xml/network_security_config.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- 内部局域网工具：允许明文 HTTP；如需更严可改为 domain-config 绑定固定 IP -->
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

> ⚠️ targetSdk 35 下**不加这个文件就完全连不上**（明文被系统拦截），且报错很难看出是它。这是本方案第一大坑。

---

## 8. 设置项存储

用新表 `sync_state`（不要塞 `SharedPreferences`，方便和 DB 一起备份/恢复）。
`deviceId` 首次生成：`"PDA-" + 设备序列号后 6 位`（或随机 6 位数字），生成后**不再变**。

---

## 9. PAD 端验收自测清单

| # | 场景 | 预期 |
| --- | --- | --- |
| 1 | 未配对时点「立即同步」 | 提示未配对，不崩 |
| 2 | 扫配对码 → 测试连接 | 绿色 + 显示服务端时间 |
| 3 | 断网扫码 5 条 | 记录正常入库，待传显示 5 |
| 4 | 恢复网络 | 待传归 0，PC 端能查到这 5 条 |
| 5 | 同一条反复触发同步 3 次 | PC 端 `duplicated` 增加，总数不翻倍 |
| 6 | 软删除一条 → 同步 | PC 端该条 `deletedAt` 有值（不是消失） |
| 7 | 回收站撤回 → 同步 | PC 端 `deletedAt` 变回 null |
| 8 | PC 下发任务 → 同步 → 装载 | 柜号/计划数/规则（含流水号范围）全部正确 |
| 9 | 升级安装（带老数据） | App 不崩，旧记录 `uid` 已回填，能正常上传 |
| 10 | PDA 时间调错 2 小时 | 上传时间以 PDA 为准（业务要真实扫码时刻），`health` 里提示偏差 |
