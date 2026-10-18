package com.zhuanggui.pda.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

enum class ScanResult { OK, NOT_IN_LIST, OVER_LIMIT, SERIAL_MISMATCH, DUPLICATE }

@Entity(
    tableName = "scan_logs",
    foreignKeys = [
        ForeignKey(
            entity = WorkOrder::class,
            parentColumns = ["id"],
            childColumns = ["workOrderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("workOrderId")]
)
data class ScanLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workOrderId: Long,
    val barcode: String,
    val result: ScanResult,
    val scannedAt: Date = Date()
)
