package com.ted.shouhuan.proto

import java.util.UUID
import java.util.zip.CRC32

/**
 * 表盘包下发的协议字节 —— 走的是华米的固件/资源传输通道。
 *
 * 通道有两条私有特征：
 *   `1531` 控制 —— 所有命令和回复都走这条；
 *   `1532` 数据 —— 只用来灌文件内容。
 *
 * 完整流程（照 Gadgetbridge 的 `UpdateFirmwareOperationNew` 写；
 * `MiBand5Support extends MiBand4Support`，Mi Band 5 用的就是它）：
 * ```
 * 手机 → 3号配置: 39 00 00 ff ff ff <文件[18..21]>      ⓪ 选表盘槽（仅 UIHH 容器）
 * 手机 → 1531: 01 <type> <size u32le> <crc32 u32le>     ① 报元数据
 * 手环 →      : 10 01 01                                 ② 收下了
 * 手机 → 1531: 03 01                                     ③ 开始推
 * 手机 → 1532: <数据包 × N>（每 100 包插一条 00 到 1531） ④ 推数据
 * 手机 → 1531: 00                                        ⑤ 推完了
 * 手环 →      : 10 03 01                                 ⑥ 数据齐了
 * 手机 → 1531: 04 <crc16 u16le>                          ⑦ 请校验（CRC16 也要带）
 * 手环 →      : 10 04 01                                 ⑧ 成功
 * ```
 *
 * ✅ **2026-09-12 真机验证通过**：补上 ⓪ 和 ⑦ 的 CRC16 之后，内置表盘
 * 下发到手环，手环回 `10 04 01`，表盘成功换上。此前长期卡在
 * `10 20 08` / `10 20 00`（数据完整送达但收尾被拒），根因就是缺 ⓪、
 * ⑦ 没带 CRC16 —— 界面上的「实验性」标已经摘掉。
 *
 * 上游参考：Gadgetbridge `devices/huami/HuamiService.java`（常量）与
 * `service/devices/huami/operations/update/UpdateFirmwareOperation(New).java`（流程）。
 */
object WatchFace {

    // ---------------- 特征 ----------------

    /** 控制特征：命令与回复。 */
    val CHAR_CONTROL: UUID = UUID.fromString("00001531-0000-3512-2118-0009af100700")

    /** 数据特征：只灌文件内容。 */
    val CHAR_DATA: UUID = UUID.fromString("00001532-0000-3512-2118-0009af100700")

    /**
     * 配置特征：表盘上传前要先写一条「选表盘槽」命令到这里（GB 的
     * `UUID_CHARACTERISTIC_3_CONFIGURATION`）。和 1531 不是同一条。
     */
    val CHAR_CONFIG: UUID = UUID.fromString("00000003-0000-3512-2118-0009af100700")

    // ---------------- 常量 ----------------

    /** `HuamiFirmwareType.WATCHFACE`。 */
    const val TYPE_WATCHFACE = 0x08

    /**
     * 单次 GATT 写多少字节。
     *
     * 我们的连接层没有协商 MTU，默认 MTU 23 → 可用载荷 20 字节。
     * 改这里要连带确认 MTU，否则会被协议栈截断。
     */
    const val PACKET_SIZE = 20

    /** 每发这么多包插一条同步命令（上游就是 100）。 */
    const val SYNC_EVERY_PACKETS = 100

    /**
     * 允许下发的最大包体。
     *
     * Mi Band 5 按 615 KB 卡（社区打包工具的口径 —— 是手环硬限还是工具的
     * 保守保护值，还没真机确认，见 docs/watchface.md §4）；官方那份自定义
     * 表盘是 242 KB，内置表盘都在 320 KB 以内。这里同时兜住「选错文件」
     * （比如挑了个几百 MB 的 zip）的情况。
     */
    const val MAX_PAYLOAD_BYTES = 615 * 1024

    private const val RESPONSE = 0x10
    private const val SUCCESS = 0x01

    const val CMD_INIT = 0x01
    const val CMD_DATA_COMPLETE = 0x03
    const val CMD_CHECKSUM = 0x04
    const val CMD_SYNC = 0x00

    // ---------------- 字节构造 ----------------

    /** ① 元数据：`01 <type> <size u32le> <crc32 u32le>`。 */
    fun initPacket(payload: ByteArray): ByteArray =
        byteArrayOf(CMD_INIT.toByte(), TYPE_WATCHFACE.toByte()) +
            u32(payload.size) + u32(crc32Of(payload))

    /** ③ 开始推：`03 01`。 */
    fun startPacket(): ByteArray = byteArrayOf(CMD_DATA_COMPLETE.toByte(), 0x01)

    /** 是不是华米 .bin 表盘容器（"UIHH" 文件头，且装得下槽选择命令要的第 18~21 字节）。 */
    fun isUihh(payload: ByteArray): Boolean =
        payload.size >= 22 &&
            payload[0] == 0x55.toByte() && payload[1] == 0x49.toByte() &&
            payload[2] == 0x48.toByte() && payload[3] == 0x48.toByte()

    /**
     * ⓪ 选表盘槽：`39 00 00 ff ff ff <表盘文件第 18..21 字节>`，写到配置特征。
     * 照 Gadgetbridge `UpdateFirmwareOperationNew` 原样抄 —— 那四个字节是 UIHH
     * 头里的表盘 ID，手环靠它知道收到的包归哪个表盘槽。缺了这一步，收尾校验
     * 会被拒（实测 `10 20 08` / `10 20 00`）。
     */
    fun slotSelectPacket(payload: ByteArray): ByteArray =
        byteArrayOf(0x39, 0x00, 0x00, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()) +
            payload.copyOfRange(18, 22)

    /** ⑤ 推完了 / ④ 期间的同步命令：单字节 `00`。 */
    fun syncPacket(): ByteArray = byteArrayOf(CMD_SYNC.toByte())

    /**
     * ⑦ 请校验：`04 <crc16 u16le>`。
     *
     * CRC16 必须带上 —— 早期实测发裸 `04`，手环回 `10 20 08` / `10 20 00` 拒绝。
     * [crc16Of] 与 Gadgetbridge `CheckSums.getCRC16` 同一套（CCITT，init 0xFFFF；
     * 对照向量 "123456789" → 0x29B1 已验证）。
     */
    fun checksumPacket(payload: ByteArray): ByteArray {
        val crc16 = crc16Of(payload)
        return byteArrayOf(
            CMD_CHECKSUM.toByte(),
            (crc16 and 0xff).toByte(),
            ((crc16 shr 8) and 0xff).toByte(),
        )
    }

    // ---------------- 计算 ----------------

    /** 标准 zlib CRC32 —— 和上游 `CheckSums.getCRC32` 同一套。 */
    fun crc32Of(payload: ByteArray): Int =
        CRC32().apply { update(payload) }.value.toInt()

    /**
     * CRC16/CCITT（init 0xFFFF）—— 与 Gadgetbridge `CheckSums.getCRC16` 逐行同款，
     * 收尾校验命令 `04 <crc16>` 用的就是它。
     */
    fun crc16Of(payload: ByteArray): Int {
        var crc = 0xFFFF
        for (b in payload) {
            crc = ((crc ushr 8) or (crc shl 8)) and 0xffff
            crc = crc xor (b.toInt() and 0xff)
            crc = crc xor ((crc and 0xff) shr 4)
            crc = crc xor ((crc shl 12) and 0xffff)
            crc = crc xor (((crc and 0xff) shl 5) and 0xffff)
            crc = crc and 0xffff
        }
        return crc
    }

    fun packetCount(payloadSize: Int): Int =
        if (payloadSize <= 0) 0 else (payloadSize + PACKET_SIZE - 1) / PACKET_SIZE

    /** 第 [index] 个数据包（最后一包可能不满）。 */
    fun packetAt(payload: ByteArray, index: Int): ByteArray {
        val from = index * PACKET_SIZE
        val to = minOf(from + PACKET_SIZE, payload.size)
        return payload.copyOfRange(from, to)
    }

    // ---------------- 解析 ----------------

    /** 手环从控制特征回来的消息。 */
    sealed interface Response {
        /** 原始字节 —— 出问题时这是唯一能查下去的东西。 */
        val raw: ByteArray

        /** `10 <cmd> <status>` —— 对某条命令的应答。 */
        data class Ack(
            val command: Int,
            val status: Int,
            override val raw: ByteArray,
        ) : Response {
            /** 是不是 `10 <cmd> 01`。 */
            val ok: Boolean get() = status == SUCCESS
        }

        /** 认不出来的形态（长度不够、或者不是 0x10 开头）。 */
        data class Other(override val raw: ByteArray) : Response
    }

    fun parse(value: ByteArray): Response =
        if (value.size >= 3 && (value[0].toInt() and 0xff) == RESPONSE) {
            Response.Ack(
                command = value[1].toInt() and 0xff,
                status = value[2].toInt() and 0xff,
                raw = value,
            )
        } else {
            Response.Other(value)
        }

    // ---------------- 展示用 ----------------

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    /** `0x0943ba4f` 这种固定 8 位的写法。 */
    fun hex32(value: Int): String =
        "0x" + Integer.toHexString(value).padStart(8, '0')

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 24) and 0xff).toByte(),
    )
}

/** 下发进度。 */
data class WatchFaceProgress(
    val sentPackets: Int,
    val totalPackets: Int,
    val sentBytes: Int,
    val totalBytes: Int,
    val elapsedMillis: Long,
) {
    val percent: Int
        get() = if (totalPackets <= 0) 0 else (sentPackets * 100 / totalPackets)

    val elapsedSec: Int get() = (elapsedMillis / 1000).toInt()
}

/** 一次下发的最终结果。 */
sealed interface WatchFaceOutcome {
    /** 手环明确回 `10 04 01`。 */
    data object Success : WatchFaceOutcome

    /**
     * 数据全部送达（手环回了「数据齐了」），但最后一步的回复不符合预期。
     *
     * 真机实测就停在这个状态 —— 所以界面上要说清「包收全了，但生效没确认」，
     * 而不是含糊地报成功或失败。
     */
    data class Unconfirmed(val lastResponse: String) : WatchFaceOutcome

    data class Failed(
        val title: String,
        val detail: String,
        val hint: String? = null,
    ) : WatchFaceOutcome
}
