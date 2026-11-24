package com.AARCNoa.zhiyou.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 模型输出的 JSON 兜底解析，AI 子系统唯一实现。
 *
 * 四类调用点（建议 / 今日总结 / 群总结 / 朋友圈评论）原先各写一份，容错细节还不一致 ——
 * 有的只剥小写 ` ```json `，模型真返回 ` ```JSON ` 时就当成非 JSON 走了兜底。
 * 这里统一三步：原文 → 剥围栏 → 截首尾花括号，谁先能解析成对象就用谁。
 */
internal object AiJson {

    private val json = Json { ignoreUnknownKeys = true }

    /** 去掉 markdown 代码围栏，大小写都认。 */
    fun stripFence(raw: String): String = raw.trim()
        .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        .removeSuffix("```").trim()

    /** 解析候选：原文 → 剥围栏 → 首尾花括号之间。顺序即优先级，模型多包一层时靠后两条兜底。 */
    fun candidates(raw: String): List<String> = buildList {
        add(raw)
        val stripped = stripFence(raw)
        if (stripped != raw) add(stripped)
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start in 0 until end) add(raw.substring(start, end + 1))
    }

    /** 第一个能解析成对象的候选；全都不是 JSON 时返回 null，调用方自己决定用什么兜底。 */
    fun firstObject(raw: String): JsonObject? {
        candidates(raw).forEach { candidate ->
            runCatching { json.parseToJsonElement(candidate).jsonObject }
                .getOrNull()
                ?.let { return it }
        }
        return null
    }

    /** 取字符串字段并 trim；缺失或类型不符按空串。 */
    fun str(obj: JsonObject, key: String): String =
        runCatching { obj[key]?.jsonPrimitive?.contentOrNull.orEmpty().trim() }.getOrDefault("")

    /** 取字符串数组，丢掉空项。 */
    fun strings(obj: JsonObject, key: String): List<String> = runCatching {
        obj[key]?.jsonArray?.mapNotNull {
            it.jsonPrimitive.contentOrNull?.trim()?.takeIf { text -> text.isNotEmpty() }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** 取布尔字段；缺失时返回 null，由调用方给默认值。 */
    fun bool(obj: JsonObject, key: String): Boolean? =
        runCatching { obj[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

    /** 取整数字段；缺失或不是数字时返回 null（不要默认成 0：0 常是合法值）。 */
    fun int(obj: JsonObject, key: String): Int? =
        runCatching { obj[key]?.jsonPrimitive?.intOrNull }.getOrNull()
}
