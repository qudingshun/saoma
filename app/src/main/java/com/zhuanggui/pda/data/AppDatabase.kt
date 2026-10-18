package com.zhuanggui.pda.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.zhuanggui.pda.data.converter.Converters
import com.zhuanggui.pda.data.dao.LoadContainerDao
import com.zhuanggui.pda.data.dao.LoadRuleDao
import com.zhuanggui.pda.data.dao.ScanLogDao
import com.zhuanggui.pda.data.dao.ScanRecordDao
import com.zhuanggui.pda.data.dao.WorkOrderDao
import com.zhuanggui.pda.data.dao.WorkOrderItemDao
import com.zhuanggui.pda.data.entity.LoadContainer
import com.zhuanggui.pda.data.entity.LoadRule
import com.zhuanggui.pda.data.entity.ScanLog
import com.zhuanggui.pda.data.entity.ScanRecord
import com.zhuanggui.pda.data.entity.WorkOrder
import com.zhuanggui.pda.data.entity.WorkOrderItem

@Database(
    entities = [
        WorkOrder::class, WorkOrderItem::class, ScanLog::class,
        LoadContainer::class, LoadRule::class, ScanRecord::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workOrderDao(): WorkOrderDao
    abstract fun workOrderItemDao(): WorkOrderItemDao
    abstract fun scanLogDao(): ScanLogDao
    abstract fun loadContainerDao(): LoadContainerDao
    abstract fun loadRuleDao(): LoadRuleDao
    abstract fun scanRecordDao(): ScanRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "zhuanggui.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }

        /** v1 → v2：工单表新增备注字段 */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE work_orders ADD COLUMN remark TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2 → v3：新增出库扫码相关三张表（装柜会话 / 条码规则 / 扫码记录） */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `load_containers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`containerNo` TEXT NOT NULL, " +
                        "`totalQuantity` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_load_containers_containerNo` " +
                        "ON `load_containers` (`containerNo`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `load_rules` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`containerId` INTEGER NOT NULL, " +
                        "`prefix` TEXT NOT NULL, " +
                        "FOREIGN KEY(`containerId`) REFERENCES `load_containers`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_load_rules_containerId` " +
                        "ON `load_rules` (`containerId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scan_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`containerId` INTEGER NOT NULL, " +
                        "`containerNo` TEXT NOT NULL, " +
                        "`barcode` TEXT NOT NULL, " +
                        "`fullCode` TEXT NOT NULL, " +
                        "`result` TEXT NOT NULL, " +
                        "`scannedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`containerId`) REFERENCES `load_containers`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scan_records_containerId` " +
                        "ON `scan_records` (`containerId`)"
                )
            }
        }
    }
}
