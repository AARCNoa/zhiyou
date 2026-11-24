package com.AARCNoa.zhiyou.ai.group

import com.AARCNoa.zhiyou.ai.AiDates
import com.AARCNoa.zhiyou.features.ai.AiConfig
import com.AARCNoa.zhiyou.utils.WeLogger

/**
 * 工作群的「工作汇报」：把该群的历史摘要整体喂给模型，写成一份从项目开始到现在的汇报。
 *
 * 与 [GroupDigestRunner] 的分工：
 * - Runner 每次只看一个窗口，产出「这一段聊了什么」；
 * - 这里把多轮摘要当成素材，产出「整个项目做了什么、到哪了、还差什么」。
 *
 * 只在用户点「汇报」时跑，不随总结自动触发；结果按「摘要条数 + 最后一条 id」缓存，
 * 没有新摘要就直接复用，避免每次展开都花一次请求。
 */
object GroupReport {

    private const val TAG = "GroupReport"

    /** 最多喂多少轮摘要：再往前的细节对汇报没有增量，只会撑大 token。 */
    private const val MAX_DIGESTS = 40

    /** 汇报正文上限：卡片展开区里再长就没人读了。 */
    private const val REPORT_MAX = 1200

    private const val TEMPERATURE = 0.4
    private const val REQUEST_TIMEOUT_MS = 180_000

    data class Result(
        val report: String? = null,
        val error: String? = null,
    )

    /**
     * 取该群的汇报；`force = true` 表示用户主动重算。
     *
     * 阻塞调用（可能发一次请求），调用方必须放在后台线程。
     */
    fun ensure(groupId: String, force: Boolean = false): Result {
        if (!AiConfig.isConfigured) return Result(error = "未配置 AI 接口")
        val config = GroupDigestStore.readConfig(groupId) ?: return Result(error = "读取群配置失败")
        if (!config.workGroup) return Result(error = "这个群没有开启工作群")
        if (config.digests.isEmpty()) return Result(error = "还没有群摘要")

        val key = config.digests.last().id
        val fresh = config.reportDigestCount == config.digests.size && config.reportDigestKey == key
        if (!force && config.report.isNotBlank() && fresh) {
            return Result(report = config.report)
        }

        val picked = config.digests.takeLast(MAX_DIGESTS)
        val result = AiConfig.chatWithTools(
            messages = listOf(
                AiConfig.ChatMessage(role = "system", content = SYSTEM),
                AiConfig.ChatMessage(role = "user", content = user(config.name, picked)),
            ),
            temperature = TEMPERATURE,
            readTimeoutMs = REQUEST_TIMEOUT_MS,
            // 用户主动触发的后台请求：成功失败都不改首页「AI 已激活」。
            affectStatus = false,
        )
        val text = result.text?.trim().orEmpty().take(REPORT_MAX)
        if (text.isBlank()) {
            val reason = result.error ?: "模型返回为空"
            WeLogger.w(TAG, "${config.groupId} 汇报生成失败：$reason")
            return Result(error = reason)
        }
        if (result.finishReason == "length") {
            WeLogger.w(TAG, "${config.groupId} 汇报被截断（finish_reason=length）")
        }

        val saved = GroupDigestStore.update(groupId) {
            it.copy(
                report = text,
                reportAt = System.currentTimeMillis(),
                reportDigestCount = it.digests.size,
                reportDigestKey = key,
            )
        }
        if (saved == null) {
            WeLogger.w(TAG, "${config.groupId} 汇报写盘失败，本次结果只留在界面")
        } else {
            WeLogger.i(TAG, "${config.groupId} 汇报完成：${text.length} 字 · ${picked.size} 条摘要")
        }
        return Result(report = text)
    }

    private fun user(groupName: String, digests: List<GroupDigest>): String = buildString {
        append("群名：").append(groupName.ifBlank { "未命名群" }).append('\n')
        val from = digests.first().fromSec
        val to = digests.last().toSec
        append("时间：").append(timeOfSec(from)).append(" ~ ").append(timeOfSec(to)).append('\n')
        // 给时间跨度：模型要靠「花了多久、推进多少」自己判断节奏，不能只给绝对时间点。
        append("共 ").append(digests.size).append(" 轮摘要，覆盖 ").append(span(from, to)).append('\n')
        append("<摘要>\n")
        digests.forEach { digest ->
            append("- ").append(timeOfSec(digest.toSec)).append(" · ").append(digest.headline).append('\n')
            if (digest.points.isNotEmpty()) {
                append("  要点：").append(digest.points.joinToString("；")).append('\n')
            }
            if (digest.todos.isNotEmpty()) {
                append("  待办：").append(digest.todos.joinToString("；")).append('\n')
            }
            if (digest.task.isNotBlank()) {
                append("  任务：").append(digest.task)
                if (digest.progress in 0..100) append(' ').append(digest.progress).append('%')
                append('\n')
            }
        }
        append("</摘要>\n\n")
        append("只输出汇报正文，不要解释。")
    }

    /**
     * 群摘要里的时间都是秒；界面统一按毫秒格式化，换算只在这一处。
     * 直接写 `timeOf(sec)` 会显示成 1970 年，而且不报错 —— 这是本项目踩过的坑。
     */
    private fun timeOfSec(sec: Long): String = AiDates.timeOf(sec * 1000L)

    /** 时间跨度：两天以上按天说，否则按小时说 —— 「覆盖 4 天」比「96 小时」更像人话。 */
    private fun span(fromSec: Long, toSec: Long): String {
        val hours = ((toSec - fromSec) / 3600L).coerceAtLeast(1L)
        return if (hours >= 48) "${hours / 24} 天" else "$hours 小时"
    }

    /**
     * 调试页预览：与真实请求同一套 system 与 user 拼装，只把摘要换成示例。
     *
     * 示例故意包含「同一个环节重复出现、进度只推进一点」的情况，
     * 这样能看出「慢」的那段话术到底会怎么写。
     */
    internal fun preview(nowSec: Long = System.currentTimeMillis() / 1000L): Pair<String, String> {
        val day = 86_400L
        val sample = listOf(
            GroupDigest(
                id = "preview-1",
                fromSec = nowSec - day * 4,
                toSec = nowSec - day * 3,
                createdAt = (nowSec - day * 3) * 1000L,
                headline = "接口定义评审完成",
                points = listOf("后端给出接口草案", "前端提出两个字段需要补充"),
                todos = listOf("后端补充字段说明"),
                task = "接口联调",
                progress = 15,
            ),
            GroupDigest(
                id = "preview-2",
                fromSec = nowSec - day * 2,
                toSec = nowSec - day,
                createdAt = (nowSec - day) * 1000L,
                headline = "字段说明补充，联调仍未开始",
                points = listOf("字段说明已补齐", "等待灰度环境就绪"),
                todos = listOf("确认灰度环境排期"),
                task = "接口联调",
                progress = 25,
            ),
            GroupDigest(
                id = "preview-3",
                fromSec = nowSec - day,
                toSec = nowSec,
                createdAt = nowSec * 1000L,
                headline = "灰度环境就绪，开始联调",
                points = listOf("灰度环境可用", "前后端已完成三个主要接口的对接"),
                todos = listOf("周五前跑完主流程"),
                task = "接口联调",
                progress = 45,
            ),
        )
        return SYSTEM to user("示例项目组", sample)
    }

    private val SYSTEM = """
        你在替项目负责人写一份给上级看的工作汇报，读者是不懂技术的上级。

        依据给定的一段时间的群摘要（每轮都有时间、要点、待办与任务进度），写成一份回看整个项目的汇报。

        先自己判断节奏（只在心里用，不要写出「我判断」「节奏偏慢」这类元话术）：
        看这些摘要覆盖了多长时间、任务推进了多少、同一个环节重复了几轮，得出这段是「比预期快」「正常」还是「偏慢」。

        结构：
        - 开头一句给整体判断：项目处在哪个阶段、进展是否可控。
        - 再按「做了什么 / 现在到哪了 / 还没完成什么」展开；同类的事合在一起，不逐条复述摘要。
        - 摘要之间没有明显先后关系时按主题归类，不硬编时间线。

        措辞按节奏调整 —— 汇报的目的之一是让上级看得懂、放心，不是罗列过程：
        - 推进快、同一环节一次过：写团队对这类事驾轻就熟、配合顺畅、按节点甚至提前完成，
          强调经验与效率。
        - 稳步推进：写按计划推进、节点清晰、风险可控。
        - 偏慢、或在同一个环节反复：不要写「拖了很久」「进度落后」，改成说明这一步为什么值得慢下来
          —— 核对细节、打牢基础、逐项验证避免返工 —— 并写出这段时间具体完成了什么
          （对齐了什么、验证了什么、补了哪些细节）。摘要里看不出对应工作就只陈述状态，不硬编原因。

        读者不懂技术：少写实现细节和术语，多讲阶段、能带来什么结果、风险是否可控；
        必须提到的技术名词，用一句人话说明它影响什么。

        事实不能变：只写摘要里有的内容，不推测、不编造成果；没做完的仍写在「还没完成」里，
        不能把没做的说成做完，也不能把延后说成提前。

        - 不写谁说的，也不要出现成员昵称、wxid 或 @；需要指代时用「大家」「有人」。
        - 300 字以内，纯文本；可以用「1. 2. 3.」编号，不要 markdown 标题、表格或代码块。
    """.trimIndent()
}
