package com.ted.shouhuan.proto

/**
 * 文字通知的手环协议编码（MB3/4/5 通用，通知走 chunked 通道 0x0020）。
 *
 * 字节级依据 Gadgetbridge 的 HuamiSupport.onNotification + writeToChunkedOld，
 * 与我们真机已验证的特征/认证体系同源。关键点：
 *
 *  - 通道：chunked（00000020-…-0009af100700），通知用 type=0；
 *  - MB4 起通知多一个 4 字节零头（MiBand4Support.notificationHasExtraHeader=true，
 *    MB5 继承 MB4）—— 少了它手环显示的内容会错位；
 *  - 分类字节：CustomHuami = 0xFA（AlertCategory.CustomHuami.getId() = -6）；
 *  - 消息体 = 标题 \0 正文（GB 对标题截 32 字节、正文截 512 字节）；
 *  - 尾部 = \0 应用名 \0，手环把它显示在图标旁边；
 *  - 分块：每包 3 字节头 + 最多 MTU-6 字节（MTU 23 → 17 字节），flags：
 *    单包 0xC0（最后一包 && 第 0 包）、多包 0x00 → 0x40 → 0x80，chunk[2] = 包序号。
 */
object Notify {

    /** chunked 通道上通知的类型号。 */
    private const val CHUNKED_TYPE_NOTIFICATION = 0

    /** MB4+ 的通知在分类字节后多 4 个 0x00。 */
    private const val EXTRA_4BYTE_HEADER = true

    const val ALERT_CATEGORY_CUSTOM_HUAMI = 0xFA

    /** 通用应用图标 —— Gadgetbridge 对 UNKNOWN 类型就用它。 */
    const val ICON_GENERIC_APP = 0x0B

    /** 消息体（标题+正文）的字节上限 = GB notificationMaxLength(230) - CustomHuami 前缀(7)。 */
    private const val MAX_MESSAGE_BYTES = 223

    /** 单包数据上限：MTU 23 - 6（3 字节头 + 3 字节 ATT/GATT 开销）。 */
    const val MAX_CHUNK_LENGTH = 17

    /**
     * 拼一条完整的通知 payload（未分块）。
     *
     * @param appName 手环上显示在图标旁的应用名
     */
    fun buildPacket(
        appName: String,
        title: String,
        body: String,
        icon: Int = ICON_GENERIC_APP,
    ): ByteArray {
        val message = truncateUtf8(title, 32) + "\u0000" + truncateUtf8(body, 512)
        val messageBytes = truncateUtf8(message, MAX_MESSAGE_BYTES).toByteArray(Charsets.UTF_8)
        val appSuffix = ("\u0000$appName\u0000").toByteArray(Charsets.UTF_8)

        val prefixLength = 2 + // 分类 + numAlerts
            (if (EXTRA_4BYTE_HEADER) 4 else 0) +
            1 // CustomHuami 的图标字节
        val packet = ByteArray(prefixLength + messageBytes.size + appSuffix.size)
        var pos = 0
        packet[pos++] = ALERT_CATEGORY_CUSTOM_HUAMI.toByte()
        if (EXTRA_4BYTE_HEADER) {
            repeat(4) { packet[pos++] = 0 }
        }
        packet[pos++] = 0x01 // numAlerts
        packet[pos++] = icon.toByte()
        System.arraycopy(messageBytes, 0, packet, pos, messageBytes.size)
        System.arraycopy(appSuffix, 0, packet, pos + messageBytes.size, appSuffix.size)
        return packet
    }

    /**
     * 按 writeToChunkedOld 的格式分块。data 为空时返回空列表（一条也不写）。
     */
    fun chunk(data: ByteArray, chunkLength: Int = MAX_CHUNK_LENGTH): List<ByteArray> {
        if (data.isEmpty()) return emptyList()
        val chunks = ArrayList<ByteArray>()
        var offset = 0
        var index = 0
        while (offset < data.size) {
            val remaining = data.size - offset
            val copy = minOf(remaining, chunkLength)
            var flags = 0
            if (remaining <= chunkLength) {
                flags = flags or 0x80 // 最后一包
                if (index == 0) flags = flags or 0x40 // 单包也是「第 0 包 + 最后一包」
            } else if (index > 0) {
                flags = flags or 0x40 // 连续包
            }
            val chunk = ByteArray(copy + 3)
            chunk[0] = 0
            chunk[1] = (flags or CHUNKED_TYPE_NOTIFICATION).toByte()
            chunk[2] = index.toByte()
            System.arraycopy(data, offset, chunk, 3, copy)
            chunks.add(chunk)
            offset += copy
            index++
        }
        return chunks
    }

    /** UTF-8 意义上的截断：不会把一个多字节字符劈成两半。 */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            val byteLength = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (used + byteLength > maxBytes) break
            sb.appendCodePoint(codePoint)
            used += byteLength
            i += Character.charCount(codePoint)
        }
        return sb.toString()
    }
}
