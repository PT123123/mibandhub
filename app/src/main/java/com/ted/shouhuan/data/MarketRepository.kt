package com.ted.shouhuan.data

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.CRC32

/** 市场目录（market/index.json）里的一条。 */
data class MarketEntry(
    val id: String,
    val name: String,
    val author: String,
    val license: String,
    /** 相对 market/ 的路径，如 faces/mk01.bin。 */
    val file: String,
    val preview: String,
    val sizeBytes: Int,
    /** 十六进制串解析出来的 CRC32；目录没给就是 -1（跳过校验）。 */
    val crc32: Long,
    val note: String?,
)

data class MarketCatalog(
    val updated: String,
    val faces: List<MarketEntry>,
)

/** 已下载落地的表盘（filesDir/market/<id>/）。 */
data class StoredWatchFace(
    val id: String,
    val name: String,
    val author: String,
    val license: String,
    val sizeBytes: Int,
    val note: String?,
    val binFile: File,
    val previewFile: File,
)

/**
 * 在线市场的数据源：目录和表盘包都放在本仓库的 `market/` 目录里，
 * 走 GitHub raw 拉取。只收本仓库自制（CC0）的内容 —— 授权纪律见 docs/watchface.md。
 *
 * 全部方法都是阻塞 IO，调用方自己切线程。
 */
class MarketRepository private constructor(context: Context) {

    private val root: File = File(context.applicationContext.filesDir, "market")
    private val previewCache: File = File(context.applicationContext.filesDir, "market-cache/previews")

    companion object {
        /**
         * 目录清单地址：按顺序试，谁成谁算。
         * jsDelivr 是 GitHub 仓库的 CDN 镜像，国内可达性远好于
         * raw.githubusercontent.com（实测同一时刻一个 200 一个连不上），
         * 所以它做主源。代价是它有 CDN 缓存 —— 推完新目录要主动刷一次：
         * curl https://purge.jsdelivr.net/gh/PT123123/mibandhub@main/market/index.json
         */
        private val CATALOG_URLS = listOf(
            "https://cdn.jsdelivr.net/gh/PT123123/mibandhub@main/market/index.json",
            "https://raw.githubusercontent.com/PT123123/mibandhub/main/market/index.json",
        )
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000

        @Volatile
        private var instance: MarketRepository? = null

        fun get(context: Context): MarketRepository =
            instance ?: synchronized(this) {
                instance ?: MarketRepository(context.applicationContext).also { instance = it }
            }
    }

    /** 目录拉成功时记下的 base（faces/、previews/ 跟着同一个源走，别混用）。 */
    @Volatile
    private var activeBase: String = CATALOG_URLS.first().substringBeforeLast('/')

    // ---------------------------------------------------------------- 目录

    /** 拉目录清单：逐个源试，第一个成功的算数。全挂了抛最后一个错。 */
    @Throws(IOException::class)
    fun fetchCatalog(): MarketCatalog {
        var lastError: IOException? = null
        for (url in CATALOG_URLS) {
            try {
                val text = httpGet(url)
                val root = JSONObject(text)
                val faces = root.optJSONArray("faces")
                    ?: throw IOException("目录里没有 faces 字段")
                val entries = (0 until faces.length()).mapNotNull { i ->
                    // 单条坏了跳过，别让一张烂数据拖垮整个目录
                    try {
                        val o = faces.getJSONObject(i)
                        MarketEntry(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            author = o.getString("author"),
                            license = o.getString("license"),
                            file = o.getString("file"),
                            preview = o.getString("preview"),
                            sizeBytes = o.optInt("sizeBytes", 0),
                            crc32 = o.optString("crc32").toLongOrNull(16) ?: -1L,
                            note = o.optString("note").takeIf { it.isNotEmpty() },
                        )
                    } catch (e: JSONException) {
                        null
                    }
                }
                if (entries.isEmpty()) throw IOException("目录是空的")
                activeBase = url.substringBeforeLast('/')
                return MarketCatalog(updated = root.optString("updated"), faces = entries)
            } catch (e: IOException) {
                lastError = e
            } catch (e: Exception) {
                lastError = IOException(e.message ?: e.javaClass.simpleName)
            }
        }
        throw lastError ?: IOException("目录源全部不可达")
    }

    /**
     * 浏览用预览图：有缓存直接回，没有拉一次。失败返回 null，界面降级成无图卡片。
     */
    fun fetchPreviewCached(entry: MarketEntry): File? {
        val cached = File(previewCache, "${entry.id}.png")
        if (cached.isFile && cached.length() > 0) return cached
        return runCatching {
            previewCache.mkdirs()
            val bytes = httpGetBytes("$activeBase/${entry.preview}")
            cached.writeBytes(bytes)
            cached
        }.getOrNull()
    }

    fun peekPreviewCache(entry: MarketEntry): File? =
        File(previewCache, "${entry.id}.png").takeIf { it.isFile && it.length() > 0 }

    // ---------------------------------------------------------------- 下载

    /**
     * 下载一张表盘：包体走进度回调 → CRC32/大小校验（对不上就当没下过）→
     * 落到 filesDir/market/<id>/（face.bin + preview.png + meta.json）。
     * 重复下载 = 整目录重写。
     */
    @Throws(IOException::class)
    fun downloadFace(entry: MarketEntry, onProgress: (Int) -> Unit): StoredWatchFace {
        val bytes = try {
            httpGetBytes("$activeBase/${entry.file}") { percent -> onProgress(percent) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: e.javaClass.simpleName)
        }
        val crc = CRC32().apply { update(bytes) }.value
        if (entry.crc32 >= 0 && crc != entry.crc32) {
            throw IOException("校验和不符：目录说 %08x，下下来 %08x".format(entry.crc32, crc))
        }
        if (entry.sizeBytes > 0 && bytes.size != entry.sizeBytes) {
            throw IOException("大小不符：目录说 ${entry.sizeBytes} 字节，下下来 ${bytes.size}")
        }

        val faceDir = File(root, entry.id).apply { deleteRecursively(); mkdirs() }
        val binFile = File(faceDir, "face.bin").apply { writeBytes(bytes) }
        val previewFile = File(faceDir, "preview.png")
        runCatching { previewFile.writeBytes(httpGetBytes("$activeBase/${entry.preview}")) }
        File(faceDir, "meta.json").writeText(
            JSONObject()
                .put("id", entry.id)
                .put("name", entry.name)
                .put("author", entry.author)
                .put("license", entry.license)
                .put("sizeBytes", bytes.size)
                .put("note", entry.note ?: "")
                .toString(),
        )
        // 本体都下来了，浏览缓存那份就是冗余
        File(previewCache, "${entry.id}.png").delete()
        return StoredWatchFace(
            id = entry.id,
            name = entry.name,
            author = entry.author,
            license = entry.license,
            sizeBytes = bytes.size,
            note = entry.note,
            binFile = binFile,
            previewFile = previewFile,
        )
    }

    /** 已下载的表盘，按 meta.json 清点；缺文件或元数据的目录直接忽略。 */
    fun loadDownloaded(): List<StoredWatchFace> =
        root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val meta = File(dir, "meta.json")
                val bin = File(dir, "face.bin")
                if (!meta.isFile || !bin.isFile) return@mapNotNull null
                try {
                    val o = JSONObject(meta.readText())
                    StoredWatchFace(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        author = o.getString("author"),
                        license = o.getString("license"),
                        sizeBytes = o.optInt("sizeBytes", bin.length().toInt()),
                        note = o.optString("note").takeIf { it.isNotEmpty() },
                        binFile = bin,
                        previewFile = File(dir, "preview.png"),
                    )
                } catch (e: JSONException) {
                    null
                }
            }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    // ---------------------------------------------------------------- HTTP

    private fun httpGet(url: String): String = String(httpGetBytes(url))

    private fun httpGetBytes(url: String, onProgress: ((Int) -> Unit)? = null): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1
            val out = ByteArrayOutputStream(if (total > 0) total.toInt() else 64 * 1024)
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (onProgress != null && total > 0) {
                        onProgress((read * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}
