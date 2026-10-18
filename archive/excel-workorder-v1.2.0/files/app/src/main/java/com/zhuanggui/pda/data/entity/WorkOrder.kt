package com.zhuanggui.pda.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "work_orders")
data class WorkOrder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val containerNo: String,
    val remark: String = "",
    val createdAt: Date = Date(),
    val status: String = STATUS_ACTIVE
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_FINISHED = "FINISHED"
        const val STATUS_CLOSED = "CLOSED"
    }
}
