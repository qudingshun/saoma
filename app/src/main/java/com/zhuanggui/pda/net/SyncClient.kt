package com.zhuanggui.pda.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 网络/服务端错误：code = HTTP 状态码（0 = 网络异常），message 用于界面提示 */
class SyncClientException(val code: Int, message: String) : Exception(message)

/**
 * 同步 HTTP 客户端（v1.8.0）：
 *  - 局域网明文 HTTP；连接 3s / 读 10s / 写 15s
 *  - 统一头：X-Device-Id、X-Sync-Token（/health 免 token）
 *  - 调用方必须放在 Dispatchers.IO
 */
class SyncClient(baseUrl: String, private val token: String, private val deviceId: String) {

    private val base = baseUrl.trimEnd('/')

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    fun health(): JSONObject = get("/api/v1/health", auth = false)

    fun pullTasks(sinceRev: Int, limit: Int = 200): JSONObject =
        get("/api/v1/tasks?since=$sinceRev&limit=$limit")

    fun pushScans(body: String): JSONObject = post("/api/v1/scans", body)

    /** 规则库：GET 电脑端快照 / POST 上传本机快照（v1.9.0） */
    fun getRuleLibrary(): JSONObject = get("/api/v1/rulelib")

    fun pushRuleLibrary(body: String): JSONObject = post("/api/v1/rulelib", body)

    fun ack(rev: Int, appVersion: String): JSONObject = post(
        "/api/v1/ack",
        JSONObject().put("deviceId", deviceId).put("rev", rev).put("appVersion", appVersion).toString()
    )

    private fun get(path: String, auth: Boolean = true): JSONObject =
        exec(
            Request.Builder().url(base + path).apply {
                header("X-Device-Id", deviceId)
                if (auth) header("X-Sync-Token", token)
            }.build()
        )

    private fun post(path: String, body: String): JSONObject =
        exec(
            Request.Builder().url(base + path)
                .header("X-Sync-Token", token)
                .header("X-Device-Id", deviceId)
                .post(body.toRequestBody(json))
                .build()
        )

    private fun exec(req: Request): JSONObject {
        val resp = try {
            http.newCall(req).execute()
        } catch (t: Throwable) {
            throw SyncClientException(0, t.message ?: "网络连接失败")
        }
        resp.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                // 尽量取服务端 error.message，取不到就用 HTTP 码
                val msg = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull().takeUnless { m -> m.isNullOrBlank() }
                throw SyncClientException(it.code, msg ?: "HTTP ${it.code}")
            }
            return runCatching { JSONObject(text) }
                .getOrElse { throw SyncClientException(0, "服务端返回格式错误") }
        }
    }
}
