package com.mibandhub.app.relay

import com.mibandhub.app.relay.model.RelayBatch
import com.mibandhub.app.relay.model.RelayDevice
import com.mibandhub.app.relay.model.RelayPushResult
import com.mibandhub.app.relay.model.RelaySession
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 进程内的「模拟局域网中转」：不碰任何网络，纯粹在内存里演一遍 LocalSend 风格的交互，
 * 让 UI 现在就能完整演示「发现 → 建会话 → 推送 → 收到回执」整条链路。
 *
 * 它对外表现与真实中转完全一致（同样返回 [RelayDevice] / [RelaySession] / [RelayPushResult]），
 * 所以以后换成 [LanRelayTransport] 时，上层毫无感知。
 */
class MockRelayTransport : RelayTransport {

    override val scheme: String = "mock"

    override suspend fun discover(timeoutMs: Long): List<RelayDevice> {
        // 模拟一下网络发现的耗时
        delay(minOf(timeoutMs, 600))
        return listOf(
            RelayDevice(
                id = "mock-relay-local",
                name = "本地模拟中转",
                host = "localhost",
                port = 0,
                fingerprint = "MOCK·F0:9A:3C:…:D1",
                isMock = true,
            ),
        )
    }

    override suspend fun openSession(device: RelayDevice): RelaySession {
        delay(150)
        return RelaySession(
            device = device,
            token = "mock-session-${Random.nextInt(100000, 999999)}",
            capabilities = listOf("prepare", "transfer", "accept-json"),
        )
    }

    override suspend fun push(
        session: RelaySession,
        batch: RelayBatch,
        onProgress: (Float) -> Unit,
    ): RelayPushResult {
        val total = batch.items.sumOf { it.count }.coerceAtLeast(1)
        var sent = 0
        batch.items.forEach { item ->
            // 逐类推送，期间回报进度，增强真实感
            delay(120)
            sent += item.count
            onProgress(sent.toFloat() / total)
        }
        delay(80)
        onProgress(1f)
        return RelayPushResult.Success(
            acceptedItems = batch.items.sumOf { it.count },
            acceptedBytes = batch.items.sumOf { it.bytes },
            receipt = "mock-receipt-${System.currentTimeMillis() % 100000}",
        )
    }
}
