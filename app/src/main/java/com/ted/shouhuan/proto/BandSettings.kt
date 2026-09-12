package com.ted.shouhuan.proto

/**
 * 手环本机设置的字节构造（Mi Band 3/4/5 通用，本机对的是 MB5）。
 *
 * 字节级依据 Gadgetbridge（version 0.8.x）：
 *   - `HuamiSupport.setDisplayItemsNew` —— 菜单/快捷方式顺序，走 chunked 通道（类型号 2）；
 *   - `HuamiSupport.setDisplayOnLiftWrist / setBandScreenUnlock / setDisconnectNotification /
 *     setDoNotDisturb / setNightMode` —— 各开关，直写配置特征（00000003）；
 *   - `HuamiSupport.setWearLocation` —— 佩戴手，写用户设置特征（00000008）；
 *   - 常量表：`HuamiService` / `MiBand3Service` / `HuamiMenuType`。
 *
 * 这些设置都是「写了就生效、手环自己持久化」，没有任何读取接口 ——
 * 手环上现在是什么状态只有手环知道，所以应用侧存一份自己的偏好，
 * 连接成功后整套下发一遍，让「应用里的设置 = 手环上的设置」。
 */
object BandSettings {

    // ------------------------------------------------------------------
    // 菜单项（GB HuamiMenuType.idLookup，key 与 GB 的 pref 键逐字一致）
    // ------------------------------------------------------------------

    enum class Item(val key: String, val id: Int, val label: String) {
        STATUS("status", 0x01, "状态"),
        HEART_RATE("hr", 0x02, "心率"),
        WORKOUT("workout", 0x03, "运动"),
        WEATHER("weather", 0x04, "天气"),
        NOTIFICATIONS("notifications", 0x06, "通知"),
        MORE("more", 0x07, "更多"),
        DND("dnd", 0x08, "勿扰"),
        ALARM("alarm", 0x09, "闹钟"),
        TAKE_PHOTO("takephoto", 0x0a, "拍照"),
        MUSIC("music", 0x0b, "音乐"),
        STOPWATCH("stopwatch", 0x0c, "秒表"),
        TIMER("timer", 0x0d, "计时器"),
        FIND_PHONE("findphone", 0x0e, "查找手机"),
        MUTE_PHONE("mutephone", 0x0f, "手机静音"),
        NFC("nfc", 0x10, "NFC"),
        ALIPAY("alipay", 0x11, "支付宝"),
        SETTINGS("settings", 0x13, "设置"),
        ACTIVITY("activity", 0x14, "活动记录"),
        EVENT_REMINDER("eventreminder", 0x15, "事件提醒"),
        COMPASS("compass", 0x16, "指南针"),
        PAI("pai", 0x19, "PAI"),
        WORLD_CLOCK("worldclock", 0x1a, "世界时钟"),
        STRESS("stress", 0x1c, "压力"),
        PERIOD("period", 0x1d, "女性健康"),
        GOAL("goal", 0x21, "目标"),
        SLEEP("sleep", 0x23, "睡眠"),
        SPO2("spo2", 0x24, "血氧"),
        EVENTS("events", 0x26, "日程"),
        BREATHING("breathing", 0x33, "呼吸"),
        ;

        companion object {
            fun fromKey(key: String): Item? = entries.firstOrNull { it.key == key }

            /** MB5 默认菜单（GB `R.array.pref_miband5_display_items_default`）。 */
            val DEFAULT_MENU = listOf(
                STATUS, PAI, HEART_RATE, NOTIFICATIONS, BREATHING, EVENT_REMINDER,
                WEATHER, WORKOUT, MORE, STRESS, PERIOD, NFC,
            )

            /** MB5 默认快捷方式（GB `R.array.pref_miband5_shortcuts_default`）。 */
            val DEFAULT_SHORTCUTS = listOf(NOTIFICATIONS, WEATHER, MUSIC)
        }
    }

    /**
     * 菜单/快捷方式顺序命令（GB setDisplayItemsNew）：
     *
     * ```
     * 0x1e [00 00 <menuType> <watchface>] [序号 00 <menuType> <id>] × N
     * ```
     *
     * watchface（表盘）固定占第 0 位 —— MB5 的 setDisplayItems/setShortcuts 都是
     * forceWatchface=true，不用我们操心。menuType：0xff = 主菜单，0xfd = 快捷方式。
     *
     * 菜单项总数上限 16（GB 的规则：超过时把「更多」钉在第 16 位，防止手环截断；
     * 我们的界面直接限制了总数，到不了这条规则）。
     */
    fun displayItemsCommand(items: List<Item>, shortcuts: Boolean): ByteArray {
        val menuType = (if (shortcuts) 0xfd else 0xff).toByte()
        val out = ByteArray(items.size * 4 + 5)
        var pos = 0
        out[pos++] = 0x1e
        out[pos++] = 0 // watchface 固定第 0 位
        out[pos++] = 0x00
        out[pos++] = menuType
        out[pos++] = ITEM_WATCHFACE_ID.toByte()
        var index = 1
        for (item in items) {
            out[pos++] = index.toByte()
            out[pos++] = 0x00
            out[pos++] = menuType
            out[pos++] = item.id.toByte()
            index++
        }
        return out
    }

    /** chunked 通道上设置命令的类型号（GB writeToChunked(builder, 2, …)）。 */
    const val CHUNKED_TYPE_DISPLAY_ITEMS = 2

    private const val ITEM_WATCHFACE_ID = 0x12

    // ------------------------------------------------------------------
    // 开关类设置 —— 直写配置特征（00000003），头字节 ENDPOINT_DISPLAY = 0x06
    // ------------------------------------------------------------------

    /**
     * 抬腕亮屏（GB COMMAND_ENABLE/DISPLAY_ON_LIFT_WRIST）。
     * 开启时多出来的 4 个零是 GB 原样保留的（预测模式占位），照抄。
     */
    fun displayOnLiftWristCommand(enabled: Boolean): ByteArray =
        if (enabled) {
            byteArrayOf(0x06, 0x05, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
        } else {
            byteArrayOf(0x06, 0x05, 0x00, 0x00)
        }

    /** 滑动解锁（GB setBandScreenUnlock）：开启后锁屏需上滑解锁。 */
    fun swipeUnlockCommand(enabled: Boolean): ByteArray =
        byteArrayOf(0x06, 0x16, 0x00, (if (enabled) 0x01 else 0x00).toByte())

    /**
     * 断开提醒（GB setDisconnectNotification）：手环与手机断开蓝牙时手环自己振动提醒。
     * 「定时」形态会往 cmd[4..7] 填开始/结束时刻，「常开」全填零。
     */
    fun disconnectAlertCommand(enabled: Boolean): ByteArray =
        if (enabled) {
            byteArrayOf(0x06, 0x0c, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
        } else {
            byteArrayOf(0x06, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        }

    // ------------------------------------------------------------------
    // 勿扰（ENDPOINT_DND = 0x09）与夜间模式（GB MiBand3Service 0x1a）
    // ------------------------------------------------------------------

    enum class DndMode { OFF, SCHEDULED, AUTOMATIC }

    /**
     * 勿扰（GB setDoNotDisturb）：关闭 09 82 / 自动 09 83 / 定时 09 81 + 起止时刻。
     * 「自动」= 手环检测到入睡自动进入。
     */
    fun dndCommand(mode: DndMode, startMinute: Int, endMinute: Int): ByteArray = when (mode) {
        DndMode.OFF -> byteArrayOf(0x09, 0x82.toByte())
        DndMode.AUTOMATIC -> byteArrayOf(0x09, 0x83.toByte())
        DndMode.SCHEDULED -> byteArrayOf(
            0x09, 0x81.toByte(), clockByte(startMinute, true), clockByte(startMinute, false),
            clockByte(endMinute, true), clockByte(endMinute, false),
        )
    }

    enum class NightMode { OFF, SCHEDULED, SUNSET }

    /** 夜间模式（GB setNightMode）：关闭 1a 00 / 定时 1a 01 + 起止 / 日落自动 1a 02。 */
    fun nightModeCommand(mode: NightMode, startMinute: Int, endMinute: Int): ByteArray = when (mode) {
        NightMode.OFF -> byteArrayOf(0x1a, 0x00)
        NightMode.SUNSET -> byteArrayOf(0x1a, 0x02)
        NightMode.SCHEDULED -> byteArrayOf(
            0x1a, 0x01, clockByte(startMinute, true), clockByte(startMinute, false),
            clockByte(endMinute, true), clockByte(endMinute, false),
        )
    }

    // ------------------------------------------------------------------
    // 佩戴手 —— 写用户设置特征（00000008），GB setWearLocation
    // ------------------------------------------------------------------

    fun wearLocationCommand(left: Boolean): ByteArray =
        if (left) byteArrayOf(0x20, 0x00, 0x00, 0x02) else byteArrayOf(0x20, 0x00, 0x00, 0x82.toByte())

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 「当天第几分钟」→ 小时/分钟字节（GB 往 cmd 里填 Calendar.HOUR_OF_DAY / MINUTE）。 */
    private fun clockByte(minuteOfDay: Int, hour: Boolean): Byte {
        val clamped = ((minuteOfDay % 1440) + 1440) % 1440
        return ((if (hour) clamped / 60 else clamped % 60) and 0xff).toByte()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }
}
