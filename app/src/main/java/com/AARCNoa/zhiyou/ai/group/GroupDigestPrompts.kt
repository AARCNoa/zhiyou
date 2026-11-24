package com.AARCNoa.zhiyou.ai.group

import com.AARCNoa.zhiyou.ai.AiDates
import com.AARCNoa.zhiyou.ai.AiJson

/**
 * 群总结的提示词与输出解析。
 *
 * 只有两步：每片提炼要点（[sliceSystem] / [sliceUser]）→ 合成一份摘要
 * （[mergeSystem] / [mergeUser]）。单片也走这条路径，不再有「单片直出」的第二套提示词。
 *
 * 三条约束（与 AI 档案的提示词同一套口径）：
 * 1. **模型看不到的东西不写**：片号、总片数、上一片结果、进度，一律不进提示词；
 * 2. 判断标准只写在 system，输出格式只写在 user 末尾，一件事只写一处；
 * 3. 不写字数之外的格式要求：points 的条数与长度在 [parse] 里截断，不靠模型自觉。
 */
object GroupDigestPrompts {

    /** 每片只提炼要点，不和别的片合并（模型不知道有别的片）。 */
    fun sliceSystem(): String = """
        你负责提炼一个微信群一小段聊天记录里的要点。

        只写这一段里讨论的内容：在聊什么、有哪些结论、还有什么没定。
        不要写谁说的，也不要出现成员昵称、wxid 或 @；需要指代时用「有人」「大家」。
        不评价、不推测、不补充记录里没有的背景，也不逐条复述原话。
        寒暄、刷屏、表情包与图片占位不算内容；没有内容就把 points 留空。
    """.trimIndent()

    /** 最后一步：把要点合成一份摘要。只合并不新增；只有一份要点时就是压缩成摘要。 */
    fun mergeSystem(workGroup: Boolean = false): String {
        val base = """
            你负责把同一段时间的要点整理成一份摘要。

            只合并重复项、去掉寒暄，不新增要点里没有的内容。
            不要写谁说的，也不要出现成员昵称、wxid 或 @。
        """.trimIndent()
        return if (workGroup) base + "\n\n" + MERGE_WORK_RULES else base
    }

    /**
     * 工作群的判断规则。
     *
     * 合并时手里只有要点，判断依据不足就留空，不硬凑一个任务。
     * 「提到过」和「在推进」的区别只写在这里：写进 user 会和输出格式混在一起，
     * 模型容易把「讨论过」当成任务，列表上就会出现一条永远不动的假任务。
     */
    private val MERGE_WORK_RULES = """
        这个群是工作群，还要给出当前在推进的任务与进度：
        - 只根据要点里明确提到、有人在做的事判断；依据不足就 task 留空、progress 写 -1。
        - 进度按 0 到 100 估一个整数，拿不准时给保守值。
        - 任务写「在做什么」，不写过程。
        - 给了上一轮任务时：要点里没有提到它、也看不出新进展，就原样返回；明确完成、放弃或换任务时才改。
    """.trimIndent()

    fun sliceUser(groupName: String, fromSec: Long, toSec: Long, transcript: String): String = buildString {
        append("群名：").append(groupName.ifBlank { "未命名群" }).append('\n')
        append("时间：").append(range(fromSec, toSec)).append('\n')
        append("<聊天记录>\n").append(transcript).append("\n</聊天记录>\n\n")
        append("只输出 JSON，不要解释：\n")
        append("""{"headline":"30 字以内","points":["40 字以内"],"todos":["谁要做什么，没有就空数组"]}""")
        append("\npoints 最多 6 条。")
    }

    fun mergeUser(
        groupName: String,
        fromSec: Long,
        toSec: Long,
        parts: List<String>,
        workGroup: Boolean = false,
        previousTask: String = "",
        previousProgress: Int = -1,
    ): String = buildString {
        append("群名：").append(groupName.ifBlank { "未命名群" }).append('\n')
        append("时间：").append(range(fromSec, toSec)).append('\n')
        if (workGroup && previousTask.isNotBlank()) {
            append("上一轮任务：").append(previousTask)
            if (previousProgress in 0..100) append("（").append(previousProgress).append("%）")
            append('\n')
        }
        append("<要点>\n")
        parts.forEach { append(it).append('\n') }
        append("</要点>\n\n")
        append(outputRule(workGroup))
    }

    private val OUTPUT_RULE = """
        只输出 JSON，不要解释：
        {"significant":true,"headline":"30 字以内","points":["40 字以内"],"todos":["谁要做什么，没有就空数组"]}
        points 最多 6 条；整段没有值得看的内容时 significant 写 false，其余字段留空。
    """.trimIndent()

    /** 工作群的输出多两个字段；判断标准在 system，这里只给格式。 */
    private fun outputRule(workGroup: Boolean): String = if (!workGroup) {
        OUTPUT_RULE
    } else {
        """
            只输出 JSON，不要解释：
            {"significant":true,"headline":"30 字以内","points":["40 字以内"],"todos":["谁要做什么，没有就空数组"],"task":"20 字以内","progress":60}
            points 最多 6 条；progress 是 0~100 的整数，没有任务写 -1；
            整段没有值得看的内容时 significant 写 false，其余字段留空。
        """.trimIndent()
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    data class Parsed(
        val significant: Boolean,
        val headline: String,
        val points: List<String>,
        val todos: List<String>,
        /** 工作群当前任务；非工作群、或模型判断没有任务时为空。 */
        val task: String = "",
        /** 0~100；-1 表示没有。 */
        val progress: Int = -1,
    )

    /**
     * 解析模型输出。返回 null 表示完全不是 JSON —— 调用方用原文兜底，不整条丢。
     *
     * 先剥 ``` 围栏：部分服务商会把 JSON 包进 markdown，直接 parse 会失败。
     */
    fun parse(raw: String): Parsed? {
        val obj = AiJson.firstObject(raw) ?: return null
        return Parsed(
            significant = AiJson.bool(obj, "significant") ?: true,
            headline = AiJson.str(obj, "headline").take(HEADLINE_MAX),
            points = AiJson.strings(obj, "points").take(MAX_POINTS).map { it.take(POINT_MAX) },
            todos = AiJson.strings(obj, "todos").take(MAX_POINTS).map { it.take(POINT_MAX) },
            // 任务与进度互相独立地越界：task 空就丢掉进度，避免列表显示「【 60%】」。
            task = AiJson.str(obj, "task").take(TASK_MAX),
            progress = AiJson.int(obj, "progress")?.takeIf { it in 0..100 } ?: -1,
        )
    }

    /** 解析失败时用第一句当 headline，保证卡片上不是空的。 */
    fun fallbackHeadline(raw: String): String {
        val firstLine = raw.lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
            .removePrefix("{")
            .removePrefix("\"")
        return firstLine.take(HEADLINE_MAX).ifBlank { "（无摘要）" }
    }

    private fun range(fromSec: Long, toSec: Long): String =
        AiDates.timeOf(fromSec * 1000L) + " ~ " + AiDates.timeOf(toSec * 1000L)





}
