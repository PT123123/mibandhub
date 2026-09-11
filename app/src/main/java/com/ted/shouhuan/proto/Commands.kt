package com.ted.shouhuan.proto

/**
 * 手环指令字节。
 *
 * 心跳测量走**标准 GATT 心率控制点**（0x2A39），首字节固定 0x15。
 * 取值来源：Gadgetbridge MiBandService（COMMAND_SET_HR_MANUAL=0x02、
 * COMMAND_SET__HR_CONTINUOUS=0x01）。
 */
object Commands {

    // ---------------- 心率控制点（写 0x2A39）----------------

    /** 停止连续测量 `{0x15, 0x01, 0x00}`。 */
    val HR_STOP_CONTINUOUS = byteArrayOf(0x15, 0x01, 0x00)

    /** 开始连续测量 `{0x15, 0x01, 0x01}` —— 实时心率用它。 */
    val HR_START_CONTINUOUS = byteArrayOf(0x15, 0x01, 0x01)

    /** 停止单次测量 `{0x15, 0x02, 0x00}`。 */
    val HR_STOP_MANUAL = byteArrayOf(0x15, 0x02, 0x00)

    /** 开始单次测量 `{0x15, 0x02, 0x01}`。 */
    val HR_START_MANUAL = byteArrayOf(0x15, 0x02, 0x01)

    /**
     * 开始一次测量的标准写入序列。
     *
     * 顺序不能改：先停连续、再停单次、最后启单次 —— 手环在已有测量在跑时
     * 会忽略新的启动请求（Gadgetbridge 也是这么写的）。
     */
    val HR_MEASURE_ONCE_SEQUENCE = listOf(
        HR_STOP_CONTINUOUS,
        HR_STOP_MANUAL,
        HR_START_MANUAL,
    )

    /** 开始实时连续测量：先停掉可能存在的单次测量，再开连续。 */
    val HR_START_REALTIME_SEQUENCE = listOf(
        HR_STOP_MANUAL,
        HR_START_CONTINUOUS,
    )

    // ---------------- MiBand 控制点（写 0xFF05）----------------

    /** 请求同步活动数据（含睡眠）。 */
    val CONTROL_FETCH_DATA = byteArrayOf(0x02)

    /** 停止同步。 */
    val CONTROL_STOP_SYNC = byteArrayOf(0x11)
}
