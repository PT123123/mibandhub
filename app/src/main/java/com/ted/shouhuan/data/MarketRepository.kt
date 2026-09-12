package com.ted.shouhuan.data

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 市场目录（market/index.json）里的一条。 */
data class MarketEntry(
    val id: String,
    val name: String,
    val author: String,
    val license: String,
    /**
     * 表盘包地址：仓库内的相对路径（faces/xx.bin，拼在 activeBase 后），
     * 或第三方源的绝对 URL（如 amazfitwatchfaces 的 .bin 直链）。
     */
    val file: String,
    val preview: String,
    val sizeBytes: Int,
    /** 十六进制串解析出来的 CRC32；目录没给就是 -1（跳过校验，改用 UIHH 魔数校验）。 */
    val crc32: Long,
    val note: String?,
    /** 详情页地址 —— 第三方源下载时用作 Referer，也方便用户溯源。 */
    val page: String?,
    /**
     * 在线条目的人气数据（"下载 126 · 收藏 1"），展示在卡片副标题上。
     * 快照目录的条目没有 —— 站点列表页才有这些数字。
     */
    val stats: String? = null,
    /**
     * 功能标签（[OnlineTag.param] 值域）。在线条目不填 —— 标签筛选走站点服务端；
     * 快照目录条目在解析时从 note 推导（回落模式下本地过滤用）。
     */
    val tags: Set<String> = emptySet(),
)

/** 热门榜的统计口径（站点的 sortby 参数；不传 = 按下载量排）。 */
enum class OnlineMetric(val label: String, val param: String?) {
    DOWNLOADS("下载量", null),
    VIEWS("浏览量", "views"),
    FAVORITES("收藏数", "fav"),
}

/** 浏览模式：站点的三条目录页。搜索词 / 功能标签激活时覆盖这里的取值。 */
enum class BrowseMode(val label: String) {
    FRESH("最新"),
    TOP("热门"),
    UPDATED("最近更新"),
}

/**
 * 功能标签筛选，对应站点筛选面板里那组可多选的 tag 按钮。
 *
 * 多选时逗号拼接进 `tags=` 查询参数，站点按交集（AND）过滤 —— 实测
 * tags=moon,aod（6 张 ∩ 1 张）返回 0 张，若为并集不可能为 0；任一模式
 * （/fresh /top /updated）都认这个参数，还能与 lang/paid 组合。
 * 词表从详情页 Tags 和面板按钮里挑的功能性标签（每个都实测有结果）；
 * 不收 PSG、metal gear solid 这类内容向标签。
 */
enum class OnlineTag(val label: String, val param: String) {
    DIGITAL("数字", "digital"),
    ANALOG("指针", "analog"),
    MINIMAL("极简", "minimal"),
    WEATHER("天气", "weather"),
    BATTERY("电量", "battery"),
    DATE("日期", "date"),
    WEEKDAY("星期", "weekday"),
    MONTH("月份", "month"),
    STEPS("步数", "steps"),
    HEART_RATE("心率", "heartrate"),
    CALORIES("卡路里", "calories"),
    DISTANCE("距离", "distance"),
    FLOORS("楼层", "floors"),
    SECONDS("秒针", "seconds"),
    BLUETOOTH("蓝牙", "bluetooth"),
    ALARM("闹钟", "alarm"),
    LOCK("锁屏", "lock"),
    DND("勿扰", "dnd"),
    MOON("月相", "moon"),
    AOD("息屏显示", "aod"),
    ANIMATED("动画", "animated"),
    RETRO("复古", "retro"),
}

/** 语言筛选（站点的 lang 参数，取值即站点筛选面板那张下拉表的值域）。单选。 */
enum class OnlineLang(val label: String, val param: String) {
    ANY("不限语言", ""),
    MULTILINGUAL("多语言", "multilingual"),
    ZH("中文", "zh"),
    EN("英文", "en"),
    JA("日文", "ja"),
    KO("韩文", "ko"),
    RU("俄文", "ru"),
    DE("德文", "de"),
    FR("法文", "fr"),
    ES("西班牙文", "es"),
    PT("葡萄牙文", "pt"),
    IT("意大利文", "it"),
    TR("土耳其文", "tr"),
}

/** 价格筛选（站点的 paid 参数，面板下拉里免费/付费的落点）。单选。 */
enum class OnlinePaid(val label: String, val param: String?) {
    ANY("免费/付费", null),
    FREE("免费", "0"),
    PAID("付费", "1"),
}

/**
 * 热门榜的时间窗口（站点的 topof 参数）。
 * 注意取值就这五个 —— 站点上没有 month，传了会拿到空列表（实测）。
 */
enum class OnlinePeriod(val label: String, val param: String) {
    WEEK("本周", "week"),
    MONTHS_3("3 个月", "3months"),
    HALF_YEAR("半年", "6months"),
    YEAR("一年", "year"),
    ALL_TIME("总榜", "alltime"),
}

/** 在线目录的一页。 */
data class OnlinePage(
    val entries: List<MarketEntry>,
    /** false = 这是最后一页，别再往下翻了。 */
    val hasMore: Boolean,
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

        /** amazfitwatchfaces 在线源：目录页、预览图、包体都从这个域出。 */
        private const val SITE_BASE = "https://amazfitwatchfaces.com"

        /**
         * 在线目录对准的设备（站点的 slug）。手环管家现在整条链路都是
         * Mi Band 5（认证、表盘槽、UIHH 包体），换设备时这里一起改。
         */
        private const val DEVICE_SLUG = "mi-band-5"

        /** 站点每页 16 张；解析满一页就当还有下一页，不满一页即到底。 */
        private const val ONLINE_PAGE_SIZE = 16
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000

        /**
         * 仓库内相对路径（faces/、previews/）按 GitHub 目录约定解析：
         * jsDelivr 用 gh/user/repo@ref/ 形式，raw 用 ref/ 形式，互不通用。
         */
        private val GITHUB_RAW_RE = Regex(
            """^https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/(?:refs/heads/)?(.+?)/([^/]+)$""",
        )

        /**
         * 目录/包体这类「小、要新鲜」的请求才禁缓存；预览图是静态资源，
         * 必须留着磁盘缓存和 30 天 max-age，否则每次进市场都白拉一遍。
         */
        private const val CACHE_BUST_HEADER = "Cache-Control"
        private const val CACHE_BUST_VALUE = "no-cache"

        @Volatile
        private var instance: MarketRepository? = null

        fun get(context: Context): MarketRepository =
            instance ?: synchronized(this) {
                instance ?: MarketRepository(context.applicationContext).also { instance = it }
            }
    }

    /** 目录拉成功时记下的 base 拼装函数（faces/、previews/ 跟着同一个源走，别混用）。 */
    @Volatile
    private var resolveRelative: (String) -> String = { rel ->
        "https://cdn.jsdelivr.net/gh/PT123123/mibandhub@main/$rel"
    }

    /**
     * 第三方站点反爬失败时回的是 HTML 说明页，不是 .bin —— 靠头部字节拦下来。
     * 实测 amazfitwatchfaces 的 Mi Band 5 包体是 `55 49 48 48`（"UIHH"）开头；
     * 仓库自制包体走自研容器，可能是明文头，也可能是 `ENCRYPTED` 头。
     */
    private val UIHH_MAGIC = "UIHH".toByteArray(Charsets.US_ASCII).toList()
    private val ENCRYPTED_MAGIC = "ENCRYPTED".toByteArray(Charsets.US_ASCII).toList()

    private fun startsWith(bytes: ByteArray, magic: List<Byte>): Boolean =
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }

    private fun looksLikePackage(bytes: ByteArray): Boolean =
        startsWith(bytes, UIHH_MAGIC) || startsWith(bytes, ENCRYPTED_MAGIC)

    /** 预览图解码后的内存缓存：省掉每次进市场都重新解码上百张图。 */
    private val previewMemory = HashMap<String, ByteArray>()
    private val previewMemoryLock = Any()

    /** 预览图落盘的后台任务：并发上限 4，不阻塞界面。 */
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetchGate = Semaphore(4)
    private val prefetchStarted = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 起一批后台预取：整页 149 张预览图，滑到哪张有哪张。 */
    fun startPreviewPrefetch(entries: List<MarketEntry>) {
        for (entry in entries) {
            if (previewMemory.containsKey(entry.id)) continue
            if (!prefetchStarted.add(entry.id)) continue
            prefetchScope.launch {
                prefetchGate.withPermit {
                    runCatching { fetchPreviewCached(entry) }
                }
            }
        }
    }

    private val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

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
                        val note = o.optString("note").takeIf { it.isNotEmpty() }
                        MarketEntry(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            author = o.getString("author"),
                            license = o.getString("license"),
                            file = o.getString("file"),
                            preview = o.getString("preview"),
                            sizeBytes = o.optInt("sizeBytes", 0),
                            crc32 = o.optString("crc32").toLongOrNull(16) ?: -1L,
                            note = note,
                            page = o.optString("page").takeIf { it.isNotEmpty() },
                            tags = tagsFromNote(note),
                        )
                    } catch (e: JSONException) {
                        null
                    }
                }
                if (entries.isEmpty()) throw IOException("目录是空的")
                resolveRelative = resolverFor(url)
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
     * 快照目录条目没有标签字段，从 note（make_market.py 写的组件说明，
     * 如「时间 / 日期 / 步数 / 电量」）推导 —— 回落模式下本地标签过滤用。
     * 站点快照条目（az*）的 note 没有组件词面，推导出来是空集。
     */
    private fun tagsFromNote(note: String?): Set<String> {
        if (note.isNullOrBlank()) return emptySet()
        val tags = LinkedHashSet<String>()
        for ((needle, tag) in NOTE_TAG_WORDS) {
            if (note.contains(needle)) tags += tag.param
        }
        return tags
    }

    private val NOTE_TAG_WORDS = listOf(
        "时间" to OnlineTag.DIGITAL,
        "日期" to OnlineTag.DATE,
        "星期" to OnlineTag.WEEKDAY,
        "步数" to OnlineTag.STEPS,
        "电量" to OnlineTag.BATTERY,
        "心率" to OnlineTag.HEART_RATE,
        "天气" to OnlineTag.WEATHER,
    )

    /**
     * 目录清单地址 → 「相对路径怎么拼」的解析函数。
     *
     * 这里必须按源分别构造，不能统一拼一个 base：jsDelivr 的路径带 `@main`，
     * raw 的路径没有 —— 早先统一按 jsDelivr 拼接，一旦回落到 raw 源，
     * 拼出来的地址就是 404，表现是目录能出、图全空。
     */
    private fun resolverFor(catalogUrl: String): (String) -> String {
        GITHUB_RAW_RE.find(catalogUrl)?.let { m ->
            val (user, repo, ref, _) = m.destructured
            return { rel -> "https://raw.githubusercontent.com/$user/$repo/$ref/$rel" }
        }
        val base = catalogUrl.substringBeforeLast('/')
        return { rel -> "$base/$rel" }
    }

    // ----------------------------------------------------------------
    // 在线源（amazfitwatchfaces.com）
    //
    // 站点没有公开 API，但目录页是服务端渲染的 HTML，卡片里预览图、作者、
    // 人气数字全在标记里 —— 抓下来正则解析即可（Notify for Mi Band 同款思路）。
    // 列表页实测 16 张/页，分页是路径式的 /p/N（搜索页是 ?page=N）。
    // ----------------------------------------------------------------

    /** 一张卡片的起点；两处起点之间恰好是一张卡的完整标记。 */
    private val CARD_ANCHOR = "class=\"panel wf-panel\""

    private val TITLE_RE = Regex("title=\"([^\"]+)\"")
    private val VIEW_LINK_RE = Regex("href=\"/([a-z0-9-]+)/view/(\\d+)\"")
    private val IMG_RE = Regex("src=\"([^\"]+)\"")
    private val ALT_RE = Regex("alt=\"([^\"]+)\"")
    private val AUTHOR_RE = Regex("/ucp/\\d+\"[^>]*>([^<]+)<")
    private val COMP_RE = Regex("<code>([^<]+)</code>")

    /** 卡片人气行：星=收藏、眼=浏览、下载图标=下载，各自跟着一个数字 span。 */
    private val COUNT_RE = Regex(
        "fa-(star|eye|download)\"></i>\\s*<span class=\"text-muted\">(\\d+)<",
    )

    /** 详情页里真正的包体直链（文件名带指纹，离线算不出来，只能现抓）。 */
    private val DL_LINK_RE = Regex("href=\"(/dl/[^\"]+\\.bin)\"")

    /**
     * 拉一页在线目录。
     *
     * 源站偶尔会回 200 + 空 body（Cloudflare 后面的限流行为，实测），
     * 所以这里带一次重试；空响应和「解析不到卡片」都按失败处理，
     * 交给调用方回落快照目录，绝不能让界面摆出一个莫名的空市场。
     *
     * @param mode 浏览模式：最新上传 / 热门榜 / 最近更新
     * @param query 非空 = 站内搜索，其余筛选参数全部忽略
     * @param tags 功能标签多选（空集 = 不过滤），逗号拼接进 tags= 参数
     * @param lang 语言过滤，ANY = 不传参；搜索模式下不生效
     * @param paid 免费/付费过滤；搜索模式下不生效
     * @param verifiedOnly 只看站点认证的表盘（verified=1）；搜索模式下不生效
     */
    @Throws(IOException::class)
    fun fetchOnlinePage(
        page: Int,
        mode: BrowseMode = BrowseMode.FRESH,
        metric: OnlineMetric = OnlineMetric.DOWNLOADS,
        period: OnlinePeriod = OnlinePeriod.ALL_TIME,
        query: String? = null,
        tags: Set<OnlineTag> = emptySet(),
        lang: OnlineLang = OnlineLang.ANY,
        paid: OnlinePaid = OnlinePaid.ANY,
        verifiedOnly: Boolean = false,
    ): OnlinePage {
        val url = onlineListUrl(page, mode, metric, period, query, tags, lang, paid, verifiedOnly)
        var lastError: IOException? = null
        repeat(2) { attempt ->
            if (attempt > 0) runCatching { Thread.sleep(1_200) }
            try {
                val html = httpGet(url)
                when {
                    html.isBlank() -> throw IOException("源站返回了空响应")
                    else -> {
                        val entries = parseListing(html)
                        // 「Nothing to display」是站点的合法空页（如本周没有新表盘）；
                        // 除此之外解析不到卡片就是被拦了，不能当空目录用
                        if (entries.isEmpty() && !html.contains("Nothing to display")) {
                            throw IOException("页面里解析不到表盘（多半被源站拦截）")
                        }
                        return OnlinePage(entries = entries, hasMore = entries.size >= ONLINE_PAGE_SIZE)
                    }
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("目录拉取失败")
    }

    /**
     * 组一页目录的地址。分页格式实测：目录 /p/N。
     * 功能标签 / 语言 / 价格 / 认证都是查询参数（站点面板提交的同一套），
     * 三条目录路径都认；多选标签按站点 JS 的做法逗号拼接（[OnlineTag] 注释）。
     */
    private fun onlineListUrl(
        page: Int,
        mode: BrowseMode,
        metric: OnlineMetric,
        period: OnlinePeriod,
        query: String?,
        tags: Set<OnlineTag>,
        lang: OnlineLang,
        paid: OnlinePaid,
        verifiedOnly: Boolean,
    ): String {
        if (!query.isNullOrBlank()) {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            return "$SITE_BASE/search/$DEVICE_SLUG/text/$q" + if (page > 1) "?page=$page" else ""
        }
        val params = mutableListOf<String>()
        if (tags.isNotEmpty()) params += "tags=" + tags.joinToString(",") { it.param }
        if (lang != OnlineLang.ANY) params += "lang=${lang.param}"
        paid.param?.let { params += "paid=$it" }
        if (verifiedOnly) params += "verified=1"
        if (mode == BrowseMode.TOP) {
            // top 页不带显式 topof 会返回空列表（站点默认窗口对着一个空的「本月」），
            // 所以这里永远把时间窗口带上
            metric.param?.let { params += "sortby=$it" }
            params += "topof=${period.param}"
        }
        val path = when (mode) {
            BrowseMode.FRESH -> "$SITE_BASE/$DEVICE_SLUG/fresh"
            BrowseMode.UPDATED -> "$SITE_BASE/$DEVICE_SLUG/updated"
            BrowseMode.TOP -> "$SITE_BASE/$DEVICE_SLUG/top"
        }
        val suffix = (if (page > 1) "/p/$page" else "") +
            (if (params.isEmpty()) "" else "?" + params.joinToString("&"))
        return path + suffix
    }

    /** 把目录页 HTML 拆成表盘条目；解析不出的卡片直接跳过，不拖累整页。 */
    private fun parseListing(html: String): List<MarketEntry> =
        html.split(CARD_ANCHOR).drop(1).mapNotNull(::parseCard)

    private fun parseCard(chunk: String): MarketEntry? {
        val view = VIEW_LINK_RE.find(chunk) ?: return null
        val device = view.groupValues[1]
        val numericId = view.groupValues[2]

        // 站点目录混着「表盘 App」（游戏、快捷方式）—— 那不是 .bin 表盘包，跳过
        val compatible = COMP_RE.find(chunk)?.groupValues?.get(1).orEmpty()
        if (compatible.contains("app", ignoreCase = true)) return null

        val name = (TITLE_RE.find(chunk)?.groupValues?.get(1) ?: ALT_RE.find(chunk)?.groupValues?.get(1))
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val img = IMG_RE.find(chunk)?.groupValues?.get(1) ?: return null
        val author = AUTHOR_RE.find(chunk)?.groupValues?.get(1)?.trim().orEmpty()

        val counts = COUNT_RE.findAll(chunk).associate { it.groupValues[1] to it.groupValues[2] }
        val stats = listOfNotNull(
            counts["download"]?.let { "下载 $it" },
            counts["star"]?.let { "收藏 $it" },
        ).joinToString(" · ").takeIf { it.isNotEmpty() }

        val detail = "$SITE_BASE/$device/view/$numericId"
        return MarketEntry(
            // 站内 id 是按设备编号的，加上 awf- 前缀和快照目录的 id 域分开
            id = "awf-$numericId",
            name = name,
            author = author,
            license = "",          // 目录页不带授权信息，详情页链接可溯源
            file = detail,         // 下载时从详情页现解析 .bin 直链（见 [resolveDirectBin]）
            preview = absoluteUrl(img),
            sizeBytes = 0,
            crc32 = -1L,
            note = null,
            page = detail,
            stats = stats,
        )
    }

    /** 站点标记里的地址三种形态都有：/storage/…、//域名/…、完整 https。 */
    private fun absoluteUrl(src: String): String = when {
        src.startsWith("//") -> "https:$src"
        src.startsWith("/") -> "$SITE_BASE$src"
        else -> src
    }

    /**
     * 在线条目的 file 字段存的是详情页；真正的 .bin 直链（/dl/<设备>/zip-bin/…）
     * 只能下载时抓详情页现解析。
     */
    private fun resolveDirectBin(url: String, entry: MarketEntry): String {
        if (!url.contains("/view/")) return url
        val html = httpGet(url, entry, bustCache = false)
        val href = DL_LINK_RE.find(html)?.groupValues?.get(1)
            ?: throw IOException("详情页里没找到下载直链（站点结构可能变了）")
        return absoluteUrl(href)
    }

    /**
     * 浏览用预览图字节：内存缓存 → 磁盘缓存 → 网络。
     * 失败返回 null，界面降级成无图卡片。
     *
     * 注意缓存是按 .bin 内容存的，不看扩展名 —— 源站给的多半是 GIF 动图
     * （149 张里 105 张），文件名一律 .png 只是历史习惯，BitmapFactory 认内容不认后缀。
     */
    fun fetchPreviewCached(entry: MarketEntry): ByteArray? {
        synchronized(previewMemoryLock) { previewMemory[entry.id] }?.let { return it }
        val cached = File(previewCache, "${entry.id}.png")
        if (cached.isFile && cached.length() > 0) {
            return runCatching { cached.readBytes() }.getOrNull()
        }
        return runCatching {
            previewCache.mkdirs()
            val url = downloadUrl(entry.preview, entry)
            // 站点图床和 .bin 一样认 UA，不带浏览器头可能拿回说明页
            val bytes = httpGetBytes(url, extraHeaders = httpHeadersFor(url, entry))
            if (bytes.isEmpty()) return@runCatching null
            cached.writeBytes(bytes)
            synchronized(previewMemoryLock) { previewMemory[entry.id] = bytes }
            bytes
        }.getOrNull()
    }


    // ---------------------------------------------------------------- 下载

    /**
     * 下载一张表盘：包体走进度回调 → CRC32/大小校验（对不上就当没下过）→
     * 落到 filesDir/market/<id>/（face.bin + preview.png + meta.json）。
     * 重复下载 = 整目录重写。
     */
    @Throws(IOException::class)
    fun downloadFace(entry: MarketEntry, onProgress: (Int) -> Unit): StoredWatchFace {
        // 源站对直连下载偶发「200 + 空响应」（见 fetchOnlinePage 的注释），
        // 空的和「不是包」的都重试一次再认输；报错带上实际收到的字节，
        // 不然界面上一句「下载到的不是表盘包」根本没法排查
        var bytes: ByteArray? = null
        var lastError: IOException? = null
        repeat(2) { attempt ->
            if (bytes != null) return@repeat
            if (attempt > 0) runCatching { Thread.sleep(1_200) }
            try {
                val direct = resolveDirectBin(downloadUrl(entry.file, entry), entry)
                val got = httpGetBytes(
                    direct,
                    onProgress = { percent -> onProgress(percent) },
                    extraHeaders = httpHeadersFor(entry.file, entry) + cacheBust(),
                )
                when {
                    got.isEmpty() -> lastError =
                        IOException("源站返回了空响应（源站限流或被拦截），稍后再试")
                    !looksLikePackage(got) -> lastError = IOException(
                        "下载到的不是表盘包：${got.size} 字节，开头 " +
                            got.take(8).joinToString(" ") { "%02x".format(it) } +
                            " —— 源站多半拦了请求，稍后重试或换一张",
                    )
                    else -> bytes = got
                }
            } catch (e: IOException) {
                lastError = e
            } catch (e: Exception) {
                lastError = IOException(e.message ?: e.javaClass.simpleName)
            }
        }
        val data = bytes ?: throw lastError ?: IOException("下载失败")
        val crc = CRC32().apply { update(data) }.value
        if (entry.crc32 >= 0 && crc != entry.crc32) {
            throw IOException("校验和不符：目录说 %08x，下下来 %08x".format(entry.crc32, crc))
        }
        if (entry.sizeBytes > 0 && data.size != entry.sizeBytes) {
            throw IOException("大小不符：目录说 ${entry.sizeBytes} 字节，下下来 ${data.size}")
        }

        val faceDir = File(root, entry.id).apply { deleteRecursively(); mkdirs() }
        val binFile = File(faceDir, "face.bin").apply { writeBytes(data) }
        val previewFile = File(faceDir, "preview.png")
        runCatching {
            val previewUrl = downloadUrl(entry.preview, entry)
            previewFile.writeBytes(
                httpGetBytes(previewUrl, extraHeaders = httpHeadersFor(previewUrl, entry)),
            )
        }
        File(faceDir, "meta.json").writeText(
            JSONObject()
                .put("id", entry.id)
                .put("name", entry.name)
                .put("author", entry.author)
                .put("license", entry.license)
                .put("sizeBytes", data.size)
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
            sizeBytes = data.size,
            note = entry.note,
            binFile = binFile,
            previewFile = previewFile,
        )
    }

    /** 按 id 找一张已下载的表盘；没下过返回 null（市场卡片「安装」用）。 */
    fun storedFace(id: String): StoredWatchFace? = loadDownloaded().firstOrNull { it.id == id }

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

    /**
     * 统一的 GET：按目标自动带反爬头（站点对 Android 默认的 Dalvik UA 直接回
     * 403 —— 必须伪装成浏览器，见 [httpHeadersFor]）。
     * 只保留这一个入口，别再开不带头的重载。
     */
    private fun httpGet(url: String, entry: MarketEntry? = null, bustCache: Boolean = true): String =
        String(
            httpGetBytes(
                url,
                null,
                httpHeadersFor(url, entry) + if (bustCache) cacheBust() else emptyMap(),
            ),
        )

    /**
     * 算下载地址：绝对 URL（第三方源）直接用；相对路径按当前目录源的规则拼
     * （见 [resolverFor]）。
     * amazfitwatchfaces 有反爬 —— 请求要带浏览器 UA 和详情页 Referer，
     * 否则拿回来的是 HTML 说明页（下载处有魔数校验兜底）。
     */
    private fun downloadUrl(url: String, entry: MarketEntry): String =
        if (url.startsWith("http")) url else resolveRelative(url)

    private fun httpHeadersFor(url: String, entry: MarketEntry?): Map<String, String> {
        if (!url.contains("amazfitwatchfaces.com")) return emptyMap()
        val headers = mutableMapOf("User-Agent" to BROWSER_UA)
        entry?.page?.let { headers["Referer"] = it }
        return headers
    }

    /**
     * 目录和 .bin 都走 CDN（jsDelivr 默认缓存数小时），推完新内容不刷就一直是旧的。
     * 预览图不加这个头 —— 它们是按 URL 命名的静态资源，让缓存正常生效。
     */
    private fun cacheBust(): Map<String, String> =
        mapOf(CACHE_BUST_HEADER to CACHE_BUST_VALUE)

    private fun httpGetBytes(
        url: String,
        onProgress: ((Int) -> Unit)? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            for ((k, v) in extraHeaders) setRequestProperty(k, v)
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            // amazfit 的 dl 端点是 chunked（没有 Content-Length），
            // 真实大小放在自定义头 aw-content-length 里 —— 进度条靠它
            val total = conn.contentLengthLong.takeIf { it > 0 }
                ?: conn.getHeaderField("aw-content-length")?.toLongOrNull()
                ?: -1L
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
