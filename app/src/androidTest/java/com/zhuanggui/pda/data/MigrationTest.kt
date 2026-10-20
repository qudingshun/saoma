package com.zhuanggui.pda.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移测试（真机 instrumentation 运行：.\gradlew.bat :app:connectedDebugAndroidTest）
 *
 * 覆盖：
 *  - v8 → v9：新增 scan_records(barcode) 索引（用导出的 8.json 建库，数据保留校验）
 *  - v9 → v10：scan_records 加 operatorId 列（用导出的 9.json 建库，默认值校验）
 *  - v10 → v11：load_rules 加 serialMin/serialMax/ignoreRepeat，rule_library 加四个规则开关
 *    （用导出的 10.json 建库，默认值校验）
 *  - v11 → v12：load_rules 加 serialTailLen（末尾流水号位数，默认 0 = 旧行为）
 *  - v12 → v13：新增 rule_models（型号），现有规则里的型号名称去重回填
 *  - v5 → v13 全链：v5~v7 当年未导出 schema（无法用 createDatabase 建旧库），
 *    改为手写与当年迁移 SQL 完全一致的 v5 建库脚本作为起点，再跑 5→6→…→13 并按 13.json 校验。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    /** v8（有 schema）→ v9：加索引、数据不丢 */
    @Test
    fun migrate8To9_addsBarcodeIndex() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO load_containers (containerNo, totalQuantity, createdAt, status) " +
                    "VALUES ('C-8TO9', 10, 1730000000000, 'ACTIVE')"
            )
            db.execSQL(
                "INSERT INTO scan_records (containerId, containerNo, barcode, fullCode, result, scannedAt, deletedAt) " +
                    "VALUES (1, 'C-8TO9', 'XYZ0001', 'C-8TO9，XYZ0001', 'OK', 1730000000000, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, AppDatabase.ALL_MIGRATIONS[7])
        try {
            db.query("SELECT COUNT(*) FROM scan_records").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_scan_records_barcode'"
            ).use { c ->
                assertTrue(c.moveToFirst())
            }
        } finally {
            db.close()
        }
    }

    /** v9（有 schema）→ v10：加 operatorId 列、历史记录默认空串、数据不丢 */
    @Test
    fun migrate9To10_addsOperatorId() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.execSQL(
                "INSERT INTO load_containers (containerNo, totalQuantity, createdAt, status) " +
                    "VALUES ('C-9TO10', 10, 1730000000000, 'ACTIVE')"
            )
            db.execSQL(
                "INSERT INTO scan_records (containerId, containerNo, barcode, fullCode, result, scannedAt, deletedAt) " +
                    "VALUES (1, 'C-9TO10', 'XYZ0001', 'C-9TO10，XYZ0001', 'OK', 1730000000000, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, AppDatabase.ALL_MIGRATIONS[8])
        try {
            db.query("SELECT COUNT(*) FROM scan_records").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT operatorId FROM scan_records LIMIT 1").use { c ->
                c.moveToFirst()
                assertEquals("", c.getString(0))
            }
        } finally {
            db.close()
        }
    }

    /** v10（有 schema）→ v11：规则表加流水号范围/忽略 + 规则库四个开关，历史行取旧行为默认值 */
    @Test
    fun migrate10To11_addsRuleColumns() {
        helper.createDatabase(TEST_DB, 10).use { db ->
            db.execSQL(
                "INSERT INTO load_containers (containerNo, totalQuantity, createdAt, status) " +
                    "VALUES ('C-10TO11', 10, 1730000000000, 'ACTIVE')"
            )
            db.execSQL(
                "INSERT INTO load_rules (containerId, prefix, allowDuplicate, countEnabled, checkHistory) " +
                    "VALUES (1, 'XYZ', 0, 1, 0)"
            )
            db.execSQL(
                "INSERT INTO rule_library (prefix, name, updatedAt) VALUES ('XYZ', '型号X', 1730000000000)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, AppDatabase.ALL_MIGRATIONS[9])
        try {
            // load_rules：流水号范围默认空（不校验）、忽略默认关 → 行为与旧版一致
            db.query("SELECT serialMin, serialMax, ignoreRepeat FROM load_rules LIMIT 1").use { c ->
                c.moveToFirst()
                assertEquals("", c.getString(0))
                assertEquals("", c.getString(1))
                assertEquals(0, c.getInt(2))
            }
            // rule_library：四个开关默认 = 不可重复 / 计入统计 / 不查历史 / 不忽略
            db.query(
                "SELECT allowDuplicate, countEnabled, checkHistory, ignoreRepeat FROM rule_library LIMIT 1"
            ).use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
                assertEquals(1, c.getInt(1))
                assertEquals(0, c.getInt(2))
                assertEquals(0, c.getInt(3))
            }
        } finally {
            db.close()
        }
    }

    /** v11（有 schema）→ v12：规则表加末尾流水号位数，历史行默认 0 = 旧行为 */
    @Test
    fun migrate11To12_addsSerialTailLen() {
        helper.createDatabase(TEST_DB, 11).use { db ->
            db.execSQL(
                "INSERT INTO load_containers (containerNo, totalQuantity, createdAt, status) " +
                    "VALUES ('C-11TO12', 10, 1730000000000, 'ACTIVE')"
            )
            db.execSQL(
                "INSERT INTO load_rules (containerId, prefix, allowDuplicate, countEnabled, checkHistory, " +
                    "serialMin, serialMax, ignoreRepeat) VALUES (1, 'ASV12', 0, 1, 0, '1', '300', 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, AppDatabase.ALL_MIGRATIONS[10])
        try {
            db.query("SELECT serialTailLen FROM load_rules LIMIT 1").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    /** v12（有 schema）→ v13：新增型号表，现有规则名称去重回填 */
    @Test
    fun migrate12To13_addsRuleModels() {
        helper.createDatabase(TEST_DB, 12).use { db ->
            db.execSQL(
                "INSERT INTO rule_library (prefix, name, allowDuplicate, countEnabled, checkHistory, " +
                    "ignoreRepeat, updatedAt) VALUES ('ASV12', 'T45LW', 0, 1, 0, 0, 1730000000000)"
            )
            db.execSQL(
                "INSERT INTO rule_library (prefix, name, allowDuplicate, countEnabled, checkHistory, " +
                    "ignoreRepeat, updatedAt) VALUES ('ASV13', 'T45LW', 0, 1, 0, 0, 1730000000000)"
            )
            db.execSQL(
                "INSERT INTO rule_library (prefix, name, allowDuplicate, countEnabled, checkHistory, " +
                    "ignoreRepeat, updatedAt) VALUES ('B1', 'T70', 0, 1, 0, 0, 1730000000000)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 13, true, AppDatabase.ALL_MIGRATIONS[11])
        try {
            db.query("SELECT COUNT(*) FROM rule_models").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
            db.query("SELECT name FROM rule_models ORDER BY name").use { c ->
                c.moveToFirst()
                assertEquals("T45LW", c.getString(0))
                c.moveToNext()
                assertEquals("T70", c.getString(0))
            }
        } finally {
            db.close()
        }
    }

    /** v5（手写起点）→ v13 全链：数据保留 + 新增列默认值正确 + barcode 索引生效 */
    @Test
    fun migrate5To13_fullChain() {
        context.deleteDatabase(TEST_DB)
        val dbFile = context.getDatabasePath(TEST_DB)
        dbFile.parentFile?.mkdirs()

        // —— v5 起点结构（与 MIGRATION_2_3 / MIGRATION_4_5 中当年的建表 SQL 一致） ——
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `load_containers` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `containerNo` TEXT NOT NULL, " +
                    "`totalQuantity` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_load_containers_containerNo` " +
                    "ON `load_containers` (`containerNo`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `load_rules` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `containerId` INTEGER NOT NULL, " +
                    "`prefix` TEXT NOT NULL, " +
                    "FOREIGN KEY(`containerId`) REFERENCES `load_containers`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_load_rules_containerId` ON `load_rules` (`containerId`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `scan_records` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `containerId` INTEGER NOT NULL, " +
                    "`containerNo` TEXT NOT NULL, `barcode` TEXT NOT NULL, `fullCode` TEXT NOT NULL, " +
                    "`result` TEXT NOT NULL, `scannedAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`containerId`) REFERENCES `load_containers`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_scan_records_containerId` ON `scan_records` (`containerId`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `rule_library` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `prefix` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_rule_library_prefix` ON `rule_library` (`prefix`)"
            )
            // 种子数据：1 柜 + 1 规则 + 2 条扫码记录（v5 时还没有 deletedAt / 双码机三列）
            db.execSQL(
                "INSERT INTO load_containers (containerNo, totalQuantity, createdAt, status) " +
                    "VALUES ('C-5TO10', 100, 1730000000000, 'ACTIVE')"
            )
            db.execSQL("INSERT INTO load_rules (containerId, prefix) VALUES (1, 'ABC')")
            db.execSQL(
                "INSERT INTO scan_records (containerId, containerNo, barcode, fullCode, result, scannedAt) " +
                    "VALUES (1, 'C-5TO10', 'ABC0001', 'C-5TO10，ABC0001', 'OK', 1730000000000)"
            )
            db.execSQL(
                "INSERT INTO scan_records (containerId, containerNo, barcode, fullCode, result, scannedAt) " +
                    "VALUES (1, 'C-5TO10', 'ABC0002', 'C-5TO10，ABC0002', 'OK', 1730000001000)"
            )
            db.execSQL(
                "INSERT INTO rule_library (prefix, name, updatedAt) VALUES ('LIB5', '型号五', 1730000000000)"
            )
            db.version = 5
        }

        // 跑 5→6→…→13（起点无 schema JSON，drop 校验传 false），结束后按 13.json 全量校验
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 13, false, *AppDatabase.ALL_MIGRATIONS.copyOfRange(4, 12)
        )
        try {
            // 数据保留
            db.query("SELECT COUNT(*) FROM scan_records").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM scan_records WHERE deletedAt IS NULL").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
            // v6~v8 新增列默认值正确（旧行为不变）
            db.query("SELECT allowDuplicate, countEnabled, checkHistory FROM load_rules LIMIT 1").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
                assertEquals(1, c.getInt(1))
                assertEquals(0, c.getInt(2))
            }
            // v9 barcode 索引已建立
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_scan_records_barcode'"
            ).use { c ->
                assertTrue(c.moveToFirst())
            }
            // v10 新增 operatorId 列：旧记录默认空串
            db.query("SELECT operatorId FROM scan_records LIMIT 1").use { c ->
                c.moveToFirst()
                assertEquals("", c.getString(0))
            }
            // v11 新增规则列：流水号范围默认空、忽略默认关（旧行为不变）
            db.query("SELECT serialMin, serialMax, ignoreRepeat FROM load_rules LIMIT 1").use { c ->
                c.moveToFirst()
                assertEquals("", c.getString(0))
                assertEquals("", c.getString(1))
                assertEquals(0, c.getInt(2))
            }
            // v12 新增末尾位数默认 0（前缀后整段，旧行为不变）
            db.query("SELECT serialTailLen FROM load_rules LIMIT 1").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            // v13 型号表：现有规则名称回填
            db.query("SELECT name FROM rule_models").use { c ->
                c.moveToFirst()
                assertEquals("型号五", c.getString(0))
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
