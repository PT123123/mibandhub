package com.ted.shouhuan.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.bandDataStore: DataStore<Preferences> by preferencesDataStore(name = "band")

/**
 * 设备配置的本地存储。
 *
 * 注意：AuthKey 等同于手环的控制权，这里和 Gadgetbridge 一样存在应用私有目录里，
 * 不上云、不导出。卸载应用即一并清除。
 */
class BandPrefs(private val context: Context) {

    private object Keys {
        val MAC = stringPreferencesKey("device_mac")
        val NAME = stringPreferencesKey("device_name")
        val AUTH_KEY = stringPreferencesKey("auth_key")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val FORWARD_NOTIFICATIONS = booleanPreferencesKey("forward_notifications")
        val HAS_DEVICE = booleanPreferencesKey("has_device")
    }

    val mac: Flow<String?> = context.bandDataStore.data.map { it[Keys.MAC] }
    val name: Flow<String?> = context.bandDataStore.data.map { it[Keys.NAME] }
    val authKey: Flow<String?> = context.bandDataStore.data.map { it[Keys.AUTH_KEY] }
    val hasDevice: Flow<Boolean> = context.bandDataStore.data.map { it[Keys.HAS_DEVICE] ?: false }
    val autoConnect: Flow<Boolean> = context.bandDataStore.data.map { it[Keys.AUTO_CONNECT] ?: true }
    val forwardNotifications: Flow<Boolean> =
        context.bandDataStore.data.map { it[Keys.FORWARD_NOTIFICATIONS] ?: true }

    suspend fun saveDevice(mac: String, name: String, authKey: String) {
        context.bandDataStore.edit {
            it[Keys.MAC] = mac
            it[Keys.NAME] = name
            it[Keys.AUTH_KEY] = authKey
            it[Keys.HAS_DEVICE] = true
        }
    }

    suspend fun setAutoConnect(enabled: Boolean) {
        context.bandDataStore.edit { it[Keys.AUTO_CONNECT] = enabled }
    }

    suspend fun setForwardNotifications(enabled: Boolean) {
        context.bandDataStore.edit { it[Keys.FORWARD_NOTIFICATIONS] = enabled }
    }

    /** 忘记设备：把密钥一并抹掉。 */
    suspend fun forgetDevice() {
        context.bandDataStore.edit { it.clear() }
    }
}
