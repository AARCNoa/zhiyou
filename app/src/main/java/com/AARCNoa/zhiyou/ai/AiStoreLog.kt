package com.AARCNoa.zhiyou.ai

import com.AARCNoa.zhiyou.utils.WeLogger

/**
 * AI 数据层统一的失败日志。
 *
 * 双写是刻意的：模块进程（无微信 Context）里 [WeLogger] 未必找得到日志目录，
 * logcat 那一份仍然能看到；两处都包 `runCatching`，日志本身不许再抛异常。
 *
 * 原来 `AiProfileStore` / `AiOverviewStore` / `AiTodaySummary` / `GroupDigestStore`
 * 各写一遍同样的四行，tag 还各拼一遍 —— 现在只此一处。
 */
internal fun storeLog(tag: String, message: String) {
    runCatching { WeLogger.w(tag, message) }
    runCatching { android.util.Log.w("WCX-$tag", message) }
}
