package com.ted.shouhuan.proto

/**
 * 标准 GATT 心率测量（0x2A37）的解析。
 *
 * 格式：`[flags] [心率值…]`
 *   - flags bit0 = 0 → 心率是 1 字节（uint8）
 *   - flags bit0 = 1 → 心率是 2 字节小端（uint16）
 * 后面还可能跟能量消耗、RR 间期等字段，这里只取心率。
 */
object HeartRateParser {
    fun parse(value: ByteArray): Int? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt() and 0xFF
        return if (flags and 0x01 != 0) {
            if (value.size < 3) {
                null
            } else {
                ((value[2].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
            }
        } else {
            if (value.size < 2) null else value[1].toInt() and 0xFF
        }
    }
}
