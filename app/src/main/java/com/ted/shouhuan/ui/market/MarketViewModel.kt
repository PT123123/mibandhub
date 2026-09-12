package com.ted.shouhuan.ui.market

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ted.shouhuan.data.MarketEntry
import com.ted.shouhuan.data.MarketRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * 表盘市场的状态机：拉目录 → 并行预取预览图 → 按需下载表盘包。
 * 下载完成的表盘进 filesDir，表盘页的库会看到它们。
 */
class MarketViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MarketRepository.get(app)

    private val _state = MutableStateFlow(MarketUiState())
    val state: StateFlow<MarketUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 拉目录。预览图不再全量预取 —— 上百张预取又慢又浪费，卡片滑到再拉（[ensurePreview]）。 */
    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repo.fetchCatalog() }
            }
            result.fold(
                onSuccess = { catalog ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = null,
                            updated = catalog.updated,
                            entries = catalog.faces,
                            downloadedIds = downloadedIds(),
                            progress = emptyMap(),
                            // 之前会话缓存的预览图直接解码复用
                            previews = catalog.faces.mapNotNull { entry ->
                                repo.peekPreviewCache(entry)?.let { f ->
                                    decode(f)?.let { bmp -> entry.id to bmp }
                                }
                            }.toMap(),
                        )
                    }
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = t.message ?: t.javaClass.simpleName,
                            downloadedIds = downloadedIds(),
                        )
                    }
                },
            )
        }
    }

    /** 卡片进入画面时调用：预览图没拉过就拉一次（磁盘缓存 + 内存缓存）。 */
    fun ensurePreview(entry: MarketEntry) {
        val current = _state.value
        if (current.previews.containsKey(entry.id) || entry.id in current.previewLoading) return
        _state.update { it.copy(previewLoading = it.previewLoading + entry.id) }
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
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

    private fun setProgress(id: String, percent: Int) {
        _state.update { it.copy(progress = it.progress + (id to percent)) }
    }

    private fun downloadedIds(): Set<String> =
        repo.loadDownloaded().map { it.id }.toSet()

    private fun decode(file: File): ImageBitmap? =
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
}
