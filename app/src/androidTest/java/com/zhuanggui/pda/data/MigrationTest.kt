package com.zhuanggui.pda.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *  - v13 → v14：scan_records 加 uid/syncedAt，新增 sync_state / sync_inbox（WiFi 同步）
 *  - v15 → v16：默认装柜数量归并到 rule_models，rule_library 去掉该列，移除 scan_setup_templates
 *  - v16 → v17：规则库 prefix 唯一索引改为普通索引，允许特殊码同前缀跨型号
 *  - v5 → v17 全链：v5~v7 当年未导出 schema（无法用 createDatabase 建旧库），
 *    改为手写与当年迁移 SQL 完全一致的 v5 建库脚本作为起点，再跑 5→6→…→17 并按 17.json 校验。
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

    /** v13（有 schema）→ v14：加 uid/syncedAt、建 sync_state/sync_inbox，历史记录 uid 全部回填且唯一 */
    @Test
    fun migrate13To14_addsSyncColumns() {
        helper.createDatabase(TEST_DB, 13).use { db ->
            db.execSQL(
                "INSERT INTO load_containers (containerNo, totalQuantity, createdAt, status) " +
                    "VALUES ('C-13TO14', 10, 1730000000000, 'ACTIVE')"
            )
            db.execSQL(
                "INSERT INTO scan_records (containerId, containerNo, barcode, fullCode, result, scannedAt, deletedAt, operatorId) " +
                    "VALUES (1, 'C-13TO14', 'XYZ0001', 'C-13TO14，XYZ0001', 'OK', 1730000000000, NULL, 'A')"
            )
            db.execSQL(
                "INSERT INTO scan_records (containerId, containerNo, barcode, fullCode, result, scannedAt, deletedAt, operatorId) " +
                    "VALUES (1, 'C-13TO14', 'XYZ0002', 'C-13TO14，XYZ0002', 'OK', 1730000000001, NULL, 'A')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 14, true, AppDatabase.ALL_MIGRATIONS[12])
        try {
            db.query("SELECT uid, syncedAt FROM scan_records").use { c ->
                val uids = mutableSetOf<String>()
                while (c.moveToNext()) {
                    val uid = c.getString(0)
                    assertTrue(uid.isNotBlank())
                    uids.add(uid)
                    assertTrue(c.isNull(1))
                }
                assertEquals(2, uids.size)
            }
            db.query("SELECT COUNT(*) FROM sync_state").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM sync_inbox").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    /** v15（有 schema）→ v16：默认数量归并到型号，规则库去掉该列，设置方案表移除 */
    @Test
    fun migrate15To16_movesDefaultQuantityToModel() {
        helper.createDatabase(TEST_DB, 15).use { db ->
            db.execSQL(
                "INSERT INTO rule_models (name, updatedAt) VALUES ('T45LW', 1730000000000)"
            )
            db.execSQL(
                "INSERT INTO rule_library (prefix, name, allowDuplicate, countEnabled, checkHistory, " +
                    "ignoreRepeat, defaultQuantity, updatedAt) " +
                    "VALUES ('ASV12', 'T45LW', 0, 1, 0, 0, 260, 1730000000000)"
            )
            db.execSQL(
                "INSERT INTO scan_setup_templates (name, totalQuantity, rulesJson, updatedAt) " +
                    "VALUES ('旧方案', 260, '[]', 1730000000000)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 16, true, AppDatabase.ALL_MIGRATIONS[14])
        try {
            db.query("SELECT defaultQuantity FROM rule_models WHERE name = 'T45LW'").use { c ->
                c.moveToFirst()
                assertEquals(260, c.getInt(0))
            }
            db.query("SELECT prefix, name, countEnabled FROM rule_library LIMIT 1").use { c ->
                c.moveToFirst()
                assertEquals("ASV12", c.getString(0))
                assertEquals("T45LW", c.getString(1))
                assertEquals(1, c.getInt(2))
            }
            // 规则库不应再有 defaultQuantity 列
            var hasDefaultColumn = false
            db.query("PRAGMA table_info(rule_library)").use { c ->
                while (c.moveToNext()) {
                    if (c.getString(c.getColumnIndexOrThrow("name")) == "defaultQuantity") {
                        hasDefaultColumn = true
                    }
                }
            }
            assertFalse(hasDefaultColumn)
            db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'scan_setup_templates'"
            ).use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    /** v16（有 schema）→ v17：prefix 唯一索引改为普通索引，可同前缀挂多个型号 */
    @Test
    fun migrate16To17_allowsDuplicatePrefixAcrossModels() {
        helper.createDatabase(TEST_DB, 16).use { db ->
            db.execSQL("INSERT INTO rule_models (name, defaultQuantity, updatedAt) VALUES ('A', 0, 1)")
            db.execSQL("INSERT INTO rule_models (name, defaultQuantity, updatedAt) VALUES ('B', 0, 1)")
            db.execSQL(
                "INSERT INTO rule_library (prefix, name, allowDuplicate, countEnabled, checkHistory, " +
                    "ignoreRepeat, updatedAt) VALUES ('COMMON', 'A', 1, 1, 0, 1, 1)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 17, true, AppDatabase.ALL_MIGRATIONS[15])
        try {
            db.execSQL(
                "INSERT INTO rule_library (prefix, name, allowDuplicate, countEnabled, checkHistory, " +
                    "ignoreRepeat, updatedAt) VALUES ('COMMON', 'B', 1, 1, 0, 1, 1)"
            )
            db.query("SELECT COUNT(*) FROM rule_library WHERE prefix = 'COMMON'").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    /** v5（手写起点）→ v17 全链：数据保留 + 新增列默认值正确 + barcode 索引生效 */
    @Test
    fun migrate5To17_fullChain() {
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

        // 跑 5→6→…→17（起点无 schema JSON，drop 校验传 false），结束后按 17.json 全量校验
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 17, false, *AppDatabase.ALL_MIGRATIONS.copyOfRange(4, 16)
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
            // v14 同步列：历史记录 uid 全部回填
            db.query("SELECT COUNT(*) FROM scan_records WHERE uid = ''").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            // v16：默认数量归并到型号（旧数据默认 0），设置方案表已移除
            db.query("SELECT defaultQuantity FROM rule_models WHERE name = '型号五'").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'scan_setup_templates'"
            ).use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
