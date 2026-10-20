package com.zhuanggui.pda.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zhuanggui.pda.data.converter.Converters
import com.zhuanggui.pda.data.dao.LoadContainerDao
import com.zhuanggui.pda.data.dao.LoadRuleDao
import com.zhuanggui.pda.data.dao.RuleLibDao
import com.zhuanggui.pda.data.dao.RuleModelDao
import com.zhuanggui.pda.data.dao.ScanRecordDao
import com.zhuanggui.pda.data.entity.LoadContainer
import com.zhuanggui.pda.data.entity.LoadRule
import com.zhuanggui.pda.data.entity.RuleLibEntry
import com.zhuanggui.pda.data.entity.RuleModel
import com.zhuanggui.pda.data.entity.ScanRecord

@Database(
    entities = [
        LoadContainer::class, LoadRule::class, ScanRecord::class, RuleLibEntry::class, RuleModel::class
    ],
    version = 13,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun loadContainerDao(): LoadContainerDao
    abstract fun loadRuleDao(): LoadRuleDao
    abstract fun scanRecordDao(): ScanRecordDao
    abstract fun ruleLibDao(): RuleLibDao
    abstract fun ruleModelDao(): RuleModelDao

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
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }

        /** v1 → v2：工单表新增备注字段 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE work_orders ADD COLUMN remark TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2 → v3：新增出库扫码相关三张表（装柜会话 / 条码规则 / 扫码记录） */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
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

        /**
         * v3 → v4：Excel 工单（清单校验）功能下架，相关实体已从代码移除。
         * 有意为空操作：work_orders / work_order_items / scan_logs 三张表**保留数据不删除**，
         * 供后期专门开发时衔接（存档见 archive/excel-workorder-v1.2.0/）。
         * Room 通过版本号升级 + 身份哈希更新完成兼容切换，不校验已移除的旧表。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 保留旧表与数据，仅升级版本标识
            }
        }

        /** v4 → v5：新增规则库表（规则源导入 / 规则选择 / 规则编辑） */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rule_library` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`prefix` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_rule_library_prefix` " +
                        "ON `rule_library` (`prefix`)"
                )
            }
        }

        /** v5 → v6：扫码记录支持软删除（回收站 + 撤回/前进）；历史记录 deletedAt = NULL（有效） */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scan_records ADD COLUMN deletedAt INTEGER")
            }
        }

        /**
         * v6 → v7：规则支持双码机选项。
         *  - allowDuplicate：允许重复扫（固定码同码不报重复），默认 0 = 不允许（原行为）
         *  - countEnabled：是否计入统计，默认 1 = 计入（原行为）
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE load_rules ADD COLUMN allowDuplicate INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE load_rules ADD COLUMN countEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v7 → v8：规则支持「查历史串号」。
         *  - checkHistory：扫码时若该条码已在其他柜的有效记录中出现过 → 报串号；默认 0 = 不查（原行为）
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE load_rules ADD COLUMN checkHistory INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v8 → v9：scan_records(barcode) 索引（每条码查重 / 查历史串号不再全表扫描） */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scan_records_barcode` " +
                        "ON `scan_records` (`barcode`)"
                )
            }
        }

        /**
         * v9 → v10：扫码记录加「操作员工号」。
         *  - operatorId：扫描时的工号（设置页选填）；历史记录默认 '' = 未填
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scan_records ADD COLUMN operatorId TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v10 → v11：规则增强。
         *  - load_rules 加「流水号范围」（v1.7.0 第 1 条：前缀之后的数字须落在 [serialMin, serialMax] 内）
         *    与「忽略」（可重复扫的条码扫到不记录）
         *  - rule_library 加四个规则开关（v1.7.0 第 6 条：库内规则与出库扫码规则设置对齐）
         *    allowDuplicate / countEnabled / checkHistory / ignoreRepeat
         *    历史行默认值 = 旧行为（不可重复、计入统计、不查历史、不忽略）
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE load_rules ADD COLUMN serialMin TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE load_rules ADD COLUMN serialMax TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE load_rules ADD COLUMN ignoreRepeat INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rule_library ADD COLUMN allowDuplicate INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rule_library ADD COLUMN countEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE rule_library ADD COLUMN checkHistory INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rule_library ADD COLUMN ignoreRepeat INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v11 → v12：load_rules 加「流水号末尾位数」serialTailLen（0 = 前缀后整段，保持旧行为） */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE load_rules ADD COLUMN serialTailLen INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v12 → v13：新增 rule_models（型号），并把现有规则里的型号名称回填成型号行 */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rule_models` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_rule_models_name` ON `rule_models` (`name`)"
                )
                // 现有规则中的型号名称去重回填；空名称不建型号（列表归到「未填型号」）
                db.execSQL(
                    "INSERT OR IGNORE INTO `rule_models` (`name`, `updatedAt`) " +
                        "SELECT DISTINCT `name`, CAST(strftime('%s','now') AS INTEGER) * 1000 " +
                        "FROM `rule_library` WHERE `name` != ''"
                )
            }
        }

        /** 全部迁移（addMigrations 与 instrumentation 迁移测试共用），按版本顺序 */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
            MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13
        )
    }
}
