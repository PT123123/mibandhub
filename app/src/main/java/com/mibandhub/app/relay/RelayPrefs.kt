package com.mibandhub.app.relay

import android.content.Context
import androidx.datastore.core.DataStore
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
private val Context.relayDataStore: DataStore<androidx.datastore.preferences.core.Preferences> by preferencesDataStore(name = "relay")

class RelayPrefs(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("relay_mode") // "mock" | "lan"
        val ENABLED = booleanPreferencesKey("relay_enabled")
        val AUTO_SYNC = booleanPreferencesKey("relay_auto_sync")
        val INTERVAL = intPreferencesKey("relay_interval_min")
        val SERVER_URL = stringPreferencesKey("relay_server_url")
        val ALIAS = stringPreferencesKey("relay_alias")
        val SELECTED_DEVICE = stringPreferencesKey("relay_device_id")
        val LAST_SYNC_AT = longPreferencesKey("relay_last_sync_at")
        val LAST_RESULT = stringPreferencesKey("relay_last_result") // "ok:items:bytes" | "err:msg"
    }

    val config: Flow<RelayConfig> = context.relayDataStore.data.map { p ->
        RelayConfig(
            mode = if (p[Keys.MODE] == "lan") RelayMode.LAN else RelayMode.MOCK,
            enabled = p[Keys.ENABLED] ?: true,
            autoSync = p[Keys.AUTO_SYNC] ?: false,
            intervalMinutes = p[Keys.INTERVAL] ?: 15,
            serverUrl = p[Keys.SERVER_URL] ?: "http://192.168.x.x:18765",
            sourceAlias = p[Keys.ALIAS] ?: "手环管家",
            selectedDeviceId = p[Keys.SELECTED_DEVICE],
        )
    }

    val lastSyncAt: Flow<Long?> = context.relayDataStore.data.map { it[Keys.LAST_SYNC_AT] }
    val lastResult: Flow<String?> = context.relayDataStore.data.map { it[Keys.LAST_RESULT] }

    suspend fun saveConfig(cfg: RelayConfig) {
        context.relayDataStore.edit { p ->
            p[Keys.MODE] = if (cfg.mode == RelayMode.LAN) "lan" else "mock"
            p[Keys.ENABLED] = cfg.enabled
            p[Keys.AUTO_SYNC] = cfg.autoSync
            p[Keys.INTERVAL] = cfg.intervalMinutes
            p[Keys.SERVER_URL] = cfg.serverUrl
            p[Keys.ALIAS] = cfg.sourceAlias
            if (cfg.selectedDeviceId == null) {
                p.remove(Keys.SELECTED_DEVICE)
            } else {
                p[Keys.SELECTED_DEVICE] = cfg.selectedDeviceId
            }
        }
    }

    /** 记一次同步结果：时间戳 + 原始结果串（重启后能还原成 [RelayPushResult]）。 */
    suspend fun recordSync(atMillis: Long, rawResult: String) {
        context.relayDataStore.edit {
            it[Keys.LAST_SYNC_AT] = atMillis
            it[Keys.LAST_RESULT] = rawResult
        }
    }
}
