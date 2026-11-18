package com.mibandhub.app.link

/**
 * 主从转发的共享模型。
 *
 * 主从链路一句话：从机（不连手环蓝牙）把本地过滤完的通知经局域网发给主机，
 * 主机收到后推给手环 —— 手环永远只握在主机手里。
 */

/** 本机在手环通知链路里扮演的角色，落盘用 [key]。 */
enum class LinkRole(val key: String, val label: String) {
    /** 独立使用：一台手机自己连手环，行为与旧版完全一致。 */
    NONE("none", "独立使用"),

    /** 主机：连着手环，接收从机转发来的通知并推手环。 */
    MASTER("master", "作为主机"),

    /** 从机：不连手环蓝牙，把自己的通知经 Wi-Fi 转给主机。 */
    SLAVE("slave", "作为从机"),
    ;

    companion object {
        fun fromKey(key: String?): LinkRole = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/** 从机通过组播宣告发现的一台主机。 */
data class DiscoveredMaster(
    /** 主机指纹（ANDROID_ID 派生，跨重启稳定）—— 主机换端口/IP 后靠它认回同一台。 */
    val fingerprint: String,
    val alias: String,
    val host: String,
    val port: Int,
    val lastSeenAt: Long,
) {
    val endpoint: String get() = "$host:$port"
}

/** 一次主从转发的结果。ok = false 时 [note] 说明原因（给「最近推送」与 UI 用）。 */
data class LinkSendResult(val ok: Boolean, val note: String)
