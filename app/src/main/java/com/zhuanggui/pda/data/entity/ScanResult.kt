package com.zhuanggui.pda.data.entity

/**
 * 扫码结果（两个业务线共用）：
 *  - 出库扫码在用：OK / SERIAL_MISMATCH（串号）/ DUPLICATE（重复）/ OVER_LIMIT（超量）
 *  - SERIAL_ALARM（v1.13.0）：流水号命中规则设置的「报警范围」（黑名单），按异常记录、不计数
 *  - AUX 辅助码（v1.5.0）：命中「不计入统计」规则的条码（如双码机的固定码），
 *    只留痕与落盘，不计入正常件数/进度/超量
 *  - NOT_IN_LIST 为老「Excel 工单清单校验」流程的历史语义，随该流程下架后仅作数据兼容保留
 *    （功能源码存档见 archive/excel-workorder-v1.2.0/）
 */
enum class ScanResult { OK, NOT_IN_LIST, OVER_LIMIT, SERIAL_MISMATCH, DUPLICATE, AUX, SERIAL_ALARM }
