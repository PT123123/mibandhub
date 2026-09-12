package com.ted.shouhuan.ui.sleep

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.data.SleepNightRecord
import com.ted.shouhuan.util.minuteOfDayToClock
import com.ted.shouhuan.util.weekdayOf
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 睡眠页的状态。
 *
 * 数据只有一份来源：本地存储（[BandPrefs.sleepHistory]），由手环同步写入
 * （DeviceViewModel → importSleepNights）。这里不生成任何演示数据；
 * 早期版本播种过的假记录在首次进入本页时静默清掉。
 */
class SleepViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = BandPrefs(app)

    /** 全部睡眠夜，新的在前。存多久算多久，范围筛选交给界面。 */
    val nights: StateFlow<List<SleepNightRecord>> = prefs.sleepHistory
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { prefs.removeDemoSleep() }
    }

    // ------------------------------------------------------------------
    // 数据导出：小数据进剪贴板，大了写文件（CSV，Excel/Numbers 直接打开）
    // ------------------------------------------------------------------

    /** CSV 超过这个字符数就不塞剪贴板了 —— 部分输入法/ROM 会在大文本上悄悄截断。 */
    private val clipboardExportLimit = 20_000

    /** 当前历史折成 CSV 后有多大。 */
    fun csvCharLength(): Int = buildCsv().length

    /** 小数据直接进剪贴板；返回是否成功（超限也返回 false，走文件导出）。 */
    fun exportToClipboard(): Boolean {
        val csv = buildCsv()
        if (csv.length > clipboardExportLimit) return false
        val clipboard = getApplication<Application>()
            .getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("shouhuan-sleep", csv))
        return true
    }

    /** SAF 文档名（CreateDocument 的建议初始名）。 */
    fun suggestedFileName(): String {
        val d = LocalDate.now()
        return "shouhuan_sleep_%04d%02d%02d.csv".format(d.year, d.monthValue, d.dayOfMonth)
    }

    /** 把 CSV 写进用户在系统文件选择器里选的位置。带 UTF-8 BOM，Excel 打开不乱码。 */
    suspend fun exportToFile(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                // BOM：没有它 Excel 会按本地编码读 UTF-8 中文表头，出来全是乱码
                out.write("\uFEFF".toByteArray(Charsets.UTF_8))
                out.write(buildCsv().toByteArray(Charsets.UTF_8))
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    /** 历史条数，给导出成功的反馈文案用。 */
    fun nightCount(): Int = nights.value.size

    /**
     * 全部历史折成 CSV。
     *
     * 字段无逗号/引号/换行（日期、钟点、整数），不需要 CSV 转义；
     * 每行按「醒来那天」记 —— 和界面上的记录口径一致。
     */
    private fun buildCsv(): String {
        val sb = StringBuilder()
        sb.append("日期,星期,入睡,醒来,总睡眠(分钟),得分,深睡(分钟),浅睡(分钟),REM(分钟),清醒(分钟)\n")
        nights.value.forEach { n ->
            val d = LocalDate.ofEpochDay(n.epochDay)
            sb.append("%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)).append(',')
            sb.append(weekdayOf(n.epochDay)).append(',')
            sb.append(minuteOfDayToClock(n.bedMinutes)).append(',')
            sb.append(minuteOfDayToClock(n.wakeMinutes)).append(',')
            sb.append(n.totalMinutes).append(',')
            sb.append(n.score).append(',')
            sb.append(n.deepMinutes).append(',')
            sb.append(n.lightMinutes).append(',')
            sb.append(n.remMinutes).append(',')
            sb.append(n.awakeMinutes).append('\n')
        }
        return sb.toString()
    }
}
