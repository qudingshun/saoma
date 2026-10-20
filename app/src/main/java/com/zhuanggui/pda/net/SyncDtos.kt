package com.zhuanggui.pda.net

import com.zhuanggui.pda.data.entity.ScanRecord
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 传输时间：ISO8601 带时区（如 2026-10-20T15:01:02.123+08:00） */
private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
    timeZone = TimeZone.getDefault()
}

fun Date.toIso(): String = ISO.format(this)

fun String.toDateOrNull(): Date? = runCatching { ISO.parse(this) }.getOrNull()

/** 上行：单条扫码记录 → JSON（字段名与 01-接口契约 一致） */
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

/**
 * 配对信息：
 *  - 二维码：`SAOMASYNC1|ip=192.168.1.100|port=8000|token=K7F2QW9M|ver=1`
 *  - 手输兜底：`192.168.1.100:8000#K7F2QW9M`
 */
data class PairingInfo(val baseUrl: String, val token: String) {
    companion object {
        fun parse(raw: String): PairingInfo? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("SAOMASYNC1|")) {
                val map = s.split('|').drop(1).mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
                }.toMap()
                val ip = map["ip"]?.trim().orEmpty()
                val port = map["port"]?.trim()?.toIntOrNull()
                val token = map["token"]?.trim().orEmpty()
                if (ip.isEmpty() || port == null || port !in 1..65535 || token.isEmpty()) return null
                return PairingInfo("http://$ip:$port", token)
            }
            val hash = s.indexOf('#')
            if (hash <= 0 || hash == s.lastIndex) return null
            val hostPort = s.substring(0, hash).trim()
            val token = s.substring(hash + 1).trim()
            if (hostPort.isEmpty() || token.isEmpty()) return null
            val withScheme = if (hostPort.startsWith("http://") || hostPort.startsWith("https://"))
                hostPort else "http://$hostPort"
            val portPart = withScheme.substringAfterLast(':')
            val port = portPart.toIntOrNull()
            if (port == null || port !in 1..65535) return null
            return PairingInfo(withScheme.trimEnd('/'), token)
        }
    }
}
