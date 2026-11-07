package com.mibandhub.app.relay

import com.mibandhub.app.data.BandPrefs
import com.mibandhub.app.data.asRecord
import com.mibandhub.app.relay.model.RelayBatch
import com.mibandhub.app.relay.model.RelayPayloadItem
import kotlinx.coroutines.flow.first

/**
 * 同步数据的来源抽象 —— 传输层只认 [RelayBatch]，不关心数据从哪来。
 *
 * 默认实现 [BandDataPayloadSource] 从 [BandPrefs] 取数据打包；
 * 以后要中继别的数据（比如 Health Connect、第三方 CSV），实现这个接口换掉即可，
 * 传输层与 UI 都不用动。
 */
interface RelayPayloadSource {
    /** 生成当前要同步的一批数据；没有数据返回空 items。 */
    suspend fun buildBatch(sourceAlias: String): RelayBatch
}

class BandDataPayloadSource(private val prefs: BandPrefs) : RelayPayloadSource {

    override suspend fun buildBatch(sourceAlias: String): RelayBatch {
        val hr = prefs.heartRateSamples.first()
        val sleep = prefs.sleepHistory.first()

        // 测量记录是两个来源合流后同步的：桌面端靠每条的 source 字段区分
        // 手环 / 手机闪光灯。存储上仍是两个独立 key，互不影响老 CSV 契约。
        val bandMeasures = prefs.measureHistory.first()
        val ppgMeasures = prefs.ppgMeasureHistory.first()
        val measures = (bandMeasures.map { it.asRecord() } + ppgMeasures.map { it.asRecord() })
            .sortedByDescending { it.finishedAtMillis }

        val notifications = prefs.recentNotifications.first()
        val name = prefs.name.first()
        val mac = prefs.mac.first()

        val items = buildList {
            if (hr.isNotEmpty()) {
                add(RelayPayloadItem("heart_rate", hr.size, hr.heartRateSamplesToRelayJson().utf8Bytes(), hr.heartRateSamplesToRelayJson()))
            }
            if (sleep.isNotEmpty()) {
                add(RelayPayloadItem("sleep", sleep.size, sleep.sleepNightsToRelayJson().utf8Bytes(), sleep.sleepNightsToRelayJson()))
            }
            if (measures.isNotEmpty()) {
                val json = measures.measuresToRelayJson()
                add(RelayPayloadItem("measure", measures.size, json.utf8Bytes(), json))
            }
            if (notifications.isNotEmpty()) {
                add(RelayPayloadItem("notification", notifications.size, notifications.notificationsToRelayJson().utf8Bytes(), notifications.notificationsToRelayJson()))
            }
        }

        return RelayBatch(
            sessionId = "batch-${System.currentTimeMillis()}",
            sourceAlias = sourceAlias,
            createdAt = System.currentTimeMillis(),
            items = items,
            deviceName = name,
            deviceMac = mac,
        )
    }
}
