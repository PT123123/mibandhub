package com.ted.shouhuan.ui.sleep

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.data.SleepNightRecord
import com.ted.shouhuan.service.SleepSyncManager
import com.ted.shouhuan.util.minuteOfDayToClock
import com.ted.shouhuan.util.weekdayOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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

    /**
     * 睡眠同步状态 —— 进程级共享（service/SleepSyncManager），设备页的
     * 「同步手环数据」看的是同一份：在哪边拉，两边进度都动。
     */
    val sleepSync = SleepSyncManager.phase

    init {
        viewModelScope.launch { prefs.removeDemoSleep() }
    }

    /** 进入页面时自动拉一次（带去抖：2 分钟内切出去再进来不重复折腾手环）。 */
    fun syncOnEnter() = SleepSyncManager.syncAuto(getApplication())

    /** 手动同步（下拉刷新 / 同步按钮）：总是真的跑。 */
    fun syncNow() = SleepSyncManager.sync(getApplication())

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

    // ------------------------------------------------------------------
    // 数据导入：和上面的 CSV 导出一一对应（导出后能再导回来）
    // ------------------------------------------------------------------

    /**
     * 读取用户在系统文件选择器里选的 CSV，解析后并入本地睡眠史。
     *
     * 与导出同格式：`日期,星期,入睡,醒来,总睡眠(分钟),得分,深睡(分钟),浅睡(分钟),REM(分钟),清醒(分钟)`，
     * 日期是「醒来那天」、入睡/醒来是当天第几分钟。表头行自动跳过；解析不出某行就跳过那行
     * （计入 [SleepImportResult.skipped]），不影响其它行。合并走 [BandPrefs.mergeSleepNights]，
     * 同一醒来日取总分钟多的那条。
     */
    suspend fun importFromFile(uri: Uri): SleepImportResult = withContext(Dispatchers.IO) {
        val text = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                // 去掉导出时写的 UTF-8 BOM，否则首列日期会被吃掉前三个字节
                val noBom = if (bytes.size >= 3 &&
                    bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
                ) {
                    bytes.copyOfRange(3, bytes.size)
                } else {
                    bytes
                }
                String(noBom, Charsets.UTF_8)
            }
        }.getOrNull()

        if (text == null) return@withContext SleepImportResult(0, 0, "无法读取文件")

        val (records, skipped) = parseSleepCsv(text)
        if (records.isNotEmpty()) prefs.mergeSleepNights(records)
        SleepImportResult(
            imported = records.size,
            skipped = skipped,
            message = buildString {
                append("已导入 ${records.size} 晚")
                if (skipped > 0) append("，跳过 $skipped 行")
            },
        )
    }

    /** 把 CSV 文本折成记录 + 跳过数；表头（含「日期」字样）自动跳过。 */
    private fun parseSleepCsv(text: String): Pair<List<SleepNightRecord>, Int> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return emptyList<SleepNightRecord>() to 0
        val dataLines = if (lines.first().contains("日期")) lines.drop(1) else lines

        val records = mutableListOf<SleepNightRecord>()
        var skipped = 0
        for (line in dataLines) {
            val p = line.split(',')
            if (p.size < 10) { skipped++; continue }
            try {
                val epochDay = parseDate(p[0]) ?: throw IllegalArgumentException("日期")
                val bed = parseClock(p[2]) ?: throw IllegalArgumentException("入睡")
                val wake = parseClock(p[3]) ?: throw IllegalArgumentException("醒来")
                val total = p[4].trim().toIntOrNull() ?: throw IllegalArgumentException("总睡眠")
                val score = p[5].trim().toIntOrNull() ?: throw IllegalArgumentException("得分")
                val deep = p[6].trim().toIntOrNull() ?: throw IllegalArgumentException("深睡")
                val light = p[7].trim().toIntOrNull() ?: throw IllegalArgumentException("浅睡")
                val rem = p[8].trim().toIntOrNull() ?: throw IllegalArgumentException("REM")
                val awake = p[9].trim().toIntOrNull() ?: throw IllegalArgumentException("清醒")
                records.add(
                    SleepNightRecord(epochDay, total, score, bed, wake, deep, light, rem, awake),
                )
            } catch (_: Exception) {
                skipped++
            }
        }
        return records to skipped
    }

    /** "2026-09-18" / "2026/9/18" → epochDay（醒来那天）。 */
    private fun parseDate(s: String): Long? {
        val t = s.trim()
        val fmt = if (t.contains('/')) {
            DateTimeFormatter.ofPattern("yyyy/M/d")
        } else {
            DateTimeFormatter.ofPattern("yyyy-M-d")
        }
        return runCatching { LocalDate.parse(t, fmt).toEpochDay() }.getOrNull()
    }

    /** "23:30" / "7:05" → 当天第几分钟。 */
    private fun parseClock(s: String): Int? {
        val m = Regex("""(\d{1,2}):(\d{2})""").matchEntire(s.trim()) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }
}

/** 导入结果：成功并入几晚、跳过了几行、给用户看的反馈文案。 */
data class SleepImportResult(val imported: Int, val skipped: Int, val message: String)
