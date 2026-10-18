package com.zhuanggui.pda.ui

import com.zhuanggui.pda.data.entity.ScanResult

/**
 * 扫码结果判定纯函数（从 AppViewModel.handleOutboundScan 抽出，便于单元测试）。
 *
 * 判定顺序（业务契约，不要随意调整）：
 *  1. 规则不匹配（串号）—— 本柜规则优先于一切
 *  2. 同柜重复（已录入过；规则勾选“允许重复”时调用方传 dupCount = 0）
 *  3. 历史串号（条码曾在其他柜录入过；仅当规则勾选「查历史串号」）
 *  4. 辅助码（规则标记为不计入统计）—— 必须先于超量：辅助码不应被超量拦截
 *  5. 超量（本柜设了数量 total≥1，且今天已装正常数 okCount ≥ total）
 *  6. 正常
 *
 * 备注：超量沿用原语义 `total in 1..okCount`：
 *  - total ≤ 0（未设数量）→ 永不超量
 *  - okCount 为「今天已装正常数」（不含辅助/重复/串号，不含回收站）
 */
fun decideScanResult(
    matched: Boolean,
    dupCount: Int,
    historyHit: Boolean,
    countEnabled: Boolean,
    total: Int,
    okCount: Int
): ScanResult = when {
    !matched -> ScanResult.SERIAL_MISMATCH
    dupCount > 0 -> ScanResult.DUPLICATE
    historyHit -> ScanResult.SERIAL_MISMATCH
    !countEnabled -> ScanResult.AUX
    total in 1..okCount -> ScanResult.OVER_LIMIT
    else -> ScanResult.OK
}
