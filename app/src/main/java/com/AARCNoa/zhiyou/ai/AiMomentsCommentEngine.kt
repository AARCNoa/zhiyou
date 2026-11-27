package com.AARCNoa.zhiyou.ai

import android.util.Base64
import com.AARCNoa.zhiyou.features.ai.AiConfig
import com.AARCNoa.zhiyou.features.api.ui.WeMomentsApi
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.fs.KnownPaths
import com.AARCNoa.zhiyou.utils.image.ImageCompress
import java.io.File

/**
 * 朋友圈「帮我评论」：读这一条的文字（有图就带上图），结合对方档案里的标签与记忆，
 * 写三条不同风格的评论。
 *
 * 和聊天建议的差别：
 * - **不带工具**。朋友圈评论只需要看懂这一条，不需要翻聊天记录或查记忆详情；
 *   一次调用拿到三条就走，比多轮 agent 快得多。
 * - **图片先补缓存再问**。微信只在大图 / 缩略图真正显示过之后才写盘，信息流里没展开过的
 *   图往往还没落地。点「帮我评论」时由调用方给一个 [Post.ensureImages]，缺的图当场通知
 *   微信下载并等一小会儿（[IMAGE_WAIT_MS]）；仍拿不到就在提示词里说明「有图但看不到」，
 *   模型按文字写。不为了评论无限等 CDN —— 那是聊天识图也不做的事。
 * - **视频不解析**。视频文件无法直接喂给模型，只在提示词里说明这是视频，不要猜测画面内容。
 */
object AiMomentsCommentEngine {

    private const val TAG = "AiMomentsComment"

    /**
     * 一次最多带几张图：三张足够看懂一条动态，再多只是白付 token。
     *
     * 调用方（抓取媒体、补缓存）也用它，必须 internal —— 各写一份会悄悄漂。
     */
    internal const val MAX_IMAGES = 3

    /**
     * 补图片缓存的等待上限。
     *
     * 朋友圈图片走微信自己的 CDN，正常情况几百毫秒落地；这里给 6 秒是按「点一下就等」
     * 的体感上限定的 —— 再久用户已经开始怀疑点没点上。超时就按文字写，卡面照样出。
     * 调用方（[com.AARCNoa.zhiyou.features.items.moments.AiMomentsComment]）用同一个值，
     * 不各写一份。
     */
    internal const val IMAGE_WAIT_MS = 6_000L

    /** 档案注入上限：评论用不到全量，只取最近的这些。 */
    private const val MAX_TAGS = 24
    private const val MAX_MEMORIES = 40

    /** 思考内容在卡片里最多显示这么多字：再长就不是给人看的了。 */
    private const val MAX_THINKING_CHARS = 2000

    data class Post(
        val authorWxId: String?,
        /** 这条动态的正文，可能为空（纯图片 / 纯视频）。 */
        val text: String,
        /** 本地已缓存的图片路径（原图优先，其次缩略图）；视频的动态里可能是封面。 */
        val imagePaths: List<String>,
        /** 这条动态一共有几个媒体项（用来在没缓存时说清楚「有几张但看不到」）。 */
        val totalImages: Int,
        val isVideo: Boolean,
        /**
         * 点击时补缓存：缺的图通知微信下载并等一会儿，返回这次能用的路径（含原已缓存的）。
         *
         * 由调用方提供（它手里才有 proto / native 媒体对象）；为空表示不补 —— 提示词预览
         * 就是这样，它只要看文案，不该去读盘。
         */
        val ensureImages: (suspend () -> List<String>)? = null,
    )

    data class Comment(
        val replies: List<String> = emptyList(),
        val error: String? = null,
        /** 卡片标题用：备注 > 昵称 > wxid。 */
        val authorName: String = "好友",
        /** 实际带上的图片张数。 */
        val usedImages: Int = 0,
        /** 有图但模型没吃下（重试成文字），卡片上补一句说明。 */
        val imageSkipped: Boolean = false,
        /** 模型的思考内容；关掉思考或模型不返回时为空，卡片上不显示入口。 */
        val thinking: String = "",
        /** 对方档案规模：显示在名字后面，让用户知道这次参考了多少东西。 */
        val memoryCount: Int = 0,
        val tagCount: Int = 0,
    )

    /**
     * 生成三条评论。内部全是阻塞 IO（读档案 + 补图片 + 压缩 + HTTP），调用方放在 IO 线程。
     *
     * [onStage] 只在「要等图片」时被调用一次（非空）并在补齐后清空，供药丸显示当前在做什么；
     * 平时不发任何值，界面不必为此多做一层状态。
     */
    suspend fun generate(post: Post, onStage: suspend (String) -> Unit = {}): Comment {
        if (!AiConfig.isConfigured) return Comment(error = "未配置 AI 接口")

        val profile = post.authorWxId
            ?.takeIf { it.isNotBlank() }
            ?.let { wxId -> runCatching { AiProfileStore.readProfile(wxId) }.getOrNull() }
            ?.takeIf { it.state == AiProfileState.READY }
        val authorName = authorName(post.authorWxId, profile)
        val memoryCount = profile?.memories?.size ?: 0
        val tagCount = profile?.tags?.size ?: 0

        if (post.text.isBlank() && post.imagePaths.isEmpty() && post.totalImages == 0 && !post.isVideo) {
            return Comment(
                error = "这条朋友圈没有文字或图片",
                authorName = authorName,
                memoryCount = memoryCount,
                tagCount = tagCount,
            )
        }

        // 判定「要不要等图」看的是**编码出来的张数**，不是路径条数：
        // 路径存在但读不出来（0 字节占位、VFS 拷贝失败、格式解不了）时，光看路径会以为已经够了。
        val want = minOf(post.totalImages, MAX_IMAGES)
        var imageUrls = encodeAll(post.imagePaths)
        if (imageUrls.size < want && post.ensureImages != null) {
            onStage("读取图片")
            val after = fillImages(post, imageUrls.size)
            if (after.size > imageUrls.size) imageUrls = after
            onStage("")
        }
        val profileBlock = profileBlock(profile)
        val system = systemPrompt(hasProfile = profileBlock.isNotBlank())
        val user = userPrompt(post, authorName, profileBlock, imageUrls.size)

        val first = call(system, user, imageUrls)
        if (first.error == null && !first.text.isNullOrBlank()) {
            return parse(first.text.orEmpty()).copy(
                authorName = authorName,
                usedImages = imageUrls.size,
                // 有图但一张都没带上：置位后卡片会注明「图片未能识别」。
                imageSkipped = imageUrls.isEmpty() && post.totalImages > 0 && !post.isVideo,
                thinking = thinkingText(first.reasoning),
                memoryCount = memoryCount,
                tagCount = tagCount,
            )
        }
        // 带图失败就退回纯文字再试一次：最常见的原因是模型不支持图片输入，
        // 而这条动态的文字本身通常够写评论了。只重试一次，不掩盖真正的网络错误。
        if (imageUrls.isNotEmpty()) {
            WeLogger.w(TAG, "带图请求失败，改按文字重试：${first.error}")
            // user 要按这次实际附带的张数重建：沿用带图那版会让模型以为图还在，
            // 从而写出「照片里…」这类没看到画面的内容。
            val retry = call(system, userPrompt(post, authorName, profileBlock, attached = 0), emptyList())
            if (retry.error == null && !retry.text.isNullOrBlank()) {
                return parse(retry.text.orEmpty()).copy(
                    authorName = authorName,
                    usedImages = 0,
                    imageSkipped = true,
                    thinking = thinkingText(retry.reasoning),
                    memoryCount = memoryCount,
                    tagCount = tagCount,
                )
            }
        }
        val hint = if (imageUrls.isNotEmpty()) "（本次附带了图片，可能是模型不支持图片输入）" else ""
        return Comment(
            error = (first.error ?: "模型返回为空") + hint,
            authorName = authorName,
            memoryCount = memoryCount,
            tagCount = tagCount,
        )
    }

    // ── 请求 ────────────────────────────────────────────────────────────────

    /**
     * 这次要附带的图片路径。
     *
     * 已经缓存够张数就直接用（不触发下载，点下去立刻发请求）；不够才走 [Post.ensureImages]
     * 补一次。补回来的路径与已缓存的是同一份口径（原图优先、其次缩略图），所以补到几张就
     * 用几张 —— 不必等齐全部，纯图动态里 3 张有 1 张也够模型抓住画面在说什么。
     */
    private fun encodeAll(paths: List<String>): List<String> =
        paths.take(MAX_IMAGES).mapNotNull { path -> runCatching { dataUrl(path) }.getOrNull() }

    /**
     * 补图片缓存后重新编码。
     *
     * 补回来的可能是**同一条路径**：原图下载完还是 `snsb_xxx` 这个名字，之前读到的是
     * 0 字节占位。所以不能只编码新路径 —— 旧路径要一起再编一次，否则「已经落地了」
     * 却仍然当作读不到。
     */
    private suspend fun fillImages(post: Post, have: Int): List<String> {
        val started = System.currentTimeMillis()
        val filled = runCatching { post.ensureImages!!.invoke() }.getOrElse {
            WeLogger.w(TAG, "补图片缓存失败：${it.message}")
            emptyList()
        }
        val merged = (post.imagePaths + filled).distinct().take(MAX_IMAGES)
        val encoded = encodeAll(merged)
        WeLogger.i(
            TAG,
            "图片：路径 ${post.imagePaths.size} 张 → 补后 ${merged.size} 张，" +
                "编码成功 $have → ${encoded.size} 张（这条共 ${post.totalImages} 张），" +
                "耗时 ${System.currentTimeMillis() - started}ms",
        )
        return encoded
    }

    private fun call(system: String, user: String, images: List<String>): AiConfig.ChatResult =
        runCatching {
            AiConfig.chatWithTools(
                messages = listOf(
                    AiConfig.ChatMessage(role = "system", content = system),
                    AiConfig.ChatMessage(role = "user", content = user, images = images),
                ),
                // 评论是「说人话」的场景，用设置页的说话温度；不带工具。
                temperature = AiConfig.temperature,
                // 带图时首包更慢，给 90 秒；普通文字一般几秒就回。
                readTimeoutMs = 90_000,
            )
        }.getOrElse {
            WeLogger.e(TAG, "评论请求异常", it)
            AiConfig.ChatResult(text = null, error = it.message ?: it.javaClass.simpleName)
        }

    /**
     * 调试页预览：与实际请求同一套 system / user 拼装。
     *
     * 没有可用档案时用一份带标签与记忆的示例，否则「有档案才追加」的那一段看不到。
     * 图片按「已经补到」的正常情况渲染（示例 2 张）：读不到图只是兜底分支，
     * 预览常驻那一句会让人以为功能坏了。返回值是 (system, user)，不带真正的图片数据。
     */
    internal fun preview(profile: AiProfile?): Pair<String, String> {
        val p = profile ?: PREVIEW_PROFILE
        val post = Post(
            authorWxId = p.wxId,
            text = "周六下午在老地方聚一下，有人带相机吗？",
            imagePaths = emptyList(),
            // 示例给 2 张，按「补到了」渲染：与真实请求的常态一致。
            totalImages = 2,
            isVideo = false,
        )
        val block = profileBlock(p)
        val name = authorName(post.authorWxId, p)
        return systemPrompt(hasProfile = block.isNotBlank()) to userPrompt(post, name, block, attached = 2)
    }

    // ── 提示词 ──────────────────────────────────────────────────────────────

    private fun systemPrompt(hasProfile: Boolean): String = buildString {
        appendLine("你在刷微信朋友圈，要给好友发的这条动态写评论。")
        appendLine("写三条，风格必须不同：")
        appendLine("1. 熟人打趣：顺着内容接一句玩笑或调侃，有具体的点，不空泛。")
        appendLine("2. 真心回应：说出你看到这条时真实的感受、共鸣或认可。")
        appendLine("3. 顺着聊：接住里面一个细节说下去，或自然地问一句。不要问「在哪」「多少钱」这种查户口式的问题。")
        appendLine()
        appendLine("要求：")
        appendLine("- 每条 3~25 个字，一条就是一句话；不加引号、不编号、不换行。")
        appendLine("- 不复述、不总结动态在说什么，不写「点赞」「羡慕」「真棒」「有道理」这类客套词。")
        appendLine("- 不编造没看到的内容：视频里讲了什么、没提到的地点和人名都不要写。")
        appendLine("- 三条之间不要用同一种句式，也不要同一个开头。")
        if (hasProfile) {
            appendLine("- 你对这位好友有了解（材料在下面）。评论要贴着你们的关系和你知道的事，")
            appendLine("  但不要把档案里的信息直接说出来，也不要提「标签」「档案」这些词。")
        }
        // 输出格式不在这里：放在 user 末尾（见 [userPrompt]），那是模型动笔前最后读到的位置。
    }

    private fun userPrompt(post: Post, authorName: String, profileBlock: String, attached: Int): String =
        buildString {
            appendLine("这条朋友圈（作者：$authorName）：")
            appendLine(post.text.ifBlank { "（没有文字）" })
            appendLine()
            when {
                attached > 0 && post.isVideo -> appendLine("附带了这条视频的封面图（画面内容仍然看不到）。")
                attached > 0 && post.totalImages > attached ->
                    appendLine("附带了这条的 $attached 张图片（这条共 ${post.totalImages} 张，按发送顺序取了前 $attached 张）。")
                attached > 0 -> appendLine("附带了这条的 $attached 张图片，按发送顺序排列。")
                // 「没有路径」与「有路径但读不出来」分开说明：后者是本地占位文件或格式
                // 解不了，不能写成「没缓存」，否则会与开关状态混淆。
                post.totalImages > 0 && post.imagePaths.isNotEmpty() ->
                    appendLine("这条有 ${post.totalImages} 张图片，但这次没能一起发给模型，看不到内容，只能按文字写。")
                post.totalImages > 0 ->
                    appendLine("这条有 ${post.totalImages} 张图片，但本地还没缓存，看不到图片内容，只能按文字写。")
                post.isVideo -> appendLine("这条是视频，看不到视频内容，不要猜测画面里有什么。")
            }
            if (profileBlock.isNotBlank()) {
                appendLine()
                append(profileBlock)
            }
            // 输出格式放在 user 末尾：这是模型动笔前最后读到的位置（与智能推荐同一策略）。
            appendLine()
            appendLine("输出格式（严格遵守）：只输出一行 JSON，这就是你回复的全部内容。")
            appendLine("第一个字符是 {，最后一个字符是 }，所有括号都要闭合；} 之后不再有任何字符，")
            append("不要代码块、不要解释。字段只有 replies，里面是三条：{\"replies\":[\"…\",\"…\",\"…\"]}")
        }

    /**
     * 档案只注入「标签 + 事件标题」：评论是随手一评，不需要事件详情，
     * 也不该为了它背上几千 token。没有可用内容时整段省略，不写「（暂无）」占位。
     */
    private fun profileBlock(profile: AiProfile?): String {
        if (profile == null) return ""
        val tags = profile.tags.take(MAX_TAGS)
        // 档案里的记忆是「关键事件在前，其余按日期倒序」落盘的，直接 takeLast 拿到的是
        // **最旧**的一批（半年以前的旧事）。按统一的时间正序切窗再取末尾，才是最近的这些。
        val memories = AiMemoryView.of(profile).recent.map { it.second }.takeLast(MAX_MEMORIES)
        if (tags.isEmpty() && memories.isEmpty()) return ""
        return buildString {
            appendLine("你对这位好友的了解（只用来让评论更贴，不要直接说出来）：")
            if (tags.isNotEmpty()) {
                appendLine("标签：" + tags.joinToString("；") { "${it.key}=${it.value}" })
            }
            if (memories.isNotEmpty()) {
                appendLine("最近记得的事：")
                memories.forEach { appendLine("- ${it.occurredAt} ${it.title}") }
            }
        }
    }

    private fun authorName(wxId: String?, profile: AiProfile?): String {
        profile?.let { p ->
            p.remark.ifBlank { p.nickname }.takeIf { it.isNotBlank() }?.let { return it }
        }
        if (wxId.isNullOrBlank()) return "好友"
        return runCatching { AiFriends.load().firstOrNull { it.wxId == wxId }?.displayName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: wxId
    }

    /** 思考内容统一裁剪：卡片里放不下整段思考，也不该让一段思考撑爆浮层。 */
    private fun thinkingText(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return if (text.length > MAX_THINKING_CHARS) {
            text.take(MAX_THINKING_CHARS) + "\n…（共 ${text.length} 字，已省略后半）"
        } else {
            text
        }
    }

    // ── 图片 ────────────────────────────────────────────────────────────────

    /**
     * 读本地缓存图并压成 data URL。
     *
     * 朋友圈的图片路径有两类：普通文件（截图、下载）与微信 VFS（`/data/data/.../sns/`）。
     * 普通文件直接读；读不到再用 [WeMomentsApi.copyExistingFile] 拷到临时文件 —— 与转发功能
     * 用的是同一条路径，不为这里另造一套。
     *
     * 压缩参数交给 [ImageCompress] 的默认值（800px / 70%），与聊天识图同一份。
     */
    private fun dataUrl(path: String): String? {
        val raw = readBytes(path) ?: return null
        val jpeg = ImageCompress.jpeg(raw) ?: run {
            WeLogger.w(TAG, "图片解码失败（${raw.size} 字节）：$path")
            return null
        }
        return "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
    }

    private fun readBytes(path: String): ByteArray? {
        val directError = runCatching { File(path).readBytes() }
        directError.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val temp = File(KnownPaths.moduleCache.toFile(), "moments_ai_${System.nanoTime()}.img")
        return runCatching {
            if (!WeMomentsApi.copyExistingFile(path, temp.absolutePath)) {
                // 返回 false 不抛异常：这条日志是「读不到」最主要的现场，别让它静默。
                WeLogger.w(
                    TAG,
                    "图片拷贝失败（直接读${if (directError.isSuccess) "得到空文件" else "抛异常"}）：$path",
                )
                return@runCatching null
            }
            temp.readBytes().takeIf {
                if (it.isEmpty()) WeLogger.w(TAG, "图片拷贝后仍是 0 字节：$path")
                it.isNotEmpty()
            }
        }.getOrElse {
            WeLogger.w(TAG, "读取朋友圈图片失败：$path", it)
            null
        }.also {
            runCatching { temp.delete() }
        }
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    /**
     * 解析 `{"replies":["…","…","…"]}`。
     *
     * 容错统一走 [AiJson]（原文 → 剥围栏 → 首尾花括号，大小写围栏都认）；
     * 全都不成 JSON 就按行拆三条 —— 模型往往只是没包 JSON，内容本身可用。
     */
    private fun parse(raw: String): Comment {
        val replies = AiJson.firstObject(raw)?.let { AiJson.strings(it, "replies") }.orEmpty()
        if (replies.isNotEmpty()) return Comment(replies = replies.take(3))

        val lines = AiJson.stripFence(raw)
            .lines()
            .map { it.trim().removePrefix("-").removePrefix("·").trim() }
            .filter { it.isNotEmpty() }
            .take(3)
        return if (lines.isNotEmpty()) {
            Comment(replies = lines)
        } else {
            Comment(error = "模型返回为空")
        }
    }

    /** 预览专用：没有档案时用它，保证「档案注入」那段也能看到。 */
    private val PREVIEW_PROFILE = AiProfile(
        wxId = "wxid_preview",
        nickname = "示例好友",
        tags = listOf(
            ProfileTag(category = "兴趣", key = "摄影", value = "周末常出去拍照", evidence = "说新买了个镜头"),
            ProfileTag(category = "关系", key = "同事", value = "同一个项目组", evidence = "项目群里聊过"),
        ),
        memories = listOf(
            MemoryEntry(id = "preview-1", occurredAt = "2026-11-20", title = "约了周四看展", detail = "", important = true),
            MemoryEntry(id = "preview-2", occurredAt = "2026-11-18", title = "在考虑换工作", detail = ""),
        ),
    )
}
