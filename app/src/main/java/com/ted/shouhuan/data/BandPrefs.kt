package com.ted.shouhuan.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.bandDataStore: DataStore<Preferences> by preferencesDataStore(name = "band")

/**
 * 设备配置、测量/睡眠历史与通知设置的本地存储。
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
        val MEASURE_HISTORY = stringPreferencesKey("measure_history")

        // ---- 睡眠历史 ----
        val SLEEP_HISTORY = stringPreferencesKey("sleep_history")

        // ---- 通知详细设置 ----
        val DND_ENABLED = booleanPreferencesKey("dnd_enabled")
        val DND_START = intPreferencesKey("dnd_start")
        val DND_END = intPreferencesKey("dnd_end")
        val KEYWORD_BLACKLIST = booleanPreferencesKey("keyword_blacklist")
        val KEYWORDS = stringPreferencesKey("keywords")
        val DEDUPE_ENABLED = booleanPreferencesKey("dedupe_enabled")
        val DEDUPE_SECONDS = intPreferencesKey("dedupe_seconds")
        val NOTIFY_SHOW_APP_NAME = booleanPreferencesKey("notify_show_app_name")
        val NOTIFY_INCLUDE_BODY = booleanPreferencesKey("notify_include_body")
        val NOTIFY_VIBRATION = stringPreferencesKey("notify_vibration")
        val APP_RULES = stringPreferencesKey("app_rules")
    }

    val mac: Flow<String?> = context.bandDataStore.data.map { it[Keys.MAC] }
    val name: Flow<String?> = context.bandDataStore.data.map { it[Keys.NAME] }
    val authKey: Flow<String?> = context.bandDataStore.data.map { it[Keys.AUTH_KEY] }
    val hasDevice: Flow<Boolean> = context.bandDataStore.data.map { it[Keys.HAS_DEVICE] ?: false }
    val autoConnect: Flow<Boolean> = context.bandDataStore.data.map { it[Keys.AUTO_CONNECT] ?: true }
    val forwardNotifications: Flow<Boolean> =
        context.bandDataStore.data.map { it[Keys.FORWARD_NOTIFICATIONS] ?: true }

    /** 测量记录，新的在前。不设上限 —— 用户明确要求历史存多久算多久。 */
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

    /** 记一次测量结果，全部保留。 */
    suspend fun recordMeasure(result: MeasureResult) {
        context.bandDataStore.edit { prefs ->
            prefs[Keys.MEASURE_HISTORY] = encodeHistory(
                listOf(result) + decodeHistory(prefs[Keys.MEASURE_HISTORY]),
            )
        }
    }

    /** 清空测量记录。 */
    suspend fun clearMeasureHistory() {
        context.bandDataStore.edit { it.remove(Keys.MEASURE_HISTORY) }
    }

    /**
     * 忘记设备：把设备身份与密钥一并抹掉。
     *
     * 只删设备相关的键，**不动测量记录** —— 「不要这台手环了」和「把心率历史也删掉」
     * 是两件事，顺手清空等于让用户莫名其妙丢数据。
     */
    suspend fun forgetDevice() {
        context.bandDataStore.edit {
            it.remove(Keys.MAC)
            it.remove(Keys.NAME)
            it.remove(Keys.AUTH_KEY)
            it.remove(Keys.HAS_DEVICE)
        }
    }

    // ------------------------------------------------------------------
    // 睡眠历史
    //
    // 协议层的睡眠同步还没接通，先用按日期确定性生成的演示数据把界面撑起来
    //（和 DemoData 的约定一致）。存进 DataStore 而不是每次现算，是为了让
    // 「点击某一天 / 筛选范围 / 每晚明细」这套交互先跑在真实存储上 ——
    // 接真实数据源时只需要 replaceSleepHistory()，界面一行不用改。
    //
    // 不设条数上限：一年 365 行 × ~40 字节，几十年的量也占不满 DataStore。
    // ------------------------------------------------------------------

    /** 全部睡眠夜记录，新的（epochDay 大的）在前。 */
    val sleepHistory: Flow<List<SleepNightRecord>> =
        context.bandDataStore.data.map { decodeSleepHistory(it[Keys.SLEEP_HISTORY]) }

    /**
     * 首次启动时播种演示睡眠史（近 180 天）。
     *
     * 生成是确定性的：种子 = epochDay，同一天无论重跑多少次结果一致；
     * 但只在键不存在时写一次，之后用户界面上的任何展示都来自存储。
     */
    suspend fun ensureSleepSeeded() {
        context.bandDataStore.edit { prefs ->
            if (prefs[Keys.SLEEP_HISTORY] == null) {
                prefs[Keys.SLEEP_HISTORY] = encodeSleepHistory(seedDemoSleep())
            }
        }
    }

    /** 写入/覆盖一晚记录（真实同步接通后用；同一天重复写按覆盖处理）。 */
    suspend fun replaceSleepNight(record: SleepNightRecord) {
        context.bandDataStore.edit { prefs ->
            val next = (listOf(record) + decodeSleepHistory(prefs[Keys.SLEEP_HISTORY]))
                .distinctBy { it.epochDay }
                .sortedByDescending { it.epochDay }
            prefs[Keys.SLEEP_HISTORY] = encodeSleepHistory(next)
        }
    }

    /** 清空睡眠史（接真实数据源前清理演示数据用）。 */
    suspend fun clearSleepHistory() {
        context.bandDataStore.edit { it.remove(Keys.SLEEP_HISTORY) }
    }

    /** 近 180 天的演示睡眠。数值都在真机数据的合理区间内，周末睡得晚一些。 */
    private fun seedDemoSleep(): List<SleepNightRecord> {
        val today = LocalDate.now().toEpochDay()
        return (0 until 180).map { back ->
            val epochDay = today - back
            val rng = Random(epochDay * 31 + 7)
            val weekend = LocalDate.ofEpochDay(epochDay).dayOfWeek.value in listOf(6, 7)
            // 入睡：工作日 23:00 上下，周五/周六晚推后 ~50 分钟
            var bed = (23 * 60 + rng.nextInt(-35, 50)).let { if (weekend) it + 50 else it }
            if (rng.nextInt(10) == 0) bed += 60 // 偶尔熬夜
            val total = 385 + rng.nextInt(0, 120) - (if (rng.nextInt(9) == 0) rng.nextInt(40, 90) else 0)
            val awake = 6 + rng.nextInt(0, 22)
            val deep = (total * (0.21 + rng.nextDouble(0.0, 0.07))).toInt()
            val rem = (total * (0.18 + rng.nextDouble(0.0, 0.07))).toInt()
            val light = total - deep - rem
            val score = (62 + (total - 380) / 2.2 + rng.nextDouble(-6.0, 6.0)).toInt().coerceIn(52, 97)
            SleepNightRecord(
                epochDay = epochDay,
                totalMinutes = total,
                score = score,
                bedMinutes = ((bed % 1440) + 1440) % 1440,
                wakeMinutes = ((bed + total + awake) % 1440).toInt(),
                deepMinutes = deep,
                lightMinutes = light,
                remMinutes = rem,
                awakeMinutes = awake,
            )
        }.sortedByDescending { it.epochDay }
    }

    private fun encodeSleepHistory(list: List<SleepNightRecord>): String =
        list.joinToString("\n") {
            "${it.epochDay},${it.totalMinutes},${it.score},${it.bedMinutes},${it.wakeMinutes}," +
                "${it.deepMinutes},${it.lightMinutes},${it.remMinutes},${it.awakeMinutes}"
        }

    private fun decodeSleepHistory(raw: String?): List<SleepNightRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val p = line.split(',')
            if (p.size != 9) return@mapNotNull null
            val nums = p.map { it.toLongOrNull() ?: return@mapNotNull null }
            SleepNightRecord(
                epochDay = nums[0],
                totalMinutes = nums[1].toInt(),
                score = nums[2].toInt(),
                bedMinutes = nums[3].toInt(),
                wakeMinutes = nums[4].toInt(),
                deepMinutes = nums[5].toInt(),
                lightMinutes = nums[6].toInt(),
                remMinutes = nums[7].toInt(),
                awakeMinutes = nums[8].toInt(),
            )
        }.sortedByDescending { it.epochDay }.toList()
    }

    // ------------------------------------------------------------------
    // 通知详细设置
    //
    // 全部先落存储：转发协议接通时读的就是这份配置，界面不需要再动。
    // ------------------------------------------------------------------

    /** 勿扰时段开关。 */
    val dndEnabled: Flow<Boolean> = context.bandDataStore.data.map { it[Keys.DND_ENABLED] ?: false }

    /** 勿扰开始 / 结束，「当天第几分钟」。支持跨零点（如 22:00 – 07:30）。 */
    val dndStart: Flow<Int> = context.bandDataStore.data.map { it[Keys.DND_START] ?: 22 * 60 }
    val dndEnd: Flow<Int> = context.bandDataStore.data.map { it[Keys.DND_END] ?: 7 * 60 + 30 }

    /** 关键词模式：true = 黑名单（命中不转发），false = 白名单（命中才转发）。 */
    val keywordBlacklist: Flow<Boolean> =
        context.bandDataStore.data.map { it[Keys.KEYWORD_BLACKLIST] ?: false }

    /** 关键词列表，保持添加顺序。 */
    val keywords: Flow<List<String>> =
        context.bandDataStore.data.map { decodeLines(it[Keys.KEYWORDS]) }

    /** 同一应用 + 同一标题在 [dedupeSeconds] 内的重复通知只转发一次。 */
    val dedupeEnabled: Flow<Boolean> =
        context.bandDataStore.data.map { it[Keys.DEDUPE_ENABLED] ?: true }
    val dedupeSeconds: Flow<Int> =
        context.bandDataStore.data.map { it[Keys.DEDUPE_SECONDS] ?: 30 }

    /** 转发内容：应用名前缀 / 正文。 */
    val notifyShowAppName: Flow<Boolean> =
        context.bandDataStore.data.map { it[Keys.NOTIFY_SHOW_APP_NAME] ?: true }
    val notifyIncludeBody: Flow<Boolean> =
        context.bandDataStore.data.map { it[Keys.NOTIFY_INCLUDE_BODY] ?: true }

    /** 振动模式："standard" / "short" / "double"。 */
    val notifyVibration: Flow<String> =
        context.bandDataStore.data.map { it[Keys.NOTIFY_VIBRATION] ?: "standard" }

    /** 应用白名单。没有存过时回落到演示名单，勾选状态由用户改动后持久化。 */
    val appRules: Flow<List<AppRule>> =
        context.bandDataStore.data.map { prefs ->
            decodeAppRules(prefs[Keys.APP_RULES]) ?: DemoData.appRules()
        }

    suspend fun setDndEnabled(enabled: Boolean) {
        context.bandDataStore.edit { it[Keys.DND_ENABLED] = enabled }
    }

    suspend fun setDndWindow(startMinute: Int, endMinute: Int) {
        context.bandDataStore.edit {
            it[Keys.DND_START] = ((startMinute % 1440) + 1440) % 1440
            it[Keys.DND_END] = ((endMinute % 1440) + 1440) % 1440
        }
    }

    suspend fun setKeywordBlacklist(blacklist: Boolean) {
        context.bandDataStore.edit { it[Keys.KEYWORD_BLACKLIST] = blacklist }
    }

    suspend fun setKeywords(keywords: List<String>) {
        context.bandDataStore.edit { prefs ->
            if (keywords.isEmpty()) prefs.remove(Keys.KEYWORDS)
            else prefs[Keys.KEYWORDS] = keywords.joinToString("\n")
        }
    }

    suspend fun setDedupe(enabled: Boolean, seconds: Int) {
        context.bandDataStore.edit {
            it[Keys.DEDUPE_ENABLED] = enabled
            it[Keys.DEDUPE_SECONDS] = seconds
        }
    }

    suspend fun setNotifyContent(showAppName: Boolean, includeBody: Boolean) {
        context.bandDataStore.edit {
            it[Keys.NOTIFY_SHOW_APP_NAME] = showAppName
            it[Keys.NOTIFY_INCLUDE_BODY] = includeBody
        }
    }

    suspend fun setNotifyVibration(pattern: String) {
        context.bandDataStore.edit { it[Keys.NOTIFY_VIBRATION] = pattern }
    }

    suspend fun setAppRules(rules: List<AppRule>) {
        context.bandDataStore.edit { prefs ->
            prefs[Keys.APP_RULES] = rules.joinToString("\n") {
                "${it.packageName}|${it.appName}|${if (it.enabled) 1 else 0}"
            }
        }
    }

    private fun decodeAppRules(raw: String?): List<AppRule>? {
        if (raw.isNullOrBlank()) return null
        return raw.lineSequence().mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 3) return@mapNotNull null
            AppRule(p[0], p[1], p[2] == "1")
        }.toList()
    }

    private fun decodeLines(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
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
