package com.zhuanggui.pda.data.entity

/**
 * 规则库重复校验策略（v1.12.0）：
 *  - 只有「只计入统计」的普通计数码才要求跨型号唯一；
 *  - 可重复扫 / 辅助码（不计统计）/ 查历史串号 / 忽略 属于特殊码，允许不同型号共用同一前缀；
 *  - 特殊码也允许和已有普通计数码同前缀（例如通用固定码先按特殊码建，再被某个型号引用）。
 *
 * 同一型号内部仍不允许重复前缀（重复前缀在装柜规则里没有意义）。
 */
fun isCountOnlyRule(
    allowDuplicate: Boolean,
    countEnabled: Boolean,
    checkHistory: Boolean,
    ignoreRepeat: Boolean
): Boolean = countEnabled && !allowDuplicate && !checkHistory && !ignoreRepeat

fun RuleLibEntry.isCountOnlyRule(): Boolean = isCountOnlyRule(
    allowDuplicate = allowDuplicate,
    countEnabled = countEnabled,
    checkHistory = checkHistory,
    ignoreRepeat = ignoreRepeat
)

/**
 * 普通计数码与其他型号的普通计数码冲突。
 * 当前规则是特殊码，或库里同前缀只有特殊码时，不冲突。
 */
fun normalCountedConflictOwners(
    prefix: String,
    targetName: String,
    currentIsCountOnly: Boolean,
    existing: List<RuleLibEntry>
): List<RuleLibEntry> {
    if (!currentIsCountOnly) return emptyList()
    return existing.filter { entry ->
        entry.prefix == prefix &&
            entry.name != targetName &&
            entry.isCountOnlyRule()
    }
}
