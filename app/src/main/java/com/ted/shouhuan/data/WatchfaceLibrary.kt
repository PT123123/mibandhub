package com.ted.shouhuan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** 表盘库里的一个条目：内置（assets）或市场下载（filesDir）。 */
data class WatchfaceRef(
    val id: String,
    val name: String,
    val author: String,
    val license: String,
    val sizeBytes: Int,
    val note: String?,
    val source: Source,
    /**
     * 表盘包的位置：ASSET 是 assets/watchfaces/ 下的文件名，
     * DOWNLOADED 是绝对路径。
     */
    val binRef: String,
    /** 预览图，位置规则同 [binRef]。 */
    val previewRef: String,
) {
    enum class Source { ASSET, DOWNLOADED }
}

/**
 * 表盘库 = 内置表盘（随 APK 走，离线可用）+ 市场下载（filesDir）。
 * 界面只面对这一个列表；读字节/预览按 source 分流。
 */
object WatchfaceLibrary {

    fun load(context: Context): List<WatchfaceRef> {
        val builtIn = BuiltInWatchFaces.load(context).map { f ->
            WatchfaceRef(
                id = f.id,
                name = f.name,
                author = f.author,
                license = f.license,
                sizeBytes = f.sizeBytes,
                note = f.note,
                source = WatchfaceRef.Source.ASSET,
                binRef = f.file,
                previewRef = f.preview,
            )
        }
        val downloaded = MarketRepository.get(context).loadDownloaded().map { s ->
            WatchfaceRef(
                id = s.id,
                name = s.name,
                author = s.author,
                license = s.license,
                sizeBytes = s.sizeBytes,
                note = s.note,
                source = WatchfaceRef.Source.DOWNLOADED,
                binRef = s.binFile.absolutePath,
                previewRef = s.previewFile.absolutePath,
            )
        }
        return builtIn + downloaded
    }

    /** 表盘包字节。IO 操作，调用方自选线程。 */
    fun readPayload(context: Context, ref: WatchfaceRef): ByteArray = when (ref.source) {
        WatchfaceRef.Source.ASSET ->
            context.assets.open("watchfaces/${ref.binRef}").use { it.readBytes() }
        WatchfaceRef.Source.DOWNLOADED ->
            File(ref.binRef).readBytes()
    }

    /** 预览图解码；失败返回 null，界面降级成无图卡片。 */
    fun readPreview(context: Context, ref: WatchfaceRef): Bitmap? = when (ref.source) {
        WatchfaceRef.Source.ASSET -> runCatching {
            context.assets.open("watchfaces/${ref.previewRef}").use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()

        WatchfaceRef.Source.DOWNLOADED ->
            BitmapFactory.decodeFile(ref.previewRef)
    }
}
