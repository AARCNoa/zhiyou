@file:Suppress("NOTHING_TO_INLINE")

package com.AARCNoa.zhiyou.utils.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.WeLogger

/**
 * 全模块唯一的剪贴板写入入口。
 *
 * 返回是否写入成功，调用方据此给「已复制 / 复制失败」提示——别再各处自己写
 * `getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager` + `setPrimaryClip`：
 * 那样会漏掉两个必要防护（超长内容截断、写入异常兜底），且提示文案各自为政。
 *
 * @param label 剪贴板描述（Android 13+ 的复制预览会显示它），一般填内容类型。
 */
fun copyToClipboard(context: Context, content: String, label: String = "text"): Boolean {
    // 剪贴板走 binder，内容过大（>~1MB）会抛 TransactionTooLargeException 导致二次崩溃：
    // 先截断到安全长度并注明，完整内容仍保存在本地文件；再以 try-catch 兜底写入失败。
    val MAX_CLIP_CHARS = 300_000
    val clipText = if (content.length > MAX_CLIP_CHARS) {
        content.take(MAX_CLIP_CHARS) + "\n\n[内容过长已截断，完整内容见模块崩溃日志文件]"
    } else content
    return try {
        val clipboard = context.getSystemService<ClipboardManager>()
        clipboard.setPrimaryClip(ClipData.newPlainText(label, clipText))
        true
    } catch (e: Throwable) {
        WeLogger.w("Clipboard", "写入剪贴板失败", e)
        false
    }
}

inline fun copyToClipboard(content: String): Boolean = copyToClipboard(HostInfo.application, content)

inline fun readTextFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService<ClipboardManager>()
    val item = clipboard.primaryClip?.getItemAt(0) ?: return null
    return item.text?.toString()
}
