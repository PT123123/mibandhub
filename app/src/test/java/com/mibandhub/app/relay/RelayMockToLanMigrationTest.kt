package com.mibandhub.app.relay

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.mibandhub.app.relay.model.RelayMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「历史模拟中转跨升级残留」的回归门。
 *
 * 缺陷形状：旧版本把 MOCK 当默认并落盘 `relay_mode="mock"`，后来只改读取侧兜底，
 * 于是存过键的设备升级多少次都不触网，而界面上总开关还是开的。
 * 2026-11-09 在 OPPO PHZ110 上实测到（无 TCP 监听 / 无 relay-discovery 组播锁 / 无 LanRelayServer 日志）。
 *
 * 每条断言都走**生产读取路径** [relayConfigFrom]，不在测试里复刻模式判断。
 */
class RelayMockToLanMigrationTest {

    private val migration = RelayMockToLanMigration()

    /** 缺陷现场：老设备存了 "mock"，没有版本键。不迁移的话读取侧永远给 MOCK。 */
    private val legacyMock = preferencesOf(
        RelayKeys.MODE to "mock",
        RelayKeys.ENABLED to true,
        RelayKeys.ALIAS to "手环管家",
    )

    /** control：先证明夹具真能复现缺陷，否则「迁移后变 LAN」什么也没盯住。 */
    @Test
    fun control_legacyDataReadsAsMockBeforeMigration() = runBlocking {
        assertTrue("遗留数据应触发迁移", migration.shouldMigrate(legacyMock))
        assertEquals(RelayMode.MOCK, relayConfigFrom(legacyMock).mode)
    }

    @Test
    fun legacyMockBecomesLanThroughProductionReader() = runBlocking {
        val migrated = migration.migrate(legacyMock)
        assertEquals("lan", migrated[RelayKeys.MODE])
        assertEquals(RELAY_SCHEMA_VERSION, migrated[RelayKeys.SCHEMA_VERSION])
        assertEquals(RelayMode.LAN, relayConfigFrom(migrated).mode)
    }

    @Test
    fun migrationRunsOnlyOnce() = runBlocking {
        val migrated = migration.migrate(legacyMock)
        assertFalse("版本键已写满，不该再迁移", migration.shouldMigrate(migrated))
    }

    /** 迁移跑过之后用户主动选 MOCK：必须保留，否则等于剥夺调试模式。 */
    @Test
    fun explicitMockAfterMigrationIsPreserved() = runBlocking {
        val explicit = preferencesOf(
            RelayKeys.MODE to "mock",
            RelayKeys.SCHEMA_VERSION to RELAY_SCHEMA_VERSION,
        )
        assertFalse(migration.shouldMigrate(explicit))
        assertEquals(RelayMode.MOCK, relayConfigFrom(explicit).mode)
    }

    @Test
    fun freshInstallIsStampedLan() = runBlocking {
        assertTrue(migration.shouldMigrate(emptyPreferences()))
        val migrated = migration.migrate(emptyPreferences())
        assertEquals("lan", migrated[RelayKeys.MODE])
        assertEquals(RELAY_SCHEMA_VERSION, migrated[RelayKeys.SCHEMA_VERSION])
        assertEquals(RelayMode.LAN, relayConfigFrom(migrated).mode)
    }

    @Test
    fun migrationLeavesEveryOtherKeyUntouched() = runBlocking {
        val full = preferencesOf(
            RelayKeys.MODE to "mock",
            RelayKeys.ENABLED to false,
            RelayKeys.AUTO_SYNC to true,
            RelayKeys.INTERVAL to 30,
            RelayKeys.SERVER_URL to "http://192.168.88.246:18765",
            RelayKeys.ALIAS to "我的手环",
            RelayKeys.SELECTED_DEVICE to "lan:192.168.88.246:18765",
            RelayKeys.LAST_SYNC_AT to 1_760_000_000_000L,
            RelayKeys.LAST_RESULT to "ok:46:51861",
        )
        val migrated = migration.migrate(full)
        val cfg = relayConfigFrom(migrated)
        assertEquals(RelayMode.LAN, cfg.mode)
        assertFalse("总开关不该被迁移顺手打开", cfg.enabled)
        assertTrue(cfg.autoSync)
        assertEquals(30, cfg.intervalMinutes)
        assertEquals("http://192.168.88.246:18765", cfg.serverUrl)
        assertEquals("我的手环", cfg.sourceAlias)
        assertEquals("lan:192.168.88.246:18765", cfg.selectedDeviceId)
        assertEquals(1_760_000_000_000L, migrated[RelayKeys.LAST_SYNC_AT])
        assertEquals("ok:46:51861", migrated[RelayKeys.LAST_RESULT])
        // 除了 MODE 与 SCHEMA_VERSION，磁盘上的键值对必须逐条相等。
        val expected = full.asMap().toMutableMap().apply {
            put(RelayKeys.MODE, "lan")
            put(RelayKeys.SCHEMA_VERSION, RELAY_SCHEMA_VERSION)
        }
        assertEquals(expected, migrated.asMap())
    }

    /**
     * 迁移认的是磁盘上那个字符串。悄悄给 PreferencesKey 改名会让历史数据变成孤儿，
     * 门却照样绿——所以把实际落盘名字钉死。
     */
    @Test
    fun onDiskKeyNamesArePinned() {
        assertEquals("relay_mode", RelayKeys.MODE.name)
        assertEquals("relay_schema_version", RelayKeys.SCHEMA_VERSION.name)
        assertEquals("relay_enabled", RelayKeys.ENABLED.name)
        assertEquals("relay_auto_sync", RelayKeys.AUTO_SYNC.name)
    }
}