package com.AARCNoa.zhiyou.features.items.chat

import android.content.ContentValues
import com.AARCNoa.zhiyou.features.api.core.WeDatabaseListenerApi
import com.AARCNoa.zhiyou.features.api.core.WeMessageApi
import com.AARCNoa.zhiyou.features.api.core.models.MessageInfo
import com.AARCNoa.zhiyou.features.api.core.models.MessageType
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.preferences.WePrefs
import com.AARCNoa.zhiyou.utils.WeLogger

/**
 * 文件消息在「对方上传中」时是一个占位行，微信会在传完后就地更新它的 content，
 * 所以除了插入，还要监听更新事件。
 */
@Feature(name = "自动缓存文件", categories = ["聊天"], description = "监听接收到的文件消息, 在对方上传完成后自动触发微信内部下载将其缓存到本地")
object AutoCacheFiles : AutoCacheFeature(), WeDatabaseListenerApi.IUpdateListener {

    override val typeCode = MessageType.FILE.code
    override val label = "文件"
    override val logTag = "AutoCacheFiles"

    override var useWhitelist by WePrefs.prefOption("autocache_files_use_whitelist", false)
    override var whitelist by WePrefs.prefOption("autocache_files_whitelist", emptySet())
    override var blacklist by WePrefs.prefOption("autocache_files_blacklist", emptySet())

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        if (table != "message") return
        handleMessage(values)
    }

    /**
     * 通过 appmsg 内层 `<type>` 判断文件状态：
     * 74 / 131 是「对方仍在上传」，此刻下载必然失败，等更新事件再处理；
     * 6 / 130 是「文件已就绪」，可以下载。
     */
    override fun shouldCache(values: ContentValues): Boolean {
        val content = values.getAsString("content") ?: return false
        val file = try {
            MessageInfo.FileMessage(content)
        } catch (e: Exception) {
            WeLogger.w(logTag, "failed to parse appmsg content, skip", e)
            return false
        }
        return when {
            file.isSenderUploading -> {
                WeLogger.d(logTag, "sender still uploading (appmsgType=${file.appMsgType}), deferring")
                false
            }

            !file.isDownloadable -> {
                WeLogger.d(logTag, "not downloadable yet (appmsgType=${file.appMsgType}), skip")
                false
            }

            else -> true
        }
    }

    override fun cache(values: ContentValues, msgSvrId: Long): Boolean {
        // 直接用这条消息的 ContentValues 重建实例，避免再按 msgSvrId 查库（可能查不到 / 字段缺失）。
        val msgInfoInstance = try {
            WeMessageApi.convertMsgInfoInstanceFromContentValues(values)
        } catch (e: Exception) {
            WeLogger.w(logTag, "failed to build msgInfo from values", e)
            return false
        }
        launchCache(msgSvrId) { WeMessageApi.cacheFile(msgInfoInstance) }
        return true
    }
}
