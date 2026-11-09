package com.mibandhub.app.relay

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mibandhub.app.relay.model.RelayConfig
import com.mibandhub.app.relay.model.RelayMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 中转配置 / 上次同步结果的持久化。
 *
 * 单独用一个名为 "relay" 的 DataStore，和手环的 "band" 存储彻底分开 ——
 * 这条数据通道的开关、地址、别名即便改坏了，也不影响手环配对与测量。
 */
private val Context.relayDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "relay",
    produceMigrations = { listOf(RelayMockToLanMigration()) },
)

/** 落盘键名。公开到模块内是为了让迁移门能钉住磁盘上的实际名字，改名必须连着迁移一起改。 */
internal object RelayKeys {
    val MODE = stringPreferencesKey("relay_mode") // "mock" | "lan"
    val ENABLED = booleanPreferencesKey("relay_enabled")
    val AUTO_SYNC = booleanPreferencesKey("relay_auto_sync")
    val INTERVAL = intPreferencesKey("relay_interval_min")
    val SERVER_URL = stringPreferencesKey("relay_server_url")
    val ALIAS = stringPreferencesKey("relay_alias")
    val SELECTED_DEVICE = stringPreferencesKey("relay_device_id")
    val LAST_SYNC_AT = longPreferencesKey("relay_last_sync_at")
    val LAST_RESULT = stringPreferencesKey("relay_last_result") // "ok:items:bytes" | "err:msg"

    /** 配置格式版本；缺失或小于 [RELAY_SCHEMA_VERSION] 就要跑一次迁移。 */
    val SCHEMA_VERSION = intPreferencesKey("relay_schema_version")
}

/** 当前配置格式版本。递增它并补一条迁移，就能再改一次历史默认值。 */
internal const val RELAY_SCHEMA_VERSION = 1

/**
 * 一次性把历史遗留的「模拟中转」迁成局域网中转。
 *
 * 旧版本把 MOCK 当默认并落盘成 `relay_mode="mock"`。后来改成「默认 LAN」时只改了读取侧的
 * 兜底分支（`if (mode == "mock") MOCK else LAN`），于是**存过这个键的设备升级多少次都还是 MOCK**：
 * 不监听、不组播宣告、不触网，桌面端既发现不了也拉不到，而 App 界面上总开关还是开的，
 * 看着一切正常。2026-11-09 在 OPPO PHZ110（firstInstallTime 2026-01-01）上实测到这个状态。
 *
 * 迁移只在版本键落后时跑一次，跑完把版本键写满。所以用户在**当前版本**里主动选回模拟中转
 * 会被保留，不会下次启动又被拽回 LAN。
 */
internal class RelayMockToLanMigration : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[RelayKeys.SCHEMA_VERSION] ?: 0) < RELAY_SCHEMA_VERSION

    override suspend fun migrate(currentData: Preferences): Preferences {
        if (currentData[RelayKeys.MODE] == "mock") {
            // 留一条可取证的痕迹：装机后靠它区分「迁移真的跑了」和「这台设备本来就是 LAN」。
            Log.i(TAG, "历史遗留的模拟中转已迁移为局域网中转（schema=$RELAY_SCHEMA_VERSION）")
        }
        return currentData.toMutablePreferences().apply {
            this[RelayKeys.MODE] = "lan"
            this[RelayKeys.SCHEMA_VERSION] = RELAY_SCHEMA_VERSION
        }.toPreferences()
    }

    override suspend fun cleanUp() = Unit

    private companion object {
        const val TAG = "RelayPrefs"
    }
}

/**
 * 落盘 Preferences → 运行配置。抽成纯函数是为了让迁移门能走**生产读取路径**，
 * 而不是在测试里把这段判断复刻一遍（复刻的话改坏了读取侧门照样绿）。
 */
internal fun relayConfigFrom(p: Preferences): RelayConfig = RelayConfig(
    // 默认 LAN：只有用户显式存过 "mock" 才回退到调试模式。
    // 历史遗留的 "mock" 已由 [RelayMockToLanMigration] 在读取前迁掉。
    mode = if (p[RelayKeys.MODE] == "mock") RelayMode.MOCK else RelayMode.LAN,
    enabled = p[RelayKeys.ENABLED] ?: true,
    autoSync = p[RelayKeys.AUTO_SYNC] ?: false,
    intervalMinutes = p[RelayKeys.INTERVAL] ?: 15,
    serverUrl = p[RelayKeys.SERVER_URL] ?: "http://192.168.x.x:18765",
    sourceAlias = p[RelayKeys.ALIAS] ?: "手环管家",
    selectedDeviceId = p[RelayKeys.SELECTED_DEVICE],
)

class RelayPrefs(private val context: Context) {

    val config: Flow<RelayConfig> = context.relayDataStore.data.map { relayConfigFrom(it) }

    val lastSyncAt: Flow<Long?> = context.relayDataStore.data.map { it[RelayKeys.LAST_SYNC_AT] }
    val lastResult: Flow<String?> = context.relayDataStore.data.map { it[RelayKeys.LAST_RESULT] }

    suspend fun saveConfig(cfg: RelayConfig) {
        context.relayDataStore.edit { p ->
            p[RelayKeys.MODE] = if (cfg.mode == RelayMode.LAN) "lan" else "mock"
            p[RelayKeys.ENABLED] = cfg.enabled
            p[RelayKeys.AUTO_SYNC] = cfg.autoSync
            p[RelayKeys.INTERVAL] = cfg.intervalMinutes
            p[RelayKeys.SERVER_URL] = cfg.serverUrl
            p[RelayKeys.ALIAS] = cfg.sourceAlias
            // 用户显式保存即视为当前格式：此后迁移不再覆盖他的选择。
            p[RelayKeys.SCHEMA_VERSION] = RELAY_SCHEMA_VERSION
            if (cfg.selectedDeviceId == null) {
                p.remove(RelayKeys.SELECTED_DEVICE)
            } else {
                p[RelayKeys.SELECTED_DEVICE] = cfg.selectedDeviceId
            }
        }
    }

    /** 记一次同步结果：时间戳 + 原始结果串（重启后能还原成 [RelayPushResult]）。 */
    suspend fun recordSync(atMillis: Long, rawResult: String) {
        context.relayDataStore.edit {
            it[RelayKeys.LAST_SYNC_AT] = atMillis
            it[RelayKeys.LAST_RESULT] = rawResult
        }
    }
}