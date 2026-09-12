package com.ted.shouhuan.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.ted.shouhuan.proto.ActivitySync
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * 从健康连接（Health Connect）读睡眠 —— 外部数据源的第一条通道。
 *
 * 小米运动健康（com.mi.health）在设置里开了「同步数据到健康连接」之后，
 * 睡眠会话会出现在这里。手环被 ack 清掉的历史（手环只留 ~30 天、同步即删）、
 * 或者手环不在身边的日子，都能从这条通道补回来 —— 小米运动健康那边是全量保存的。
 *
 * 口径对齐手环同步（[ActivitySync.nightsFromSamples]）：totalMinutes 只含
 * 深睡+浅睡+REM，清醒单独记；不足 30 分钟的会话当碎片丢弃；epochDay 记醒来那天。
 * 分数没有官方算法，两个来源统一用 [ActivitySync.sleepScore]，数值才有可比性。
 */
class HealthConnectSleepSource(private val context: Context) {

    /** 健康连接可用性 —— 各状态都有给用户看的话术。 */
    sealed interface Availability {
        data object Ready : Availability
        data class Unavailable(val reason: String) : Availability
    }

    fun availability(): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.Ready
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.Unavailable(
            "这台手机的健康连接服务不可用 —— 去应用商店把「健康连接」App 装上或更新",
        )
        else -> Availability.Unavailable("这台手机不支持健康连接（需要 Android 9+ 且装有健康连接）")
    }

    /** 还缺哪些读权限；空集 = 可以直接读。 */
    suspend fun missingPermissions(): Set<String> {
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        return setOf(HealthPermission.getReadPermission(SleepSessionRecord::class)) - granted
    }

    /**
     * 读 [since] 以来的全部睡眠会话并归并成夜。
     * 返回空列表通常意味着来源 App（小米运动健康）还没把数据同步进健康连接
     * —— 先在它的设置里打开同步，等一轮再试。
     */
    suspend fun readNights(since: ZonedDateTime): List<SleepNightRecord> {
        val client = HealthConnectClient.getOrCreate(context)
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(since.toInstant(), Instant.now()),
            ),
        )
        return response.records.mapNotNull(::toNight)
    }

    /** 会话 → 夜。分期按四段累计；不足 30 分钟当碎片丢弃（和手环口径一致）。 */
    private fun toNight(session: SleepSessionRecord): SleepNightRecord? {
        var deep = 0f
        var light = 0f
        var rem = 0f
        var awake = 0f
        val stages = session.stages
        if (stages.isEmpty()) {
            // 少数来源只写会话不写分期 —— 整段按浅睡计，至少时长是准的
            light = minutesBetween(session.startTime, session.endTime)
        } else {
            stages.forEach { stage ->
                val minutes = minutesBetween(stage.startTime, stage.endTime)
                when (stage.stage) {
                    SleepSessionRecord.STAGE_TYPE_DEEP -> deep += minutes
                    SleepSessionRecord.STAGE_TYPE_REM -> rem += minutes
                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
                    -> awake += minutes
                    // SLEEPING / UNKNOWN 语义含糊，保守算浅睡
                    else -> light += minutes
                }
            }
        }

        val deepMin = deep.roundToInt()
        val remMin = rem.roundToInt()
        val lightMin = light.roundToInt()
        val total = deepMin + lightMin + remMin
        if (total < MIN_NIGHT_MINUTES) return null

        val bed = session.startTime.atZone(ZoneId.systemDefault())
        val wake = session.endTime.atZone(ZoneId.systemDefault())
        return SleepNightRecord(
            epochDay = wake.toLocalDate().toEpochDay(),
            totalMinutes = total,
            score = ActivitySync.sleepScore(total, deepMin, remMin),
            bedMinutes = bed.hour * 60 + bed.minute,
            wakeMinutes = wake.hour * 60 + wake.minute,
            deepMinutes = deepMin,
            lightMinutes = lightMin,
            remMinutes = remMin,
            awakeMinutes = awake.roundToInt(),
        )
    }

    private fun minutesBetween(start: Instant, end: Instant): Float =
        Duration.between(start, end).toMillis() / 60_000f

    private companion object {
        const val MIN_NIGHT_MINUTES = 30
    }
}
