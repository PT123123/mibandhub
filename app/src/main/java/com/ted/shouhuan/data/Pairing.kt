package com.ted.shouhuan.data

/**
 * 配对信息的规范化与校验。
 *
 * 用户拿到的 MAC / AuthKey 形态五花八门：官方日志里是 `AA:BB:CC:DD:EE:FF`，
 * 取密钥工具的输出带 `0x` 前缀，从聊天软件复制过来还可能夹着空格或换行。
 * 与其让 [com.ted.shouhuan.proto.Auth.parseKey] 和 `getRemoteDevice()` 各自去猜，
 * 不如在入口处收敛成一种形态 —— 存进本地的就是规范形式，后面所有环节都不用再操心。
 *
 * 校验和规范化放在一起，是为了让界面能直接说清「哪里不对」，
 * 而不是笼统地报一句「格式错误」让用户自己猜。
 */
object Pairing {

    /** MAC 是 6 字节，也就是 12 位十六进制。 */
    const val MAC_HEX_LEN = 12

    /** AuthKey 是 16 字节 AES key，也就是 32 位十六进制。 */
    const val AUTH_KEY_HEX_LEN = 32

    /** 用户没填名字时的默认值。 */
    const val DEFAULT_NAME = "小米手环5"

    /** 具体到能照做的提示，直接摆给用户看。 */
    const val MAC_EXAMPLE = "AA:BB:CC:DD:EE:FF"

    /**
     * 只留十六进制字符 —— 冒号、横杠、空格、换行、`0x` 前缀里的 x 都会在这一步掉掉。
     *
     * 用 `digitToIntOrNull(16)` 而不是自己写 `in 'a'..'f'`：位数判断和取值用同一套规则，
     * 不会出现「过滤时认了、解析时又不认」这种对不上的情况。
     */
    private fun hexOnly(raw: String): String = raw.filter { it.digitToIntOrNull(16) != null }

    /**
     * 规范化 MAC：`aabbccddeeff` / `AA-BB-CC-DD-EE-FF` / `aa:bb:cc:dd:ee:ff` 都收，
     * 统一成大写冒号分隔。位数不对返回 null（不猜、不补位 —— 猜错了会连到别的设备上）。
     */
    fun normalizeMac(raw: String): String? {
        val hex = hexOnly(raw).uppercase()
        if (hex.length != MAC_HEX_LEN) return null
        return hex.chunked(2).joinToString(":")
    }

    /**
     * 规范化 AuthKey：容得下 `0x` 前缀与任意分隔符，统一成小写 32 位 hex
     * —— 和 [com.ted.shouhuan.proto.Auth.parseKey] 认可的形态一致。
     */
    fun normalizeAuthKey(raw: String): String? {
        // 先 trimg 再剥前缀：日志里粘过来的值常带尾随换行，不 trim 就会漏掉前缀。
        val body = raw.trim().removePrefix("0x").removePrefix("0X")
        val hex = hexOnly(body).lowercase()
        if (hex.length != AUTH_KEY_HEX_LEN) return null
        return hex
    }

    /** 名字留空就用默认型号名，不硬要求用户填。 */
    fun normalizeName(raw: String): String = raw.trim().ifBlank { DEFAULT_NAME }

    /** MAC 有什么问题；没问题返回 null。 */
    fun macProblem(raw: String): String? = when {
        raw.isBlank() -> "还没填 —— 手环的 MAC 地址"
        normalizeMac(raw) == null ->
            "位数不对：应该是 $MAC_HEX_LEN 位十六进制（$MAC_EXAMPLE）"

        else -> null
    }

    /** AuthKey 有什么问题；没问题返回 null。 */
    fun authKeyProblem(raw: String): String? = when {
        raw.isBlank() -> "还没填 —— 用仓库里的 `just fetch` 或 `xiaomi_authkey.py` 取"
        normalizeAuthKey(raw) == null ->
            "位数不对：应该是 $AUTH_KEY_HEX_LEN 位十六进制（带 `0x` 前缀也行）"

        else -> null
    }

    /**
     * 展示用的密钥掩码：`0f39…946e`。
     *
     * 留头尾各 4 位是有用的 —— 手上有两把密钥时，这足以分出哪把是哪把；
     * 而中间 24 位盖住，屏幕上就不是一条完整凭据了。
     */
    fun maskAuthKey(key: String?): String {
        val hex = key?.let { normalizeAuthKey(it) } ?: return "未配置"
        return "${hex.take(4)}…${hex.takeLast(4)}"
    }
}
