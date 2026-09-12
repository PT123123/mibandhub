package com.ted.shouhuan.ble

import java.util.UUID

/**
 * 小米/华米手环用到的 GATT 服务与特征。
 *
 * 取值来源：Gadgetbridge 的 HuamiService.java / MiBandService.java，
 * 这两套常量在真机上与 Mi Band 5（hmpace.bracelet.v5）一致。
 */
object Gatt {
    // 注意两套模板的前缀写法不一样，别改混：
    //   BASE  —— 调用方给 4 位短码（"fee0"），前缀 0000 由模板补
    //   HUAMI —— 调用方给 8 位完整前缀（"00000009"），与 Gadgetbridge 常量逐字对应
    // 早先 HUAMI 模板也写了 0000，于是拼成 000000000009-...（首段 12 位），
    // UUID.fromString 抛 "UUID string too large"，整个 object 的静态初始化直接失败。
    private const val BASE = "0000%s-0000-1000-8000-00805f9b34fb"
    private const val HUAMI = "%s-0000-3512-2118-0009af100700"

    private fun b(short: String): UUID = UUID.fromString(String.format(BASE, short))
    private fun h(short: String): UUID = UUID.fromString(String.format(HUAMI, short))

    // ---------------- 服务 ----------------
    val SERVICE_MIBAND = b("fee0")
    val SERVICE_MIBAND2 = b("fee1")
    val SERVICE_HEART_RATE = b("180d")
    val SERVICE_DEVICE_INFO = b("180a")

    // ---------------- MiBand 服务（FEE0）下的特征 ----------------
    val CHAR_DEVICE_NAME = b("ff02")

    /** 手环所有事件（认证结果、电量、事件类型）都从这里推上来。 */
    val CHAR_NOTIFICATION = b("ff03")
    val CHAR_USER_INFO = b("ff04")
    val CHAR_CONTROL_POINT = b("ff05")
    val CHAR_REALTIME_STEPS = b("ff06")
    val CHAR_ACTIVITY_DATA = b("ff07")
    val CHAR_BATTERY = b("ff0c")

    // ---------------- 华米私有特征 ----------------
    val CHAR_AUTH = h("00000009")
    val CHAR_CONFIGURATION = h("00000003")
    val CHAR_BATTERY_INFO = h("00000006")

    /**
     * 实时步数（华米 7 号特征）。MB3/4/5 走它；[CHAR_REALTIME_STEPS]（ff06）
     * 是初代 MiBand 的对应物，留着做兼容。启用方式见 BandSession.enableRealtimeSteps。
     */
    val CHAR_REALTIME_STEPS_HUAMI = h("00000007")
    val CHAR_USER_SETTINGS = h("00000008")
    val CHAR_DEVICE_EVENT = h("00000010")

    /**
     * 分块传输通道：拉活动数据（含睡眠）、往手环发文字通知都走这里
     * （MB3/4/5 的通知 = chunked type 0，编码见 proto/Notify）。
     */
    val CHAR_CHUNKED = h("00000020")

    // ---------------- 标准 GATT 心率服务（0x180D）----------------
    /** notify：心率值从这里来。 */
    val CHAR_HR_MEASUREMENT = b("2a37")

    /** write：启停测量指令写这里。 */
    val CHAR_HR_CONTROL_POINT = b("2a39")

    /** 客户端特征配置描述符（开启 notify 用）。 */
    val DESC_CCCD = b("2902")
}
