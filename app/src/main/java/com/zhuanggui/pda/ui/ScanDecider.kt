package com.zhuanggui.pda.ui

import com.zhuanggui.pda.data.entity.ScanResult

/**
 * 扫码结果判定纯函数（从 AppViewModel.handleOutboundScan 抽出，便于单元测试）。
 *
 * 判定顺序（业务契约，不要随意调整）：
 *  1. 规则不匹配（串号）—— 本柜规则优先于一切
 *  1.5 流水号命中报警范围（v1.13.0 黑名单）—— 每枪都报警，优先于重复/历史
 *  2. 同柜重复（已录入过；规则勾选“允许重复”时调用方传 dupCount = 0）
 *  3. 历史串号（条码曾在其他柜录入过；仅当规则勾选「查历史串号」）
 *  4. 辅助码（规则标记为不计入统计）—— 必须先于超量：辅助码不应被超量拦截
 *  5. 超量（本柜设了计划装柜数量 total≥1，且今天已扫码正常数 okCount ≥ total）
 *  6. 正常
 *
 * 备注：超量沿用原语义 `total in 1..okCount`：
 *  - total ≤ 0（未设计划装柜数量）→ 永不超量
 *  - okCount 为「今天已装正常数」（不含辅助/重复/串号，不含回收站）
 */
fun decideScanResult(
    matched: Boolean,
    dupCount: Int,
    historyHit: Boolean,
    countEnabled: Boolean,
    total: Int,
    okCount: Int,
    rangeAlarm: Boolean = false
): ScanResult = when {
    !matched -> ScanResult.SERIAL_MISMATCH
    rangeAlarm -> ScanResult.SERIAL_ALARM
    dupCount > 0 -> ScanResult.DUPLICATE
    historyHit -> ScanResult.SERIAL_MISMATCH
    !countEnabled -> ScanResult.AUX
    total in 1..okCount -> ScanResult.OVER_LIMIT
    else -> ScanResult.OK
}

/**
 * 流水号范围判定纯函数（v1.7.0：规则可选「流水号最小值/最大值」限定数据范围；
 * v1.7.2：可选「末尾 N 位」流水号，兼顾条码中间有无关字符/数字、流水号在末尾的情况）。
 *
 * 语义（业务契约）：
 *  - 上下界都留空 → 不校验流水号（与旧版行为一致），直接通过
 *  - [tailLen] = 0（默认）：前缀之后的部分（[rest]）必须是纯数字，且数值落在范围内；否则不匹配
 *  - [tailLen] > 0：忽略 [rest] 末尾的字母/符号，取「最后 N 位数字」作为流水号再比对范围
 *    （例：前缀 ASV12、tailLen=3，条码 ASV12SDFWG05455056$ → 取 056）
 *  - 数值比较用 BigInteger：兼容任意长度流水号，避免 Long 溢出
 *
 * @param rest 条码去掉规则前缀后的剩余部分（调用方 removePrefix 得到；前缀=整条码时为空）
 * @param serialMin 流水号最小值（空 = 不限）
 * @param serialMax 流水号最大值（空 = 不限）
 * @param tailLen >0 = 取末尾 N 位数字（0 = 前缀后整段，旧行为）
 */
fun serialInRange(
    rest: String,
    serialMin: String,
    serialMax: String,
    tailLen: Int = 0
): Boolean {
    val min = serialMin.trim()
    val max = serialMax.trim()
    if (min.isEmpty() && max.isEmpty()) return true

    val serial: String = if (tailLen > 0) {
        // 先去掉末尾的字母/符号（如 056$ → 056），再取最后 N 位
        val trimmed = rest.trimEnd { !it.isDigit() }
        if (trimmed.length < tailLen) return false
        val tail = trimmed.takeLast(tailLen)
        if (!tail.all { it.isDigit() }) return false
        tail
    } else {
        if (rest.isEmpty() || !rest.all { it.isDigit() }) return false
        rest
    }
    val value = runCatching { java.math.BigInteger(serial) }.getOrNull() ?: return false
    if (min.isNotEmpty()) {
        val lo = runCatching { java.math.BigInteger(min) }.getOrNull() ?: return false
        if (value < lo) return false
    }
    if (max.isNotEmpty()) {
        val hi = runCatching { java.math.BigInteger(max) }.getOrNull() ?: return false
        if (value > hi) return false
    }
    return true
}
