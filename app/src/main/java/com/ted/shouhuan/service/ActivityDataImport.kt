package com.ted.shouhuan.service

import android.content.Context
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.data.HeartRateSample
import com.ted.shouhuan.data.SleepNightRecord
import com.ted.shouhuan.proto.MinuteSample
import com.ted.shouhuan.widget.WidgetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * 同步结果的统一落库：睡眠夜 + 心率分钟序列 + 桌面控件刷新。
 *
 * 手环同步（设备页手动 / 打开 app 自动拉取）和健康连接导入走同一个入口，
 * 保证两个来源产出的本地数据长得一模一样 —— 睡眠页、首页通知栏、桌面控件
 * 不用关心数据是哪条路进来的。入库本身幂等（见 [BandPrefs.replaceSleepNight]），
 * 重复导入同一批数据没有副作用。
 */
object ActivityDataImport {

    /**
     * @param nights 解析出的睡眠夜；空列表也没关系（可能这轮确实没有新睡眠）。
     * @param samples 原始分钟样本，提取其中心率 > 0 的分钟进心率序列；健康连接
     *   来源没有分钟样本，保持默认空列表。
     */
    suspend fun import(
        context: Context,
        prefs: BandPrefs,
        nights: List<SleepNightRecord>,
        samples: List<MinuteSample> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        prefs.importSleepNights(nights)

        // 心率 0 是「这分钟没测到」（未佩戴/监测关了），直接丢；非 0 异常值
        // 交给 [BandPrefs.replaceHeartRateSamples] 的口径过滤。
        val zone = ZoneId.systemDefault()
        val hr = samples
            .filter { it.heartRate > 0 }
            .map { HeartRateSample(it.time.atZone(zone).toInstant().toEpochMilli(), it.heartRate) }
        prefs.replaceHeartRateSamples(hr)

        WidgetRenderer.updateAll(context)
    }
}
