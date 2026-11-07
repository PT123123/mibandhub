package com.mibandhub.app.relay

import com.mibandhub.app.data.BandNotification
import com.mibandhub.app.data.HeartRateSample
import com.mibandhub.app.data.MeasureResult
import com.mibandhub.app.data.SleepNightRecord
import com.mibandhub.app.relay.model.RelayBatch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把手环数据序列化成传输层要的 JSON 字符串（传输层不解析，这里只负责「装车」）。
 *
 * 字段名严格对齐桌面端 health-motion 的 [ShouhuanData]（snake_case），见桌面端
 * `src/sources/shouhuan.rs` 与 `src/lan.rs`：睡眠 9 列、心率 at_millis/bpm、
 * 测量 bpm/finished_at_millis/duration_sec。名字错一个字母桌面端都会静默丢字段。
 *
 * 注意：扩展函数名必须各不相同 —— 它们都挂在 `List<*>` 上，JVM 擦除后签名相同会冲突，
 * 所以这里是 `heartRateSamplesToRelayJson()` 这种带前缀的名字，而不是统一的 `toRelayJson()`。
 */

internal fun List<HeartRateSample>.heartRateSamplesToRelayJson(): String = JSONArray().apply {
    forEach { put(JSONObject().put("at_millis", it.atMillis).put("bpm", it.bpm)) }
}.toString()

internal fun List<SleepNightRecord>.sleepNightsToRelayJson(): String = JSONArray().apply {
    forEach { n ->
        put(
            JSONObject().apply {
                put("epoch_day", n.epochDay)
                put("total_minutes", n.totalMinutes)
                put("score", n.score)
                put("bed_minutes", n.bedMinutes)
                put("wake_minutes", n.wakeMinutes)
                put("deep_minutes", n.deepMinutes)
                put("light_minutes", n.lightMinutes)
                put("rem_minutes", n.remMinutes)
                put("awake_minutes", n.awakeMinutes)
            },
        )
    }
}.toString()

internal fun List<MeasureResult>.measuresToRelayJson(): String = JSONArray().apply {
    forEach {
        put(
            JSONObject()
                .put("bpm", it.bpm)
                .put("finished_at_millis", it.finishedAtMillis)
                .put("duration_sec", it.durationSec),
        )
    }
}.toString()

internal fun List<BandNotification>.notificationsToRelayJson(): String = JSONArray().apply {
    forEach {
        put(
            JSONObject().apply {
                put("app", it.appName)
                put("title", it.title)
                put("body", it.body)
                put("time", it.timeLabel)
                put("forwarded", it.forwarded)
                put("pkg", it.packageName)
            },
        )
    }
}.toString()

internal fun String.utf8Bytes(): Long = toByteArray(Charsets.UTF_8).size.toLong()

/**
 * 把一批手环数据打成桌面端 LAN 同步要的 [Snapshot] JSON（仅 band 部分，gym 由 gymbuddy 侧负责）。
 *
 * 三个数组（sleeps / hr_samples / measures）**始终带**（没有数据时为空数组），因为桌面端
 * `ShouhuanData` 的字段没有 `#[serde(default)]`，缺键会导致整段解析失败。
 * 通知不进 Snapshot（桌面端 ShouhuanData 没有通知字段），桌面端按自身逻辑另行处理。
 */
internal fun RelayBatch.toSnapshotJson(): String {
    val sleeps = items.firstOrNull { it.type == "sleep" }
        ?.let { runCatching { JSONArray(it.payload) }.getOrDefault(JSONArray()) }
        ?: JSONArray()
    val hr = items.firstOrNull { it.type == "heart_rate" }
        ?.let { runCatching { JSONArray(it.payload) }.getOrDefault(JSONArray()) }
        ?: JSONArray()
    val measures = items.firstOrNull { it.type == "measure" }
        ?.let { runCatching { JSONArray(it.payload) }.getOrDefault(JSONArray()) }
        ?: JSONArray()

    val band = JSONObject().apply {
        put("sleeps", sleeps)
        put("hr_samples", hr)
        put("measures", measures)
        deviceName?.let { put("device_name", it) }
        deviceMac?.let { put("device_mac", it) }
    }
    return JSONObject().put("band", band).toString()
}
