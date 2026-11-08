package com.mibandhub.app.relay.model

/**
 * 数据中转模块的全部数据模型。
 *
 * 刻意与业务数据（心率/睡眠/通知）解耦：传输层只认这里的 [RelayBatch]，
 * 不关心里面装的是什么、从哪来。
 */

/** 传输模式：局域网真实中转（默认，对接电脑上的服务端）/ 模拟中转（进程内假节点，仅供调试）。 */
enum class RelayMode { MOCK, LAN }

/** 局域网里被发现的中转节点（LocalSend 风格：每台设备带指纹用于校验身份）。 */
data class RelayDevice(
    val id: String,
    val name: String,
    /** IP 或域名；mock 用 "localhost"。 */
    val host: String,
    val port: Int,
    /** 设备指纹（SHA256 前若干位），用于校验中转身份。 */
    val fingerprint: String,
    val isMock: Boolean,
)

/** 一次同步会话：openSession 之后拿到，后续 push 复用。 */
data class RelaySession(
    val device: RelayDevice,
    val token: String,
    val capabilities: List<String> = emptyList(),
)

/** 传输层的一批数据 —— 与数据来源完全解耦，传输层不解析内部内容。 */
data class RelayBatch(
    /** 客户端生成的本次批次 id。 */
    val sessionId: String,
    /** 本机别名，告诉中转「这是谁的数据」。 */
    val sourceAlias: String,
    val createdAt: Long,
    val items: List<RelayPayloadItem>,
    /** 设备名（桌面端 ShouhuanData.device_name）。 */
    val deviceName: String? = null,
    /** 设备 MAC（桌面端 ShouhuanData.device_mac）。 */
    val deviceMac: String? = null,
)

/** 一批里的某一类数据。 */
data class RelayPayloadItem(
    /** "heart_rate" / "sleep" / "measure" / "notification"。 */
    val type: String,
    /** 该类型记录条数。 */
    val count: Int,
    /** 序列化后的字节数（估值）。 */
    val bytes: Long,
    /** JSON 字符串，传输层原样发送、不解析。 */
    val payload: String,
)

/** 一次推送的结果（与传输实现无关）。 */
sealed interface RelayPushResult {
    data class Success(
        val acceptedItems: Int,
        val acceptedBytes: Long,
        /** 对端回执，便于端到端核对。 */
        val receipt: String,
    ) : RelayPushResult

    data class Failure(val error: String) : RelayPushResult
}

/** 同步引擎的状态。 */
enum class RelaySyncStatus { IDLE, DISCOVERING, SYNCING, ERROR }

/** 同步引擎对外暴露的状态快照。 */
data class RelaySyncState(
    val status: RelaySyncStatus = RelaySyncStatus.IDLE,
    val device: RelayDevice? = null,
    /** 0..1，仅 SYNCING 时有意义。 */
    val progress: Float = 0f,
    val lastSyncAt: Long? = null,
    val lastResult: RelayPushResult? = null,
    /** "心率 128 条 · 睡眠 7 晚" 这类人类可读摘要。 */
    val lastBatchSummary: String? = null,
)

/** 一条同步日志。 */
data class RelayLogEntry(
    val atMillis: Long,
    val level: RelayLogLevel,
    val message: String,
)

enum class RelayLogLevel { INFO, WARN, ERROR }

/** 中转配置（持久化到独立 DataStore "relay"，与手环配置隔离）。 */
data class RelayConfig(
    /** 默认走 LAN：组播自动发现桌面端，不需要手填 IP；MOCK 仅供手动调试。 */
    val mode: RelayMode = RelayMode.LAN,
    /** 总开关：关掉后既不自动同步也不手动同步。 */
    val enabled: Boolean = true,
    /** 后台自动同步。 */
    val autoSync: Boolean = false,
    /** 自动同步间隔（分钟）。 */
    val intervalMinutes: Int = 15,
    /** LAN 模式下的中转地址（桌面端 health-motion 默认端口 18765）。 */
    val serverUrl: String = "http://192.168.x.x:18765",
    /** 本机别名。 */
    val sourceAlias: String = "手环管家",
    /** 用户锁定的目标节点 id（null = 自动选第一个发现的）。 */
    val selectedDeviceId: String? = null,
)
