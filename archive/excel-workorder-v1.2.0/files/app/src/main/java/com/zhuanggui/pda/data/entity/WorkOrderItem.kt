package com.zhuanggui.pda.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "work_order_items",
    foreignKeys = [
        ForeignKey(
            entity = WorkOrder::class,
            parentColumns = ["id"],
            childColumns = ["workOrderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("workOrderId"), Index(value = ["workOrderId", "barcode"], unique = true)]
)
data class WorkOrderItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workOrderId: Long,
    val barcode: String,
    val allowedQuantity: Int = 0,
    var scannedQuantity: Int = 0,
    val productName: String = ""
)
