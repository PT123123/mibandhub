package com.ted.shouhuan.proto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private fun isHexDigit(c: Char): Boolean =
    c.isDigit() || c in 'a'..'f' || c in 'A'..'F'

private fun hexVal(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    else -> c - 'A' + 10
}

/**
 * 配对认证：和手环握手的两步。
 *
 * 报文形状与 Gadgetbridge 的 `InitOperation` 逐字一致，**只有 authFlags 不一样**：
 *
 * ```
 * ① 手机 → 手环   `82 00 02 01 00`                        请求随机数
 * ① 手环 → 手机   `10 82 01 <16 字节随机数>`               （命令 = 0x02 | cryptFlags）
 * ② 手机 → 手环   `83 00 <AES-ECB/NoPadding(随机数)>`      回加密结果
 * ② 手环 → 手机   `10 83 01`                              认证通过
 * ```
 *
 * 首字节 `0x82` / `0x83` 里的 `0x80` 是 cryptFlags（Mi Band 4/5/6 = `0x80`），
 * 表示「这条命令走加密通道」。**密钥从不上行**，只用来加密手环给的随机数。
 *
 * ## authFlags 为什么是 0x00（这条踩了很久）
 *
 * 报文第 2 字节 `authFlags`，Gadgetbridge 全系列都用 `AUTH_BYTE = 0x08`，
 * 但**这台 Mi Band 5（`hmpace.bracelet.v5`，固件 V1.0.2.76）只认 `0x00`**。
 * 用 `0x08` 时前两步全都正常，只有最后一步被回 `10 83 07` 拒掉 —— 现场看非常像「密钥错」。
 *
 * 实机矩阵（`am start …AuthLabActivity`，5 个组合跑在同一台手环上）：
 *
 * | authFlags | 第②步 → 第③步 | 结果 |
 * |---|---|---|
 * | `0x08` | `82 08 02 01 00` → `83 08 <enc>` | ✗ `10 83 07` |
 * | `0x00` | `82 00 02 01 00` → `83 00 <enc>` | ✓ `10 83 01` |
 *
 * 换 cryptFlags、换请求长度、加不加「先交密钥」那一步，结果都不变 —— **只有 authFlags 起作用**。
 * 另有两份独立来源印证 `0x00`：mebeats（Go 版小米手环客户端）用 `01 00 <key>` / `02 00` / `03 00`；
 * Gadgetbridge issue #6007「Can't pair Mi Smart Band 5」的解法就是把 `0x08` 改成 `0x00`
 * （维护者 joserebelo 原话：Mi Band 5 的 authFlags 应该是 0x00）。
 *
 * ⚠️ **标准 Gadgetbridge 的字节序列在这台手环上同样会失败**，所以不能直接照抄它的常量。
 */
object Auth {

    /** 认证标志（报文第 2 字节）。这台固件只认 `0x00`，详见类注释。 */
    const val FLAG_AUTH: Byte = 0x00

    /**
     * cryptFlags。Mi Band 4/5/6 = `0x80`（把 0x02 / 0x03 变成 0x82 / 0x83，要求走加密通道）。
     * Kotlin 的 Byte 放不下 0x80 这个正数字面量，只能写成 -0x80（值就是 0x80）。
     */
    private const val CRYPT_FLAG: Byte = -0x80

    const val CMD_SEND_KEY: Byte = 0x01
    const val CMD_REQUEST_RANDOM: Byte = 0x02
    const val CMD_SEND_ENCRYPTED: Byte = 0x03

    const val RESPONSE: Byte = 0x10
    const val STATUS_SUCCESS: Byte = 0x01
    const val STATUS_FAIL: Byte = 0x04

    /** 没配置密钥时 Gadgetbridge 使用的默认填充：ASCII "0123456789@ABCDE"。 */
    private val DEFAULT_KEY = byteArrayOf(
        0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
        0x38, 0x39, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45,
    )

    /**
     * 把用户填的密钥解析成 16 字节 AES key。
     *
     * 兼容三种输入：Gadgetbridge 风格 `0x` + 32 位 hex、裸 32 位 hex、
     * 以及不足 16 字节的原始字符串（不足部分保留默认填充）。
     */
    fun parseKey(raw: String?): ByteArray {
        val out = DEFAULT_KEY.copyOf()
        if (raw.isNullOrBlank()) return out

        val trimmed = raw.trim()
        val hex = if (trimmed.startsWith("0x", ignoreCase = true)) {
            trimmed.substring(2)
        } else {
            trimmed
        }

        val bytes = if (hex.length == 32 && hex.all { isHexDigit(it) }) {
            ByteArray(16) { i -> ((hexVal(hex[i * 2]) shl 4) or hexVal(hex[i * 2 + 1])).toByte() }
        } else {
            trimmed.toByteArray(Charsets.UTF_8)
        }

        System.arraycopy(bytes, 0, out, 0, minOf(bytes.size, 16))
        return out
    }

    /** ① 请求随机数：`82 00 02 01 00`（cryptFlags=0x80 + authFlags=0x00）。 */
    fun requestAuthNumberPacket(): ByteArray = byteArrayOf(
        crypted(CMD_REQUEST_RANDOM), // 0x82
        FLAG_AUTH,
        0x02, 0x01, 0x00,
    )

    /** ② 回加密后的随机数：`83 00 <16 字节密文>`。 */
    fun encryptedPacket(challenge: ByteArray, key: ByteArray): ByteArray =
        byteArrayOf(crypted(CMD_SEND_ENCRYPTED), FLAG_AUTH) + aes(challenge, key)

    /** 给命令字节打上 cryptFlags 的最高位：0x02 → 0x82、0x03 → 0x83。 */
    private fun crypted(cmd: Byte): Byte = (CRYPT_FLAG.toInt() or cmd.toInt()).toByte()

    /** AES-128-ECB、无填充 —— 手环要求整块 16 字节进整块出。 */
    fun aes(data: ByteArray, key: ByteArray): ByteArray {
        require(data.size == 16) { "挑战值必须是 16 字节，实际 ${data.size}" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    /** 判断一条认证特征的上报属于哪一步，便于会话状态机调度。 */
    fun classify(value: ByteArray): AuthEvent {
        if (value.size < 3 || value[0] != RESPONSE) return AuthEvent.Unknown
        // 手环回来的命令字节带 cryptFlags 高位（0x82 / 0x83），只比低 4 位
        val step = (value[1].toInt() and 0x0f).toByte()
        val ok = value[2] == STATUS_SUCCESS
        return when (step) {
            // 我们现在不发这一步了（authFlags=0x00 时手环不需要先收密钥）。
            // 留着是因为一旦真收到 `10 01 xx`，那正好说明 authFlags 又被改错了。
            CMD_SEND_KEY -> if (ok) AuthEvent.KeyAccepted else AuthEvent.KeyRejected
            CMD_REQUEST_RANDOM ->
                if (ok && value.size >= 19) AuthEvent.Challenge(value.copyOfRange(3, 19))
                else AuthEvent.Failed
            CMD_SEND_ENCRYPTED -> if (ok) AuthEvent.Authenticated else AuthEvent.KeyRejected
            else -> AuthEvent.Unknown
        }
    }
}

sealed interface AuthEvent {
    data object KeyAccepted : AuthEvent
    data object KeyRejected : AuthEvent
    data class Challenge(val value: ByteArray) : AuthEvent {
        override fun equals(other: Any?): Boolean =
            other is Challenge && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()
    }

    data object Authenticated : AuthEvent
    data object Failed : AuthEvent
    data object Unknown : AuthEvent
}
