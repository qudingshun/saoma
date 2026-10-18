package com.zhuanggui.pda.data.converter

import androidx.room.TypeConverter
import com.zhuanggui.pda.data.entity.ScanResult
import java.util.Date

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? = value?.let { Date(it) }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? = date?.time

    @TypeConverter
    fun fromScanResult(value: ScanResult?): String? = value?.name

    @TypeConverter
    fun toScanResult(value: String?): ScanResult? = value?.let { ScanResult.valueOf(it) }
}
