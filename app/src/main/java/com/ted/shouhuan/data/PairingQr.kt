package com.ted.shouhuan.data

/**
 * 配对二维码载荷的解析。
 *
 * 电脑端跑 `pairing_qr.py` 会把 MAC / AuthKey / 设备名编成一张二维码，格式：
 *
 *     SHOUHUAN1|AA:BB:CC:DD:EE:FF|0x0123456789abcdef0123456789abcdef|小米手环5
 *
 * 这里按同一条规则拆回去，再走 [Pairing] 的规范化 —— 二维码里是脚本已规范化的
 * 形态，这里再兜一道是为了防「别的 App 的二维码」误入：前缀不对直接拒收。
 */
object PairingQr {

    /** 载荷前缀，也是版本标记（和 python 端 pairing_qr.QR_PREFIX 保持一致）。 */
    const val PREFIX = "SHOUHUAN1"
    const val SEP = "|"

    /** 一条可用的配对载荷。name 可空 —— 空着用 [Pairing.DEFAULT_NAME]。 */
    data class Payload(val name: String?, val mac: String, val authKey: String)

    /** 解析二维码文本；不是本工具的载荷（前缀不对 / 字段缺 / 格式错）返回 null。 */
    fun parse(raw: String): Payload? {
        val text = raw.trim()
        val marker = "$PREFIX$SEP"
        if (!text.startsWith(marker)) return null
        val parts = text.substring(marker.length).split(SEP)
        val mac = Pairing.normalizeMac(parts.getOrNull(0).orEmpty())
        val key = Pairing.normalizeAuthKey(parts.getOrNull(1).orEmpty())
        if (mac == null || key == null) return null
        val name = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
        return Payload(name = name, mac = mac, authKey = key)
    }
}
