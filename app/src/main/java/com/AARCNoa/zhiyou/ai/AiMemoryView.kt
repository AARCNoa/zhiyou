package com.AARCNoa.zhiyou.ai

/**
 * 建议 / 解读时**默认注入**哪一段记忆，以及更早的那部分怎么取。
 *
 * 全量记忆标题（上限 2000 条）约 30k token，而一次建议真正用到的通常只有最近这段。
 * 默认只给按统一时间顺序排列后的最近 [RECENT_LIMIT] 条，不再按时间范围扩窗。
 *
 * `recent` 之外的全部算「没有注入」，放在 [Split.earlier]，由 `load_earlier_memories`
 * 一次交付（见 [Pager]）。
 */
object AiMemoryView {

    /** 默认注入最近多少条记忆；更早的由工具按需取回。 */
    const val RECENT_LIMIT = 100

    /**
     * 切分结果，两侧都保持**时间正序**且带全局序号。
     *
     * 序号必须是全档的：[AiTools.readMemory] 按同一份 `AiMemoryIndex` 解析，
     * 若默认块只给「最近 100 条」并重新编号，翻出来的 `#12` 与 `read_memory` 就对不上。
     */
    data class Split(
        val recent: List<Pair<Int, MemoryEntry>>,
        val earlier: List<Pair<Int, MemoryEntry>>,
    )

    fun of(profile: AiProfile): Split = split(profile.memories)

    /** 按统一时间正序切出最近 [RECENT_LIMIT] 条，序号仍为全档序号。 */
    fun split(memories: List<MemoryEntry>): Split {
        val numbered = AiMemoryIndex.numbered(memories)
        val recent = numbered.takeLast(RECENT_LIMIT)
        return Split(
            recent = recent,
            earlier = numbered.dropLast(recent.size),
        )
    }

    /** 一条一条渲染成 `#12 2021-03-05 见面★`。 */
    fun render(entries: List<Pair<Int, MemoryEntry>>, separator: String = "、"): String =
        entries.joinToString(separator) { (number, entry) ->
            "#$number ${entry.occurredAt} ${entry.title}" + if (entry.important) "★" else ""
        }

    /**
     * 一次请求内的交付标记。
     *
     * 工具**没有参数**，靠这个类知道「已经给过什么」：交付过的部分不会再给，
     * 而不是把同一批条目再灌一遍。带 `before=日期` 的方案要求模型抄日期，
     * 抄错就会重复加载 —— 重复的几千 token 比多一个类贵得多。
     *
     * **一次给完，不分页**：模型不知道第 100 条之后有什么，就没法判断要不要翻第二页 ——
     * 它只能整体看一遍，按标题认出「是那次」，再用 `read_memory` 读详情。
     * 上限 1900 条，仍只在模型明确要求时交付。
     */
    class Pager internal constructor(private val earlier: List<Pair<Int, MemoryEntry>>) {

        @Volatile
        private var delivered = false

        val remaining: Int get() = if (delivered) 0 else earlier.size

        /** 一次性交付剩余部分；已经交付过则返回空。 */
        fun takeEarlier(): List<Pair<Int, MemoryEntry>> {
            if (delivered) return emptyList()
            delivered = true
            return earlier
        }
    }

    fun pager(profile: AiProfile): Pager = Pager(of(profile).earlier)

}
