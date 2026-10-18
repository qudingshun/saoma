package com.zhuanggui.pda.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * 界面层时间显示统一入口（java.time）：
 * SimpleDateFormat 非线程安全、每次格式化都要新建实例；DateTimeFormatter 不可变、线程安全，可全局复用。
 */
private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
private val minuteFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
private val uiZone: ZoneId = ZoneId.systemDefault()

/** 精确到秒：yyyy-MM-dd HH:mm:ss（记录列表） */
fun formatUiDateTime(date: Date): String =
    dateTimeFormat.format(Instant.ofEpochMilli(date.time).atZone(uiZone))

/** 精确到分：yyyy-MM-dd HH:mm（回收站「删除于」） */
fun formatUiMinute(date: Date): String =
    minuteFormat.format(Instant.ofEpochMilli(date.time).atZone(uiZone))
