package com.ted.shouhuan.data

/** 睡眠分期。手环活动数据里的 rawKind 映射到这四个状态。 */
enum class SleepStage { AWAKE, LIGHT, DEEP, REM }

/** 心率采样点。minuteOfDay 是当天的第几分钟，便于直接铺在时间轴上。 */
data class HeartRatePoint(val minuteOfDay: Int, val bpm: Int)

/**
 * 一条分钟级心率样本，[BandPrefs] 持久化（桌面控件的心率曲线就吃这份数据）。
 *
 * 来源是手环活动同步：分钟样本里 heartRate > 0 的那些分钟。epochMillis 用
 * 本地时区换算，和睡眠夜的 epochDay 口径一致 —— 手环时钟连上时同步过。
 */
data class HeartRateSample(
    /** epoch 毫秒。 */
    val atMillis: Long,
    val bpm: Int,
)

data class SleepStageShare(val stage: SleepStage, val minutes: Int)

/**
 * 一晚睡眠的可持久化形态（[BandPrefs] 存储，不设条数上限）。
 *
 * epochDay 是醒来那天的 LocalDate.toEpochDay()；bed/wake 是「当天第几分钟」，
 * 入睡那侧跨零点属正常（如 23:41），展示时直接按钟点格式化。
 * totalMinutes 只含深睡+浅睡+REM，清醒分钟单独记 —— 和主流手环 App 的口径一致。
 */
data class SleepNightRecord(
    val epochDay: Long,
    val totalMinutes: Int,
    val score: Int,
    val bedMinutes: Int,
    val wakeMinutes: Int,
    val deepMinutes: Int,
    val lightMinutes: Int,
    val remMinutes: Int,
    val awakeMinutes: Int,
)

/** 手环连接与电量状态。 */
data class BandStatus(
    val name: String,
    val mac: String,
    val connected: Boolean,
    val batteryPercent: Int,
    val firmware: String,
    val authKeyConfigured: Boolean,
)

/** 设备上发生的一条通知。 */
data class BandNotification(
    val appName: String,
    val title: String,
    val body: String,
    val timeLabel: String,
    val forwarded: Boolean,
    /**
     * 来源应用的包名 —— 「最近推送」快捷加入白/黑名单要靠它精确定位应用。
     * 老记录没有这个字段（存的时候还没加），为空时界面按应用名兜底反查。
     */
    val packageName: String = "",
    /**
     * 未转发的原因（"勿扰时段内"、"常驻/前台服务通知"等），仅 forwarded=false 时有值。
     * 白名单之前的静默丢弃点（仅锁屏、勿扰、常驻标志等）靠它变成可见；
     * 老记录没有这个字段，为空时界面回退显示「未推送」。
     */
    val dropReason: String = "",
)

/** 可被转发的应用。 */
data class AppRule(
    val packageName: String,
    val appName: String,
    val enabled: Boolean,
    /** 是否把通知正文一起推到手环；false = 只推应用名 + 标题。 */
    val showDetail: Boolean = true,
)

/** 手机上装的应用 —— 「添加应用」列表里可选的那一份。 */
data class InstalledApp(val packageName: String, val label: String)
/**
 * 一条关键词过滤规则。
 *
 * 关键词分白名单 / 黑名单两份，**同时存在、同时生效**（不像「允许转发的应用」那样白/黑二选一）：
 *   - 黑名单：命中就不转发（优先级最高）；
 *   - 白名单：命中才转发 —— 只对「生效应用」里出现过规则的那些应用形成约束，
 *     没被任何白名单规则覆盖的应用不受影响（否则会误伤整个应用）。
 *
 * [packages] 为空 = 对所有应用生效；非空 = 只对这几个包名生效（可附着 0 到多个应用）。
 */
data class KeywordRule(
    val keyword: String,
    val packages: List<String> = emptyList(),
) {
    /** 这条规则是否作用于 [pkg]。 */
    fun appliesTo(pkg: String): Boolean = packages.isEmpty() || pkg in packages

    /** 标题或正文是否命中（忽略大小写）。 */
    fun hits(title: String, body: String): Boolean =
        title.contains(keyword, ignoreCase = true) || body.contains(keyword, ignoreCase = true)
}

/**
 * 内置的敏感信息识别类型（敏感信息过滤用）。
 *
 * 标题 / 正文里出现符合 [pattern] 的内容就算命中 —— 这类内容推到手腕上等于把验证码、
 * 卡号摊开给旁边人看。每种类型都能单独开关，也能单独限定生效应用。
 */
enum class SensitiveKind(val label: String, val hint: String, val pattern: Regex) {
    CODE(
        label = "验证码 / 动态密码",
        hint = "短信验证码、动态口令（4–8 位数字，前面带「验证码」等字样）",
        pattern = Regex(
            "(验证码|校验码|动态码|动态密码|一次性密码|短信密码|口令|验证数字)[^\\d]{0,12}\\d{4,8}" +
                "|(verification|security|one[- ]?time)?\\s*(code|password|otp)\\D{0,12}\\d{4,8}",
            RegexOption.IGNORE_CASE,
        ),
    ),
    ID_CARD(
        label = "身份证号",
        hint = "17 位数字 + 1 位数字或 X",
        pattern = Regex("(?<!\\d)\\d{17}[\\dXx](?!\\d)"),
    ),
    BANK_CARD(
        label = "银行卡号",
        hint = "16–19 位连续数字",
        pattern = Regex("(?<!\\d)\\d{16,19}(?!\\d)"),
    ),
    PHONE(
        label = "手机号",
        hint = "中国大陆 11 位手机号",
        pattern = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
    ),
}

/**
 * 一条敏感信息过滤规则：内置识别类型（[kind] 非空）或自定义敏感词（[keyword]）。
 *
 * 和关键词一样可以附着 0 到多个应用（[packages] 为空 = 全部应用）。
 */
data class SensitiveRule(
    val kind: SensitiveKind? = null,
    val keyword: String = "",
    val enabled: Boolean = true,
    val packages: List<String> = emptyList(),
) {
    /** 稳定标识：内置用类型名，自定义用 "c:词"。 */
    val id: String get() = kind?.name ?: "c:$keyword"

    val label: String get() = kind?.label ?: keyword

    fun appliesTo(pkg: String): Boolean = packages.isEmpty() || pkg in packages

    /** 文本是否命中（自定义敏感词按忽略大小写的子串匹配）。 */
    fun hits(text: String): Boolean {
        val k = kind ?: return text.contains(keyword, ignoreCase = true)
        return k.pattern.containsMatchIn(text)
    }
}



/**
 * 一次手环电量读数。
 *
 * 只记「变了的时候」：手环电量按 1% 跳，同一个数字重复记没有信息量，还白灌
 * DataStore。有了时间点 + 电量，相邻两点的差就能算出「多久耗多少电」。
 */
data class BatterySample(val atMillis: Long, val percent: Int)

/**
 * 一次完成的测量结果。
 *
 * 光记一个 BPM 不够用：回头看记录时还想知道「这是什么时候测的、等了多久」。
 * 所以时间戳和耗时一起留下来，界面直接摆出来，不用靠猜。
 *
 * 放在 data 层（而不是心率页）是因为它要被 [BandPrefs] 持久化 —— 换了界面它还得在。
 */
data class MeasureResult(
    val bpm: Int,
    /** 拿到读数的那一刻（epoch 毫秒）。 */
    val finishedAtMillis: Long,
    /** 从点下「测量」到拿到读数花了多久（秒）。 */
    val durationSec: Int,
)

