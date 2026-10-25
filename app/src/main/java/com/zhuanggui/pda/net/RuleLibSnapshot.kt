package com.zhuanggui.pda.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则库同步快照（v1.9.0）：
 *  - 电脑端 `GET /api/v1/rulelib` 返回 `{hasData, rev, models, rules, updatedAt, updatedBy}`
 *  - PDA 上传本机规则库后，电脑端 rev+1；其他 PDA 用 rev 判断是否有新版本
 *  - 下载是整库覆盖（先备份本机规则库）；已装柜的柜子使用各自规则快照，不受影响
 */
data class RuleLibSnapshot(
    val hasData: Boolean,
    val rev: Int,
    val models: List<String>,
    val rules: List<RuleLibRule>,
    val updatedAt: String,
    val updatedBy: String
) {
    /** 上传体（不含 rev：由电脑端 +1） */
    fun toUploadBody(deviceId: String): String = JSONObject().apply {
        put("deviceId", deviceId)
        put("models", JSONArray(models))
        put("rules", JSONArray().also { arr ->
            rules.forEach { r ->
                arr.put(JSONObject().apply {
                    put("prefix", r.prefix)
                    put("name", r.name)
                    put("allowDuplicate", r.allowDuplicate)
                    put("countEnabled", r.countEnabled)
                    put("checkHistory", r.checkHistory)
                    put("ignoreRepeat", r.ignoreRepeat)
                    put("defaultQuantity", r.defaultQuantity)
                })
            }
        })
    }.toString()

    companion object {
        fun fromJson(o: JSONObject): RuleLibSnapshot {
            val models = mutableListOf<String>()
            o.optJSONArray("models")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotEmpty()) models.add(s)
                }
            }
            val rules = mutableListOf<RuleLibRule>()
            o.optJSONArray("rules")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val prefix = r.optString("prefix").trim().uppercase()
                    if (prefix.isEmpty()) continue
                    rules.add(
                        RuleLibRule(
                            prefix = prefix,
                            name = r.optString("name").trim(),
                            allowDuplicate = r.optBoolean("allowDuplicate", false),
                            countEnabled = if (r.has("countEnabled")) r.optBoolean("countEnabled") else true,
                            checkHistory = r.optBoolean("checkHistory", false),
                            ignoreRepeat = r.optBoolean("ignoreRepeat", false),
                            defaultQuantity = r.optInt("defaultQuantity", 0).coerceAtLeast(0)
                        )
                    )
                }
            }
            val rev = o.optInt("rev", 0)
            return RuleLibSnapshot(
                hasData = o.optBoolean("hasData", rev > 0 || models.isNotEmpty() || rules.isNotEmpty()),
                rev = rev,
                models = models.distinct().sorted(),
                rules = rules.distinctBy { it.prefix }.sortedWith(compareBy({ it.name }, { it.prefix })),
                updatedAt = o.optString("updatedAt"),
                updatedBy = o.optString("updatedBy")
            )
        }
    }
}

data class RuleLibRule(
    val prefix: String,
    val name: String,
    val allowDuplicate: Boolean,
    val countEnabled: Boolean,
    val checkHistory: Boolean,
    val ignoreRepeat: Boolean,
    /**
     * 型号级默认装柜数量的同步载体（v16）：DB 中只存在 rule_models，
     * 这里随每条规则重复携带，兼容旧电脑端按规则存储的口径。
     */
    val defaultQuantity: Int = 0
)
