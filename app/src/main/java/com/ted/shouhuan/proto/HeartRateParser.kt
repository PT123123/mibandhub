package com.ted.shouhuan.proto

/**
 * 标准 GATT 心率测量（0x2A37）的解析。
 *
 * 格式：`[flags] [心率值…]`
 *   - flags bit0 = 0 → 心率是 1 字节（uint8）
 *   - flags bit0 = 1 → 心率是 2 字节小端（uint16）
 * 后面还可能跟能量消耗、RR 间期等字段，这里只取心率。
 *
 * 读数为 0 的一律当「暂时没测到」处理，返回 null。
 * 手环刚开测、还没采到脉搏时会先推若干帧 0 —— 那不是「心率 0」。
 * 若把它当有效值，界面会闪一个刺眼的 `0 BPM`，等待逻辑也会被这帧假数据
 * 提前满足、拿着 0 就当测量成功收工。
 */
object HeartRateParser {
    fun parse(value: ByteArray): Int? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt() and 0xFF
        val bpm = if (flags and 0x01 != 0) {
            if (value.size < 3) return null
            ((value[2].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
        } else {
            if (value.size < 2) return null
            value[1].toInt() and 0xFF
        }
        // 0 是「还没采到」，不是有效读数
        return bpm.takeIf { it > 0 }
    }
}

