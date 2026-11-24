package com.AARCNoa.zhiyou.features.items.moments

import com.AARCNoa.zhiyou.ai.AiMomentsCommentEngine
import com.AARCNoa.zhiyou.features.ai.AiConfig
import com.AARCNoa.zhiyou.features.api.net.models.protobuf.TimelineObjectProto
import com.AARCNoa.zhiyou.features.api.ui.WeMomentsApi
import com.AARCNoa.zhiyou.features.api.ui.WeMomentsContextMenuApi
import com.AARCNoa.zhiyou.features.core.ApiFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.ui.utils.EditIcon
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 朋友圈「帮我评论」。
 *
 * 长按朋友圈文字 → 菜单里多一项「帮我评论」→ 读这一条的文字与已缓存的图片，
 * 对方有 AI 档案就把标签与记忆一起带上，让模型写三条不同风格的评论。
 *
 * 只在配置了 AI 接口、且开关开着时出现：没配置的菜单里不出现，比点了再提示「未配置」干净。
 *
 * **开关不在功能清单里**（`ApiFeature` 不建卡），在 AI 页的「设置」段 ——
 * AI 相关能力统一放那里，不散到朋友圈分类。
 */
@Feature(
    name = "朋友圈帮我评论",
    categories = ["朋友圈"],
    description = "长按朋友圈文字，AI 读这条内容和好友档案，写三条不同风格的评论",
)
object AiMomentsComment : ApiFeature(), WeMomentsContextMenuApi.IMenuItemsProvider {

    private const val TAG = "AiMomentsComment"

    /** 朋友圈菜单 id 段：777006/777009/777013/777014/777017 已被占用。 */
    private const val MENU_ID = 777020

    /** 上限与等待时长都取引擎那一份，不在这里另定义。 */
    private val MAX_IMAGES = AiMomentsCommentEngine.MAX_IMAGES
    private val IMAGE_WAIT_MS = AiMomentsCommentEngine.IMAGE_WAIT_MS

    override fun onEnable() {
        sync()
    }

    override fun onDisable() {
        WeMomentsContextMenuApi.removeProvider(this)
        registered = false
    }

    /**
     * 按开关状态把 provider 装进 / 移出菜单注册表。
     *
     * 设置页改完开关要立刻调一次。只靠 `shouldShow` 里读配置不够：菜单上下文解析失败时
     * 那条路径不判断可见性（见 `WeMomentsContextMenuApi.handleCreateMenu`），
     * 关掉开关后菜单项仍会出现。让 provider 整个不在注册表里，这层就不会漏。
     */
    @Synchronized
    fun sync() {
        val on = AiConfig.isMomentsCommentEnabled
        if (on == registered) return
        if (on) {
            WeMomentsContextMenuApi.addProvider(this)
        } else {
            WeMomentsContextMenuApi.removeProvider(this)
        }
        registered = on
    }

    /** 当前 provider 是否已装进注册表；只在 [sync] / [onEnable] / [onDisable] 里改。 */
    @Volatile
    private var registered = false

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> = listOf(
        WeMomentsContextMenuApi.MenuItem(
            MENU_ID,
            "帮我评论",
            EditIcon,
            // 不要求 snsInfo 非空：新版信息流（ImproveSnsTimelineUI）的监听器里拿不到它，
            // 要求非空会让菜单项整片消失。内容在点击时才解析，解析不到就提示进详情页。
            { _, _ -> AiConfig.isConfigured && AiConfig.isMomentsCommentEnabled },
        ) { moment ->
            runCatching { open(moment) }.onFailure {
                WeLogger.e(TAG, "帮我评论打开失败", it)
                showToast(moment.activity, "帮我评论打开失败，详见日志")
            }
        }
    )

    private fun open(moment: WeMomentsContextMenuApi.MomentsContext) {
        val activity = moment.activity
        val snsInfo = moment.snsInfo
        if (snsInfo == null) {
            // 信息流页面长按时可能读不到这一条（监听器里没有 localId / TimeLineObject）。
            // 详情页的上下文更完整，直接引导过去，比一个「解析失败」有用。
            showToast(activity, "这条朋友圈读不到内容，点进详情页再长按文字")
            return
        }

        val proto = WeMomentsApi.getTimelineProto(snsInfo)
        val text = (proto?.contentDesc ?: WeMomentsApi.getContentText(snsInfo)).orEmpty().trim()
        val media = proto?.contentObj?.mediaList.orEmpty()
        val type = proto?.contentObj?.type ?: 0
        val isVideo = type == 15 || type == 5
        if (text.isEmpty() && media.isEmpty()) {
            showToast(activity, "这条朋友圈没有可评论的内容")
            return
        }

        // 图片先看已缓存的；缺的在生成前由引擎回调 [ensureImages] 补一次（见下）。
        val native = WeMomentsApi.getNativeTimeline(snsInfo)
        val nativeList = native?.let { runCatching { WeMomentsApi.getNativeMediaList(it) }.getOrNull() }
        val cached = cachedImagePaths(media, nativeList)
        // 图片这一步经常「看起来没生效」：一行把三个数打出来，区分是没原生对象、
        // 还是原生对象里没媒体、还是本地根本没缓存 —— 三种情况的现象完全一样。
        WeLogger.i(
            TAG,
            "open: 文字=${text.length}字 media=${media.size} native=${native != null} " +
                "nativeMedia=${nativeList?.size} 已缓存=${cached.size} video=$isVideo",
        )
        val post = AiMomentsCommentEngine.Post(
            authorWxId = proto?.userName ?: WeMomentsApi.getOwnerWxId(snsInfo),
            text = text,
            imagePaths = cached,
            totalImages = media.size,
            isVideo = isVideo,
            // 只有拿得到原生媒体对象才谈得上补缓存：触发下载要的正是它。
            ensureImages = if (nativeList.isNullOrEmpty()) null else {
                { ensureImages(media, nativeList) }
            },
        )
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            AiMomentsCommentOverlay.show(activity, post)
        }
    }

    /**
     * 取前 [MAX_IMAGES] 张已缓存的图（原图优先，其次缩略图）。
     *
     * 只查盘、不触发下载：这一步决定「点下去要不要等」——已经缓存够就不等，直接发请求。
     * 不够的交给 [ensureImages]，那边才会通知微信下载。
     */
    private fun cachedImagePaths(
        media: List<TimelineObjectProto.MediaObjProto>,
        nativeList: List<*>?,
    ): List<String> {
        if (media.isEmpty() || nativeList.isNullOrEmpty()) return emptyList()
        val out = ArrayList<String>(MAX_IMAGES)
        for (index in media.indices) {
            if (out.size >= MAX_IMAGES) break
            val nativeMedia = nativeList.getOrNull(index) ?: continue
            val path = runCatching {
                WeMomentsApi.getCachedImagePath(media[index], nativeMedia)
            }.getOrNull() ?: continue
            out += path
        }
        return out
    }

    /**
     * 补图片缓存：缺的图通知微信下载，并在 [IMAGE_WAIT_MS] 内并行等落地。
     *
     * 为什么可以等：这条动态就摊在屏幕上，微信本来也在为它取图，我们只是提前把它点开；
     * 6 秒内拿到的通常是原图。超时也不报错 —— 拿几张算几张，一张都没有就按文字写。
     * 全程在 IO 线程（引擎在 `withContext(Dispatchers.IO)` 里调用）。
     */
    private suspend fun ensureImages(
        media: List<TimelineObjectProto.MediaObjProto>,
        nativeList: List<*>,
    ): List<String> = coroutineScope {
        val count = minOf(media.size, MAX_IMAGES)
        if (count == 0) return@coroutineScope emptyList()
        val waited = withTimeoutOrNull(IMAGE_WAIT_MS + 1_500) {
            (0 until count).map { index ->
                async(Dispatchers.IO) {
                    val nativeMedia = nativeList.getOrNull(index) ?: return@async null
                    runCatching {
                        WeMomentsApi.ensureImageCached(media[index], nativeMedia, timeoutMs = IMAGE_WAIT_MS)
                    }.getOrNull()
                }
            }.awaitAll()
        }
        if (waited == null) WeLogger.w(TAG, "补图片缓存超时（${IMAGE_WAIT_MS}ms），按已拿到的写")
        waited.orEmpty().filterNotNull()
    }
}
