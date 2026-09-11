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
 * 设备配置与测量记录的本地存储。
 *
 * 注意：AuthKey 等同于手环的控制权，这里和 Gadgetbridge 一样存在应用私有目录里，
 * 不上云、不导出。卸载应用即一并清除。
 */
class BandPrefs(private val context: Context) {

    private companion object {
        /** 测量记录最多留多少条 —— 超出就把最旧的丢掉。 */
        const val MEASURE_HISTORY_LIMIT = 20
    }

    private object Keys {
        val MAC = stringPreferencesKey("device_mac")
        val NAME = stringPreferencesKey("device_name")
        val AUTH_KEY = stringPreferencesKey("auth_key")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val FORWARD_NOTIFICATIONS = booleanPreferencesKey("forward_notifications")
        val HAS_DEVICE = booleanPreferencesKey("has_device")
        val MEASURE_HISTORY = stringPreferencesKey("measure_history")
    }

    val mac: Flow<String?> = context.bandDataStore.data.map { it[Keys.MAC] }
    val name: Flow<String?> = context.bandDataStore.data.map { it[Keys.NAME] }
    val authKey: Flow<String?> = context.bandDataStore.data.map { it[Keys.AUTH_KEY] }
    val hasDevice: Flow<Boolean> = context.bandDataStore.data.map { it[Keys.HAS_DEVICE] ?: false }
    val autoConnect: Flow<Boolean> = context.bandDataStore.data.map { it[Keys.AUTO_CONNECT] ?: true }
    val forwardNotifications: Flow<Boolean> =
        context.bandDataStore.data.map { it[Keys.FORWARD_NOTIFICATIONS] ?: true }

    /** 测量记录，新的在前。 */
    val measureHistory: Flow<List<MeasureResult>> =
        context.bandDataStore.data.map { decodeHistory(it[Keys.MEASURE_HISTORY]) }

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

    /** 记一次测量结果，只留最近 [MEASURE_HISTORY_LIMIT] 条。 */
    suspend fun recordMeasure(result: MeasureResult) {
        context.bandDataStore.edit { prefs ->
            val next = (listOf(result) + decodeHistory(prefs[Keys.MEASURE_HISTORY]))
                .take(MEASURE_HISTORY_LIMIT)
            prefs[Keys.MEASURE_HISTORY] = encodeHistory(next)
        }
    }

    /** 清空测量记录。 */
    suspend fun clearMeasureHistory() {
        context.bandDataStore.edit { it.remove(Keys.MEASURE_HISTORY) }
    }

    /** 忘记设备：把密钥一并抹掉。 */
    suspend fun forgetDevice() {
        context.bandDataStore.edit { it.clear() }
    }

    // ------------------------------------------------------------------
    // 测量记录的编解码
    //
    // 就三个整数，为它引一个 JSON 依赖不值当：一行一条、逗号分隔就够。
    // 解码时单条坏掉只丢那一条，不会把整份记录带崩。
    // ------------------------------------------------------------------

    private fun encodeHistory(list: List<MeasureResult>): String =
        list.joinToString("\n") { "${it.bpm},${it.finishedAtMillis},${it.durationSec}" }

    private fun decodeHistory(raw: String?): List<MeasureResult> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size != 3) return@mapNotNull null
            val bpm = parts[0].toIntOrNull() ?: return@mapNotNull null
            val finishedAt = parts[1].toLongOrNull() ?: return@mapNotNull null
            val duration = parts[2].toIntOrNull() ?: return@mapNotNull null
            MeasureResult(bpm, finishedAt, duration)
        }.toList()
    }
}
