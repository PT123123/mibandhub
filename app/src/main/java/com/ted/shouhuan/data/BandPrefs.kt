package com.ted.shouhuan.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

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

        // ---- 心率分钟样本（桌面控件的心率曲线数据源）----
        val HEART_RATE_SERIES = stringPreferencesKey("heart_rate_series")

        /** 手环同步来的真实睡眠是否已经落过盘 —— 决定演示种子还能不能播种。 */
        val SLEEP_REAL_SYNCED = booleanPreferencesKey("sleep_real_synced")

        /** 记录是否由 v2 解析（kind/强度语义修正）产生 —— 之前的整批数据不可信。 */
        val SLEEP_PARSER_V2 = booleanPreferencesKey("sleep_parser_v2")

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

        /** 真实推到手环的通知记录（最近推送页），新的在前。 */
        val RECENT_NOTIFICATIONS = stringPreferencesKey("recent_notifications")

        // ---- 睡眠监测（手机侧开关）：控制应用打开时是否自动拉取睡眠。手环睡眠是
        // 硬件常开的，协议层没有关闭命令，所以这里只管应用自己的自动同步行为。----
        val SLEEP_MONITOR = booleanPreferencesKey("sleep_monitor")

        // ---- 手环本机设置（连接成功后整套下发，字节协议见 proto/BandSettings）----
        val SET_WEAR_LEFT = booleanPreferencesKey("band_wear_left")
        val SET_LIFT_WAKE = booleanPreferencesKey("band_lift_wake")
        val SET_SWIPE_UNLOCK = booleanPreferencesKey("band_swipe_unlock")
        val SET_DISCONNECT_ALERT = booleanPreferencesKey("band_disconnect_alert")
        val SET_DND_MODE = stringPreferencesKey("band_dnd_mode")
        val SET_DND_START = intPreferencesKey("band_dnd_start")
        val SET_DND_END = intPreferencesKey("band_dnd_end")
        val SET_NIGHT_MODE = stringPreferencesKey("band_night_mode")
        val SET_NIGHT_START = intPreferencesKey("band_night_start")
        val SET_NIGHT_END = intPreferencesKey("band_night_end")
        val MENU_ORDER = stringPreferencesKey("band_menu_order")
        val SHORTCUT_ORDER = stringPreferencesKey("band_shortcut_order")

        // ---- 手机状态提醒（手机这边发生事件 → 发到手环）----
        val REMIND_ON_CONNECT = booleanPreferencesKey("remind_on_connect")
        val REMIND_LOW_BATTERY = booleanPreferencesKey("remind_low_battery")
        val REMIND_LOW_BATTERY_PCT = intPreferencesKey("remind_low_battery_pct")
        val REMIND_FULLY_CHARGED = booleanPreferencesKey("remind_fully_charged")
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
    // 唯一来源是手环同步（DeviceViewModel → [importSleepNights]），
    // 应用自己不生成任何演示数据。
    //
    // 不设条数上限：一年 365 行 × ~40 字节，几十年的量也占不满 DataStore。
    // ------------------------------------------------------------------

    /** 全部睡眠夜记录，新的（epochDay 大的）在前。 */
    val sleepHistory: Flow<List<SleepNightRecord>> =
        context.bandDataStore.data.map { decodeSleepHistory(it[Keys.SLEEP_HISTORY]) }

    /**
     * 清掉历史版本播种过的演示睡眠数据。
     *
     * 早期版本在首次启动时会写近 180 天的演示记录；没有 [SLEEP_REAL_SYNCED]
     * 标记的存储里只剩这些假数据 —— 升级后静默删除一次，真数据照常不受影响。
     */
    suspend fun removeDemoSleep() {
        context.bandDataStore.edit { prefs ->
            if (prefs[Keys.SLEEP_REAL_SYNCED] != true) {
                prefs.remove(Keys.SLEEP_HISTORY)
            }
        }
    }

    /**
     * 导入手环同步来的真实睡眠。
     *
     * v2 解析之前入库的记录全部不可信（旧解析把 0x80 基础位当睡眠旗标，
     * 产出的「睡眠夜」把清醒也算成深睡）—— 第一批 v2 数据到达时整份清掉重来，
     * 和演示种子的退役同款处理。之后的导入按天合并（[replaceSleepNight]）。
     */
    suspend fun importSleepNights(nights: List<SleepNightRecord>) {
        context.bandDataStore.edit { prefs ->
            if (prefs[Keys.SLEEP_PARSER_V2] != true) {
                prefs.remove(Keys.SLEEP_HISTORY)
                prefs[Keys.SLEEP_PARSER_V2] = true
                prefs[Keys.SLEEP_REAL_SYNCED] = true
            } else if (prefs[Keys.SLEEP_REAL_SYNCED] != true) {
                prefs.remove(Keys.SLEEP_HISTORY)
                prefs[Keys.SLEEP_REAL_SYNCED] = true
            }
        }
        nights.forEach { replaceSleepNight(it) }
    }

    /**
     * 写入/覆盖一晚记录（真实同步接通后用）。
     *
     * 同一醒来日只留一条，两份记录取**总分钟数多的** —— 不是简单地「新的覆盖旧的」：
     * 同步不删手环数据后，拉取窗口的起点可能恰好落在某夜睡眠中间（比如凌晨打开 app
     * 时 7 天窗口的边界），只推来半截夜；手环没删过数据，窗口起点再早一轮时推来的
     * 必然是完整夜。半截的样本数一定比完整的少，取多者即可保证完整的记录不被冲掉。
     */
    suspend fun replaceSleepNight(record: SleepNightRecord) {
        context.bandDataStore.edit { prefs ->
            val next = (listOf(record) + decodeSleepHistory(prefs[Keys.SLEEP_HISTORY]))
                .groupBy { it.epochDay }
                .map { (_, sameDay) -> sameDay.maxBy { it.totalMinutes } }
                .sortedByDescending { it.epochDay }
            prefs[Keys.SLEEP_HISTORY] = encodeSleepHistory(next)
        }
    }

    /** 清空睡眠史。 */
    suspend fun clearSleepHistory() {
        context.bandDataStore.edit { it.remove(Keys.SLEEP_HISTORY) }
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
    // 心率分钟样本
    //
    // 唯一来源同样是手环同步：分钟样本里 heartRate > 0 的分钟（DeviceViewModel
    // 落盘）。只给桌面控件的心率曲线用，应用内界面暂时不消费它。
    //
    // 只留最近 [HR_KEEP_DAYS] 天：曲线只画近 24 小时，多留一天是给
    // 「几天没同步」的场景垫底；再多是白占 DataStore（全量 ~60KB 级别）。
    // ------------------------------------------------------------------

    /** 心率样本的保留天数：曲线窗口 24 小时 + 一天余量。 */
    private val hrKeepDays = 3L

    /** 「最近推送」最多留的记录条数。 */
    private val recentKeep = 20

    /** 全部心率样本，按时间升序。 */
    val heartRateSamples: Flow<List<HeartRateSample>> =
        context.bandDataStore.data.map { decodeHeartRate(it[Keys.HEART_RATE_SERIES]) }

    /**
     * 用一批新样本替换同刻旧值后整体入库（同刻重复推送以新的为准），
     * 顺手裁掉 [HR_KEEP_DAYS] 天之前的存量。
     */
    suspend fun replaceHeartRateSamples(samples: List<HeartRateSample>) {
        context.bandDataStore.edit { prefs ->
            val cutoff = System.currentTimeMillis() - hrKeepDays * 24 * 60 * 60 * 1000L
            val merged = (samples.asSequence() + decodeHeartRate(prefs[Keys.HEART_RATE_SERIES]))
                .filter { it.atMillis >= cutoff && it.bpm in 20..250 }
                .distinctBy { it.atMillis }
                .sortedBy { it.atMillis }
                .toList()
            if (merged.isEmpty()) {
                prefs.remove(Keys.HEART_RATE_SERIES)
            } else {
                prefs[Keys.HEART_RATE_SERIES] = encodeHeartRate(merged)
            }
        }
    }

    private fun encodeHeartRate(list: List<HeartRateSample>): String =
        list.joinToString("\n") { "${it.atMillis},${it.bpm}" }

    private fun decodeHeartRate(raw: String?): List<HeartRateSample> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val p = line.split(',')
            if (p.size != 2) return@mapNotNull null
            val at = p[0].toLongOrNull() ?: return@mapNotNull null
            val bpm = p[1].toIntOrNull() ?: return@mapNotNull null
            HeartRateSample(at, bpm)
        }.toList()
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

    /** 真实推到手环的通知记录，新的在前（无记录时为空，不放演示条目）。 */
    val recentNotifications: Flow<List<BandNotification>> =
        context.bandDataStore.data.map { decodeRecentNotifications(it[Keys.RECENT_NOTIFICATIONS]) }

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

    /**
     * 记一条真实推到手环的通知，新的在前，只留最近 [recentKeep] 条。
     *
     * title/body 里可能出现任意字符（含换行、逗号），用 org.json 编码最稳，
     * 不必像睡眠史那样赌内容里没有分隔符。
     */
    suspend fun recordNotification(notification: BandNotification) {
        context.bandDataStore.edit { prefs ->
            val next = (listOf(notification) + decodeRecentNotifications(prefs[Keys.RECENT_NOTIFICATIONS]))
                .take(recentKeep)
            prefs[Keys.RECENT_NOTIFICATIONS] = encodeRecentNotifications(next)
        }
    }

    private fun encodeRecentNotifications(list: List<BandNotification>): String =
        JSONArray().apply {
            list.forEach { n ->
                put(
                    JSONObject().put("app", n.appName)
                        .put("title", n.title)
                        .put("body", n.body)
                        .put("time", n.timeLabel)
                        .put("ok", n.forwarded),
                )
            }
        }.toString()

    private fun decodeRecentNotifications(raw: String?): List<BandNotification> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BandNotification(
                    appName = o.getString("app"),
                    title = o.getString("title"),
                    body = o.getString("body"),
                    timeLabel = o.getString("time"),
                    forwarded = o.getBoolean("ok"),
                )
            }
        }.getOrElse { emptyList() }
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
    // 手环本机设置 + 手机状态提醒
    //
    // 只管存储；下发时机有两处 —— 用户当场改动（DeviceViewModel）和
    // 连接成功后整套推送（BandService）。默认值全部对齐 MB5 出厂状态，
    // 首次连接就整套下发也只是把手环写回它本来就在的状态。
    // ------------------------------------------------------------------

    /** 睡眠监测（手机侧开关）：打开时应用会在打开后自动拉取近几天睡眠，默认开。 */
    val sleepMonitoring: Flow<Boolean> =
        context.bandDataStore.data.map { it[Keys.SLEEP_MONITOR] ?: true }

    /** 佩戴手，true = 左手（出厂默认）。 */
    val wearLeft: Flow<Boolean> = context.bandDataStore.data.map { it[Keys.SET_WEAR_LEFT] ?: true }

    /** 抬腕亮屏，出厂默认开。 */
    val liftWake: Flow<Boolean> = context.bandDataStore.data.map { it[Keys.SET_LIFT_WAKE] ?: true }

    /** 滑动解锁，出厂默认关。 */
    val swipeUnlock: Flow<Boolean> =
        context.bandDataStore.data.map { it[Keys.SET_SWIPE_UNLOCK] ?: false }

    /** 手环与手机断开蓝牙时手环自己提醒，出厂默认关。 */
    val disconnectAlert: Flow<Boolean> =
        context.bandDataStore.data.map { it[Keys.SET_DISCONNECT_ALERT] ?: false }

    /** 勿扰模式："off" / "scheduled" / "automatic"。 */
    val dndMode: Flow<String> = context.bandDataStore.data.map { it[Keys.SET_DND_MODE] ?: "off" }
    val dndStartMinute: Flow<Int> =
        context.bandDataStore.data.map { it[Keys.SET_DND_START] ?: 22 * 60 }
    val dndEndMinute: Flow<Int> =
        context.bandDataStore.data.map { it[Keys.SET_DND_END] ?: 7 * 60 }

    /** 夜间模式："off" / "scheduled" / "sunset"。 */
    val nightMode: Flow<String> = context.bandDataStore.data.map { it[Keys.SET_NIGHT_MODE] ?: "off" }
    val nightStartMinute: Flow<Int> =
        context.bandDataStore.data.map { it[Keys.SET_NIGHT_START] ?: 22 * 60 }
    val nightEndMinute: Flow<Int> =
        context.bandDataStore.data.map { it[Keys.SET_NIGHT_END] ?: 7 * 60 }

    /** 主菜单顺序（Item.key 逗号分隔）。没存过 = 还没动过，用出厂默认。 */
    val menuOrder: Flow<List<String>?> =
        context.bandDataStore.data.map { it[Keys.MENU_ORDER]?.split(',')?.filter { k -> k.isNotBlank() } }

    /** 快捷方式顺序（Item.key 逗号分隔）。 */
    val shortcutOrder: Flow<List<String>?> =
        context.bandDataStore.data.map { it[Keys.SHORTCUT_ORDER]?.split(',')?.filter { k -> k.isNotBlank() } }

    /** 连接成功后是否在手环上提醒一声。 */
    val remindOnConnect: Flow<Boolean> =
        context.bandDataStore.data.map { it[Keys.REMIND_ON_CONNECT] ?: false }

    /** 手机低电量提醒：开关与阈值（百分比，0 表示从未设置过）。 */
    val remindLowBattery: Flow<Boolean> =
        context.bandDataStore.data.map { it[Keys.REMIND_LOW_BATTERY] ?: false }
    val remindLowBatteryPct: Flow<Int> =
        context.bandDataStore.data.map { it[Keys.REMIND_LOW_BATTERY_PCT] ?: 15 }

    /** 手机充满电提醒。 */
    val remindFullyCharged: Flow<Boolean> =
        context.bandDataStore.data.map { it[Keys.REMIND_FULLY_CHARGED] ?: false }

    suspend fun setWearLeft(left: Boolean) {
        context.bandDataStore.edit { it[Keys.SET_WEAR_LEFT] = left }
    }

    suspend fun setSleepMonitoring(enabled: Boolean) {
        context.bandDataStore.edit { it[Keys.SLEEP_MONITOR] = enabled }
    }

    suspend fun setLiftWake(enabled: Boolean) {
        context.bandDataStore.edit { it[Keys.SET_LIFT_WAKE] = enabled }
    }

    suspend fun setSwipeUnlock(enabled: Boolean) {
        context.bandDataStore.edit { it[Keys.SET_SWIPE_UNLOCK] = enabled }
    }

    suspend fun setDisconnectAlert(enabled: Boolean) {
        context.bandDataStore.edit { it[Keys.SET_DISCONNECT_ALERT] = enabled }
    }

    suspend fun setDndSetting(mode: String, startMinute: Int, endMinute: Int) {
        context.bandDataStore.edit {
            it[Keys.SET_DND_MODE] = mode
            it[Keys.SET_DND_START] = clampMinuteOfDay(startMinute)
            it[Keys.SET_DND_END] = clampMinuteOfDay(endMinute)
        }
    }

    suspend fun setNightSetting(mode: String, startMinute: Int, endMinute: Int) {
        context.bandDataStore.edit {
            it[Keys.SET_NIGHT_MODE] = mode
            it[Keys.SET_NIGHT_START] = clampMinuteOfDay(startMinute)
            it[Keys.SET_NIGHT_END] = clampMinuteOfDay(endMinute)
        }
    }

    suspend fun setMenuOrder(keys: List<String>) {
        context.bandDataStore.edit {
            if (keys.isEmpty()) it.remove(Keys.MENU_ORDER)
            else it[Keys.MENU_ORDER] = keys.joinToString(",")
        }
    }

    suspend fun setShortcutOrder(keys: List<String>) {
        context.bandDataStore.edit {
            if (keys.isEmpty()) it.remove(Keys.SHORTCUT_ORDER)
            else it[Keys.SHORTCUT_ORDER] = keys.joinToString(",")
        }
    }

    suspend fun setRemindOnConnect(enabled: Boolean) {
        context.bandDataStore.edit { it[Keys.REMIND_ON_CONNECT] = enabled }
    }

    suspend fun setRemindLowBattery(enabled: Boolean, thresholdPct: Int) {
        context.bandDataStore.edit {
            it[Keys.REMIND_LOW_BATTERY] = enabled
            it[Keys.REMIND_LOW_BATTERY_PCT] = thresholdPct.coerceIn(5, 50)
        }
    }

    suspend fun setRemindFullyCharged(enabled: Boolean) {
        context.bandDataStore.edit { it[Keys.REMIND_FULLY_CHARGED] = enabled }
    }

    private fun clampMinuteOfDay(minute: Int): Int = ((minute % 1440) + 1440) % 1440

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
