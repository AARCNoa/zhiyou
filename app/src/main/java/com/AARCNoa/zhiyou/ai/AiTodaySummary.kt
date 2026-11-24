package com.AARCNoa.zhiyou.ai

import com.AARCNoa.zhiyou.BuildConfig
import com.AARCNoa.zhiyou.ai.group.GroupDigestStore
import com.AARCNoa.zhiyou.features.ai.AiConfig
import com.AARCNoa.zhiyou.utils.WeLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.div

/**
 * 总览顶部的「今日总结」。
 *
 * 取当天已经落在盘上的记录：
 * - 好友：自动回复通过 `report_important_event` 记下的约定；
 * - 群聊：当天每一条摘要，以及工作群当前任务与待办。
 *
 * 只做一次汇总请求，不重新读聊天记录 —— 这些内容本来就是为了看板准备的，
 * 再扫一遍数据库既慢又会和群总结的窗口口径打架。
 *
 * 三类结果：
 * - [SOURCE_AI]：模型给出的要点与待办，按天 + 输入指纹缓存，内容没变不重复请求；
 * - [SOURCE_LOCAL]：AI 未配置时的本地拼装（统计 + 最近几条），只保证有东西可看；
 * - null：今天什么都没有，面板不画这张卡。
 */
@Serializable
data class TodaySummary(
    /** 本地日期 `yyyy-MM-dd`；跨天即失效。 */
    val day: String = "",
    val at: Long = 0,
    /** 输入指纹：记录有增减就变，用来判断缓存的总结是否还对得上。 */
    val signature: String = "",
    val headline: String = "",
    val points: List<String> = emptyList(),
    val todos: List<String> = emptyList(),
    val friendCount: Int = 0,
    val eventCount: Int = 0,
    val groupCount: Int = 0,
    val digestCount: Int = 0,
    val source: String = AiTodaySummary.SOURCE_LOCAL,
)

object AiTodaySummary {

    const val SOURCE_AI = "ai"
    const val SOURCE_LOCAL = "local"

    private const val TAG = "AiTodaySummary"
    private const val TEMPERATURE = 0.3
    private const val REQUEST_TIMEOUT_MS = 90_000

    private const val HEADLINE_MAX = 24
    private const val POINT_MAX = 44
    private const val MAX_POINTS = 4
    private const val MAX_TODOS = 3

    /** 失败后 10 分钟内不再重试：面板会被反复打开，每次都打一次失败请求没有意义。 */
    private const val RETRY_MS = 10 * 60_000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = BuildConfig.DEBUG
    }

    private val file: Path get() = AiProfileStore.root / "today.json"

    /** 最后一次请求失败的时刻；只在失败时写，成功不受它影响。 */
    @Volatile
    private var lastFailureAt = 0L

    /**
     * 只读当天已存的总结，不取数、不发请求。
     *
     * 面板用它区分「今天第一次整理」与「已经整理过」：前者展开给用户看结果，后者收起。
     * 读盘，调用方要放在 IO 线程。
     */
    fun cached(nowMs: Long = System.currentTimeMillis()): TodaySummary? =
        read()?.takeIf { it.day == AiDates.dayKey(nowMs) }

    /**
     * 拿今天的总结。没有内容时返回 null。
     *
     * `force = true` 是用户点「重新整理」，绕过缓存与失败节流。
     * 阻塞调用（可能发一次请求），调用方必须放在 IO 线程。
     */
    @Synchronized
    fun ensure(nowMs: Long = System.currentTimeMillis(), force: Boolean = false): TodaySummary? {
        val input = collect(nowMs)
        if (input.lines.isEmpty()) return null

        val today = AiDates.dayKey(nowMs)
        val fresh = read()?.takeIf { it.day == today }

        // 没配接口就别假装能总结：本地拼一份，内容变了才重写文件。
        if (!AiConfig.isConfigured) {
            if (!force && fresh != null &&
                fresh.source == SOURCE_LOCAL && fresh.signature == input.signature
            ) {
                return fresh
            }
            return local(input, nowMs).also { write(it) }
        }

        if (!force && fresh != null &&
            fresh.source == SOURCE_AI && fresh.signature == input.signature
        ) {
            return fresh
        }
        // 与好友档案抢同一条网络会 429：有缓存先给缓存，没有就给本地拼装。
        if (AiRunState.hasLongTask()) return fresh ?: local(input, nowMs)
        if (!force && System.currentTimeMillis() - lastFailureAt < RETRY_MS) {
            return fresh ?: local(input, nowMs)
        }

        val parsed = request(input)
        if (parsed == null) {
            lastFailureAt = System.currentTimeMillis()
            return fresh ?: local(input, nowMs)
        }
        val summary = TodaySummary(
            day = today,
            at = nowMs,
            signature = input.signature,
            headline = parsed.headline.ifBlank { localHeadline(input) },
            points = parsed.points,
            todos = parsed.todos,
            friendCount = input.friendCount,
            eventCount = input.eventCount,
            groupCount = input.groupCount,
            digestCount = input.digestCount,
            source = SOURCE_AI,
        )
        write(summary)
        return summary
    }

    // ── 取数 ────────────────────────────────────────────────────────────────

    private data class Input(
        val day: String,
        val lines: List<String>,
        val signature: String,
        val friendCount: Int,
        val eventCount: Int,
        val groupCount: Int,
        val digestCount: Int,
    )

    /**
     * 把今天的记录摊平成几行文本。
     *
     * 好友用备注优先、群用群名：模型输出里要能指代「是谁」，但不需要知道 wxid。
     * 同一段待办会在多个窗口里重复出现，这里按文本去重一次。
     */
    private fun collect(nowMs: Long): Input {
        val today = AiDates.dayKey(nowMs)
        val lines = mutableListOf<String>()
        var friendCount = 0
        var eventCount = 0
        var groupCount = 0
        var digestCount = 0

        runCatching {
            val names = AiProfileStore.loadIndex().profiles.associate {
                it.wxId to it.displayNameOrId
            }
            names.forEach { (wxId, name) ->
                val events = AiOverviewStore.todayEventsOf(wxId, nowMs)
                if (events.isEmpty()) return@forEach
                friendCount++
                eventCount += events.size
                events.forEach { event ->
                    lines += "好友 $name · ${clock(event.at)} · ${event.title}"
                }
            }
        }.onFailure { log("读取好友事件失败：${it.javaClass.simpleName}") }

        runCatching {
            val seenTodos = HashSet<String>()
            GroupDigestStore.loadIndex().groups.forEach { summary ->
                val config = GroupDigestStore.readConfig(summary.groupId) ?: return@forEach
                val name = config.name.ifBlank { config.groupId }
                val digests = config.digests.filter { AiDates.dayKey(it.createdAt) == today }
                var counted = false
                if (digests.isNotEmpty()) {
                    counted = true
                    digestCount += digests.size
                    digests.forEach { digest ->
                        lines += "群 $name · ${clock(digest.createdAt)} · ${digest.headline}"
                    }
                    digests.forEach { digest ->
                        digest.todos.forEach { todo ->
                            if (seenTodos.add(todo)) {
                                lines += "群 $name · 待办 · $todo"
                            }
                        }
                    }
                }
                if (config.workGroup && config.currentTask.isNotBlank()) {
                    counted = true
                    val percent = if (config.currentProgress in 0..100) " ${config.currentProgress}%" else ""
                    lines += "群 $name · 任务 · ${config.currentTask}$percent"
                }
                if (counted) groupCount++
            }
        }.onFailure { log("读取群摘要失败：${it.javaClass.simpleName}") }

        // 指纹用整段文本算：条数相同但换了内容（比如进度 60% → 80%）也要重算。
        val signature = lines.joinToString("\n").hashCode().toString(16)
        return Input(
            day = today,
            lines = lines,
            signature = signature,
            friendCount = friendCount,
            eventCount = eventCount,
            groupCount = groupCount,
            digestCount = digestCount,
        )
    }

    // ── 生成 ────────────────────────────────────────────────────────────────

    private data class Parsed(
        val headline: String,
        val points: List<String>,
        val todos: List<String>,
    )

    private fun request(input: Input): Parsed? {
        val user = userMessage(input.day, input.lines)
        val result = AiConfig.chatWithTools(
            messages = listOf(
                AiConfig.ChatMessage(role = "system", content = SYSTEM),
                AiConfig.ChatMessage(role = "user", content = user),
            ),
            temperature = TEMPERATURE,
            readTimeoutMs = REQUEST_TIMEOUT_MS,
            // 后台任务：成功与失败都不改首页的「AI 已激活」状态。
            affectStatus = false,
        )
        val text = result.text
        if (text.isNullOrBlank()) {
            log("请求失败：${result.error ?: "空响应"}")
            return null
        }
        if (result.finishReason == "length") log("输出被截断（finish_reason=length）")
        return parse(text) ?: Parsed(
            headline = fallbackHeadline(text),
            points = emptyList(),
            todos = emptyList(),
        )
    }

    /** user 的唯一拼装入口：真实请求与 debug 预览必须走同一份，否则预览会与实际不一致。 */
    private fun userMessage(day: String, lines: List<String>): String = buildString {
        append("日期：").append(day).append('\n')
        append("<今日记录>\n")
        lines.forEach { append("- ").append(it).append('\n') }
        append("</今日记录>\n\n")
        append(OUTPUT_RULE)
    }

    /** 调试页预览：system 原文。 */
    internal fun previewSystem(): String = SYSTEM

    /** 调试页预览：用与真实请求同一套拼装的 user，只把记录换成示例。 */
    internal fun previewUser(
        nowMs: Long = System.currentTimeMillis(),
        friendName: String = "示例好友",
    ): String = userMessage(
        AiDates.dayKey(nowMs),
        listOf(
            "好友 $friendName · 10:20 · 约了周四下午看展",
            "好友 $friendName · 20:05 · 提了下换工作的事，还没定",
            "群 项目组 · 11:05 · 联调推到下周，前端先接接口",
            "群 项目组 · 待办 · 周五前给出排期",
            "群 项目组 · 任务 · 接口联调 60%",
        ),
    )

    private val SYSTEM = """
        你负责把今天记下的记录整理成一份今日总结。

        只写今天实际发生的事和还没做完的事；不推测、不评价、不补充记录里没有的背景。
        同类的事合在一起说，不要逐条复述；没有内容的类别不提。
        好友的事可以点出是谁；群聊的事不写发言人，也不要出现 wxid 或 @。
    """.trimIndent()

    private val OUTPUT_RULE = """
        只输出 JSON，不要解释：
        {"headline":"24 字以内","points":["44 字以内"],"todos":["还没做完的事，没有就空数组"]}
        points 最多 4 条、todos 最多 3 条；今天没有值得写的内容时三个字段都留空。
    """.trimIndent()

    private fun parse(raw: String): Parsed? {
        val obj = AiJson.firstObject(raw) ?: return null
        return Parsed(
            headline = AiJson.str(obj, "headline").take(HEADLINE_MAX),
            points = AiJson.strings(obj, "points").take(MAX_POINTS).map { it.take(POINT_MAX) },
            todos = AiJson.strings(obj, "todos").take(MAX_TODOS).map { it.take(POINT_MAX) },
        )
    }

    /** 解析失败时取首句当标题，卡片上不至于空着。 */
    private fun fallbackHeadline(raw: String): String =
        raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .trim()
            .removePrefix("{")
            .removePrefix("\"")
            .take(HEADLINE_MAX)

    // ── 本地兜底与落盘 ──────────────────────────────────────────────────────

    private fun local(input: Input, nowMs: Long): TodaySummary = TodaySummary(
        day = AiDates.dayKey(nowMs),
        at = nowMs,
        signature = input.signature,
        headline = localHeadline(input),
        // 最近几条直接给原文：没有模型时至少能看到「今天记了什么」。
        points = input.lines.takeLast(MAX_POINTS).map { it.take(POINT_MAX) },
        todos = emptyList(),
        friendCount = input.friendCount,
        eventCount = input.eventCount,
        groupCount = input.groupCount,
        digestCount = input.digestCount,
        source = SOURCE_LOCAL,
    )

    private fun localHeadline(input: Input): String = buildString {
        if (input.friendCount > 0) {
            append(input.friendCount).append(" 位好友 ").append(input.eventCount).append(" 条约定")
        }
        if (input.groupCount > 0) {
            if (isNotEmpty()) append(" · ")
            append(input.groupCount).append(" 个群 ").append(input.digestCount).append(" 条摘要")
        }
    }.ifBlank { "今天有新的记录" }

    private fun read(): TodaySummary? {
        val text = AiProfileStore.readText(file) ?: return null
        return runCatching { json.decodeFromString<TodaySummary>(text) }.getOrElse {
            // 解析失败按「今天还没有总结」处理，不覆盖原文件：下次写入才重建。
            log("today 解析失败：${it.javaClass.simpleName}")
            null
        }
    }

    private fun write(summary: TodaySummary) {
        runCatching {
            AiProfileStore.writeAtomic(file, json.encodeToString(summary))
        }.onFailure { log("today 写入失败：${it.javaClass.simpleName}") }
    }

    private fun clock(ms: Long): String = AiDates.hhmm(ms)

    private fun log(message: String) = storeLog(TAG, message)
}
