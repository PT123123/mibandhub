package com.mibandhub.app.relay

import com.mibandhub.app.relay.model.RelayBatch
import com.mibandhub.app.relay.model.RelayDevice
import com.mibandhub.app.relay.model.RelayPushResult
import com.mibandhub.app.relay.model.RelaySession

/**
 * 数据中转的传输层抽象 —— 这是整个模块和「局域网真实中转」之间**唯一的接缝**。
 *
 * 两种实现：[MockRelayTransport]（进程内假节点，无需网络即可演示整条链路）与
 * [LanRelayTransport]（对接电脑端 health-motion 的 LAN hub）。
 * 上层仓库 / ViewModel / UI 只认这套接口，换实现一行都不用改。
 *
 * 发现机制沿用 LocalSend 的 UDP 组播，但**传输不是 LocalSend 协议**：
 * 桌面端 `/api/health-motion/v1/push` 是无状态单次 POST，没有 prepare/token 流程，
 * 所以 LAN 实现的 `openSession` 只是原样返回设备，`token` 为空串。
 */
interface RelayTransport {

    /** 传输层标识：`"mock"` / `"health-motion-lan"`。 */
    val scheme: String

    /**
     * 发现局域网里可用中转节点。
     * - mock：直接返回一个固定的虚拟节点；
     * - lan：UDP 组播扫桌面端宣告，扫不到则退化成设置里手填的地址。
     */
    suspend fun discover(timeoutMs: Long = 3000): List<RelayDevice>

    /** 与节点建立会话。LAN 实现无状态，仅原样返回设备（token 为空）。 */
    suspend fun openSession(device: RelayDevice): RelaySession

    /**
     * 推送一批数据，[onProgress] 回报 0..1 的进度。
     * 返回成功（对方接受多少）或失败原因。
     */
    suspend fun push(
        session: RelaySession,
        batch: RelayBatch,
        onProgress: (Float) -> Unit = {},
    ): RelayPushResult

    /** 关闭会话（释放对端资源）。失败可忽略。 */
    suspend fun closeSession(session: RelaySession) {}
}
