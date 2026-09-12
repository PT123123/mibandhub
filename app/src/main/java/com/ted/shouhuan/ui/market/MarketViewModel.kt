package com.ted.shouhuan.ui.market

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ted.shouhuan.data.BrowseMode
import com.ted.shouhuan.data.MarketEntry
import com.ted.shouhuan.data.MarketRepository
import com.ted.shouhuan.data.OnlineLang
import com.ted.shouhuan.data.OnlineMetric
import com.ted.shouhuan.data.OnlinePage
import com.ted.shouhuan.data.OnlinePaid
import com.ted.shouhuan.data.OnlinePeriod
import com.ted.shouhuan.data.OnlineTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** 一张市场表盘的下载进度状态。 */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val percent: Int) : DownloadState
    data object Done : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** 市场页的整体状态。 */
data class MarketUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val updated: String = "",
    val entries: List<MarketEntry> = emptyList(),
    /** 在线翻页：还有没有下一页 / 正在拉下一页 / 已拉到第几页。 */
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadedPages: Int = 1,
    /** 在线源不可达，已回落到本地快照目录 —— 界面据此提示，且不再翻页。 */
    val degraded: Boolean = false,
    /** 回落的原因（给用户看的：是 403 还是空响应），degraded 时显示在目录行。 */
    val degradedMessage: String? = null,
    /** 当前浏览模式：最新上传 / 热门榜（口径 × 时间窗）/ 最近更新。 */
    val mode: BrowseMode = BrowseMode.FRESH,
    val metric: OnlineMetric = OnlineMetric.DOWNLOADS,
    val period: OnlinePeriod = OnlinePeriod.ALL_TIME,
    /** 功能标签多选（空集 = 不限）。在线模式下走站点 tags= 参数（交集语义）。 */
    val tags: Set<OnlineTag> = emptySet(),
    /** 语言筛选。ANY = 不传参。 */
    val lang: OnlineLang = OnlineLang.ANY,
    /** 免费/付费。ANY = 不传参。 */
    val paid: OnlinePaid = OnlinePaid.ANY,
    /** 只看站点认证（verified=1）。 */
    val verifiedOnly: Boolean = false,
    val query: String? = null,
    /** 只看已下载：本地过滤，任何模式（含回落快照）下都可用。 */
    val onlyDownloaded: Boolean = false,
    /** id → 已下载（在本地 filesDir 里有本体）。 */
    val downloadedIds: Set<String> = emptySet(),
    /** id → 下载进度（只有下载中的才有条目）。 */
    val progress: Map<String, Int> = emptyMap(),
    /** id → 下载失败原因。 */
    val failed: Map<String, String> = emptyMap(),
    /** id → 预览图（懒加载：卡片滑到才拉）。 */
    val previews: Map<String, ImageBitmap> = emptyMap(),
    /** 预览图正在加载中的 id 集合（防重复发起）。 */
    val previewLoading: Set<String> = emptySet(),
)

/**
 * 界面真正要展示的列表：本地过滤（只看已下载）+ 回落模式下的标签过滤，
 * 套在拉取结果上。在线模式的标签/语言/价格/搜索都由站点服务端完成，这里不重复做。
 */
fun MarketUiState.visibleEntries(): List<MarketEntry> {
    val tagParams = tags.map { it.param }.toSet()
    return entries.filter { entry ->
        (!onlyDownloaded || entry.id in downloadedIds) &&
            // 站点多选标签是交集语义，本地过滤保持一致
            (!degraded || tagParams.isEmpty() || entry.tags.containsAll(tagParams))
    }
}

/**
 * 表盘市场的状态机：在线源（amazfitwatchfaces.com）拉目录 → 并行预取预览图 →
 * 按需下载表盘包。在线源挂了回落自建快照目录，界面不至于全空。
 * 下载完成的表盘进 filesDir，表盘页的库会看到它们。
 */
class MarketViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MarketRepository.get(app)

    /**
     * 预览图解码单独一条有上限的线程池（4 条）。
     * 用默认的 Dispatchers.IO 不行 —— 它有 64 条线程，滑一格就是三张图同时解码，
     * 每张 268×622 的位图 ≈ 666 KB，几十张并行会把内存和 IO 一起顶满，
     * 表现就是「一直转圈、滑哪张都没图」。
     */
    private val decodePool =
        Executors.newFixedThreadPool(4) { r -> Thread(r, "market-preview").apply { isDaemon = true } }
            .asCoroutineDispatcher()

    private val _state = MutableStateFlow(MarketUiState())
    val state: StateFlow<MarketUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    override fun onCleared() {
        super.onCleared()
        decodePool.close()
    }

    /**
     * 按当前模式/筛选条件重新拉第一页。
     * 在线源不可达时回落自建快照目录（[loadSnapshotFallback]），不直接报错。
     */
    fun refresh() {
        if (_state.value.loading) return
        val s = _state.value
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repo.fetchOnlinePage(
                        page = 1,
                        mode = s.mode,
                        metric = s.metric,
                        period = s.period,
                        query = s.query,
                        tags = s.tags,
                        lang = s.lang,
                        paid = s.paid,
                        verifiedOnly = s.verifiedOnly,
                    )
                }
            }
            result.fold(
                onSuccess = { page -> applyOnlinePage(page, reset = true) },
                onFailure = { t -> loadSnapshotFallback(t) },
            )
        }
    }

    /** 切浏览模式。标签/语言等筛选跨模式保留（站点参数在三条目录路径上都生效）。 */
    fun setMode(mode: BrowseMode) {
        if (_state.value.mode == mode) return
        _state.update { it.copy(mode = mode, query = null) }
        refresh()
    }

    fun setMetric(metric: OnlineMetric) {
        if (_state.value.metric == metric) return
        _state.update { it.copy(metric = metric) }
        refresh()
    }

    fun setPeriod(period: OnlinePeriod) {
        if (_state.value.period == period) return
        _state.update { it.copy(period = period) }
        refresh()
    }

    /** 勾选/取消一个功能标签（多选，站点按交集过滤）。搜索词一并清掉（搜索覆盖标签）。 */
    fun toggleTag(tag: OnlineTag) {
        if (_state.value.query != null) {
            _state.update { it.copy(query = null, tags = setOf(tag)) }
        } else {
            val next = _state.value.tags.let { if (tag in it) it - tag else it + tag }
            if (next == _state.value.tags) return
            _state.update { it.copy(tags = next) }
        }
        // 回落快照目录时标签走本地过滤（见 visibleEntries），不用重拉
        if (!_state.value.degraded) refresh()
    }

    /** 清掉全部标签。 */
    fun clearTags() {
        if (_state.value.tags.isEmpty()) return
        _state.update { it.copy(tags = emptySet()) }
        if (!_state.value.degraded) refresh()
    }

    /** 选语言；ANY = 不限。 */
    fun setLang(lang: OnlineLang) {
        if (_state.value.lang == lang) return
        _state.update { it.copy(lang = lang) }
        refresh()
    }

    /** 免费/付费；ANY = 不限。 */
    fun setPaid(paid: OnlinePaid) {
        if (_state.value.paid == paid) return
        _state.update { it.copy(paid = paid) }
        refresh()
    }

    /** 只看站点认证（verified=1）。 */
    fun setVerifiedOnly(enabled: Boolean) {
        if (_state.value.verifiedOnly == enabled) return
        _state.update { it.copy(verifiedOnly = enabled) }
        refresh()
    }

    /** 只看已下载：纯本地过滤，不重新拉目录。 */
    fun setOnlyDownloaded(enabled: Boolean) {
        _state.update { it.copy(onlyDownloaded = enabled) }
    }

    /** 提交搜索词；空串 = 退出搜索。 */
    fun submitSearch(raw: String) {
        val query = raw.trim().takeIf { it.isNotEmpty() }
        if (_state.value.query == query) return
        _state.update { it.copy(query = query, tags = emptySet()) }
        refresh()
    }

    /** 滑到底部时拉下一页，追加进现有列表。 */
    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        val nextPage = current.loadedPages + 1
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repo.fetchOnlinePage(
                        page = nextPage,
                        mode = current.mode,
                        metric = current.metric,
                        period = current.period,
                        query = current.query,
                        tags = current.tags,
                        lang = current.lang,
                        paid = current.paid,
                        verifiedOnly = current.verifiedOnly,
                    )
                }
            }
            result.fold(
                onSuccess = { page -> applyOnlinePage(page, reset = false) },
                onFailure = { _ ->
                    // 翻页失败就当到底了，别让滚动监听反复重试
                    _state.update { it.copy(loadingMore = false, hasMore = false) }
                },
            )
        }
    }

    private fun applyOnlinePage(page: OnlinePage, reset: Boolean) {
        _state.update { current ->
            val merged = if (reset) {
                page.entries
            } else {
                val known = current.entries.map { it.id }.toSet()
                current.entries + page.entries.filter { it.id !in known }
            }
            current.copy(
                loading = false,
                loadingMore = false,
                error = null,
                degraded = false,
                degradedMessage = null,
                updated = "",
                entries = merged,
                loadedPages = if (reset) 1 else current.loadedPages + 1,
                // 站点翻到空页就说明到底了；至少有一张才算还有下一页
                hasMore = page.entries.isNotEmpty(),
                downloadedIds = downloadedIds(),
                // 换排序/翻页都重置预览缓存？不 —— 只有整体重拉才清，翻页保留已解码的
                previews = if (reset) emptyMap() else current.previews,
                previewLoading = if (reset) emptySet() else current.previewLoading,
            )
        }
        if (reset) {
            // 首屏可见的那几张同步解码，别让用户对着转圈等
            val entries = _state.value.entries.take(INITIAL_DECODE_LIMIT)
            val decoded = entries.mapNotNull { entry ->
                repo.fetchPreviewCached(entry)?.let { bytes ->
                    decode(bytes)?.let { bmp -> entry.id to bmp }
                }
            }.toMap()
            _state.update { it.copy(previews = it.previews + decoded) }
        }
        // 整页慢慢预取：并发 4，不占界面；滑到哪张通常已经有料
        repo.startPreviewPrefetch(page.entries)
    }

    /** 在线源不可达：回落自建快照目录，界面照常能浏览/下载/删除。 */
    private fun loadSnapshotFallback(cause: Throwable) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.fetchCatalog() } }
            result.fold(
                onSuccess = { catalog ->
                    _state.update {
                        it.copy(
                            loading = false,
                            loadingMore = false,
                            degraded = true,
                            degradedMessage = cause.message,
                            error = null,
                            updated = catalog.updated,
                            entries = catalog.faces,
                            loadedPages = 1,
                            hasMore = false,
                            downloadedIds = downloadedIds(),
                            previews = catalog.faces.take(INITIAL_DECODE_LIMIT).mapNotNull { entry ->
                                repo.fetchPreviewCached(entry)?.let { b ->
                                    decode(b)?.let { bmp -> entry.id to bmp }
                                }
                            }.toMap(),
                        )
                    }
                    repo.startPreviewPrefetch(catalog.faces)
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            loading = false,
                            loadingMore = false,
                            degraded = false,
                            degradedMessage = null,
                            error = "在线目录拉取失败（${cause.message}），本地快照也不可用（${t.message}）",
                            downloadedIds = downloadedIds(),
                        )
                    }
                },
            )
        }
    }

    /** 卡片进入画面时调用：预览图没拉过就拉一次（内存缓存 + 磁盘缓存）。 */
    fun ensurePreview(entry: MarketEntry) {
        val current = _state.value
        if (current.previews.containsKey(entry.id) || entry.id in current.previewLoading) return
        _state.update { it.copy(previewLoading = it.previewLoading + entry.id) }
        viewModelScope.launch {
            val bmp = withContext(decodePool) {
                repo.fetchPreviewCached(entry)?.let { decode(it) }
            }
            _state.update {
                it.copy(
                    previews = if (bmp != null) it.previews + (entry.id to bmp) else it.previews,
                    previewLoading = it.previewLoading - entry.id,
                )
            }
        }
    }

    fun download(entry: MarketEntry) {
        val current = _state.value
        if (current.progress.containsKey(entry.id)) return
        _state.update {
            it.copy(progress = it.progress + (entry.id to 0), failed = it.failed - entry.id)
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repo.downloadFace(entry) { percent -> setProgress(entry.id, percent) } }
            }
            result.fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            progress = it.progress - entry.id,
                            downloadedIds = it.downloadedIds + entry.id,
                            failed = it.failed - entry.id,
                        )
                    }
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            progress = it.progress - entry.id,
                            failed = it.failed + (entry.id to (t.message ?: t.javaClass.simpleName)),
                        )
                    }
                },
            )
        }
    }

    fun delete(entry: MarketEntry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(entry.id) }
            _state.update { it.copy(downloadedIds = it.downloadedIds - entry.id) }
        }
    }

    /**
     * 读一张已下载表盘的包体 —— 市场卡片「安装」直接下发用。
     * 没下过/读不到返回 null，由界面提示。
     */
    suspend fun faceBytes(entry: MarketEntry): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            repo.storedFace(entry.id)?.binFile?.takeIf { it.isFile }?.readBytes()
        }.getOrNull()
    }

    private fun setProgress(id: String, percent: Int) {
        _state.update { it.copy(progress = it.progress + (id to percent)) }
    }

    private fun downloadedIds(): Set<String> =
        repo.loadDownloaded().map { it.id }.toSet()

    /**
     * 解码预览图。
     *
     * 两个要点：
     * ① 按 2 的幂 inSampleSize 缩到 ~180px 宽 —— 卡片实际只有 110dp 宽，
     *    源图 268×622 全尺寸解码是白烧内存；缩完一张 ≈ 60 KB。
     * ② 动图（GIF）取首帧即可，[BitmapFactory] 默认就是这个行为，不需要额外处理，
     *    但绝不能拿去整张内存缓存 —— 那是 149 × 全尺寸位图。
     */
    private fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, TARGET_PX)
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()

    /** 取 2 的幂，保证缩完不小于 [target] 像素宽（再小就该糊了）。 */
    private fun sampleSizeFor(width: Int, target: Int): Int {
        if (width <= 0 || width <= target) return 1
        var sample = 1
        while (width / (sample * 2) >= target) sample *= 2
        return sample
    }

    private companion object {
        /** 卡片宽 110dp 上下，取 180px 够 3x 屏用了。 */
        const val TARGET_PX = 180

        /** 进页面时同步解这几张（首屏可见区），其余交给后台预取。 */
        const val INITIAL_DECODE_LIMIT = 12
    }
}
