package com.ted.shouhuan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray

/** manifest.json 里的一条内置表盘。 */
data class BuiltInWatchFace(
    val id: String,
    val name: String,
    val author: String,
    val source: String,
    val license: String,
    val sizeBytes: Int,
    /** assets/watchfaces/ 下的 .bin 文件名。 */
    val file: String,
    /** assets/watchfaces/ 下的预览图文件名。 */
    val preview: String,
    val note: String?,
)

/**
 * 随 APK 打包的内置表盘（assets/watchfaces/）。
 *
 * 每张的来源与授权都写在 manifest.json 里、界面原样展示：
 * 社区表盘是别人的作品，只收条款明确允许再分发的那几张；
 * 其余是本仓库用 watchface-js 自制的（CC0）。
 *
 * Mi Band 5 的表盘是华米私有 .bin 容器（UIHH 文件头），不是 zip ——
 * 这也是内置表盘存在的另一半理由：拿真正的 .bin 去验证下发通道
 * 「手环到底认不认」（docs/watchface.md §3）。
 */
object BuiltInWatchFaces {

    private const val DIR = "watchfaces"

    /** manifest 解析失败就当没有内置表盘 —— 这只是入口之一，别让它崩掉整页。 */
    fun load(context: Context): List<BuiltInWatchFace> = runCatching {
        val json = context.assets.open("$DIR/manifest.json").use { it.readBytes().decodeToString() }
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            BuiltInWatchFace(
                id = o.getString("id"),
                name = o.getString("name"),
                author = o.getString("author"),
                source = o.getString("source"),
                license = o.getString("license"),
                sizeBytes = o.getInt("sizeBytes"),
                file = o.getString("file"),
                preview = o.getString("preview"),
                note = o.optString("note").takeIf { it.isNotEmpty() },
            )
        }
    }.getOrDefault(emptyList())

    /** 表盘包本体（.bin 字节）。 */
    fun readPayload(context: Context, face: BuiltInWatchFace): ByteArray =
        context.assets.open("$DIR/${face.file}").use { it.readBytes() }

    /** 预览图；解码失败返回 null，界面降级成无图卡片。 */
    fun readPreview(context: Context, face: BuiltInWatchFace): Bitmap? = runCatching {
        context.assets.open("$DIR/${face.preview}").use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
