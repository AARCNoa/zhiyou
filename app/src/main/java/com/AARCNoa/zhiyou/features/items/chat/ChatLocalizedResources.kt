package com.AARCNoa.zhiyou.features.items.chat

import com.AARCNoa.zhiyou.utils.safeGetQuantityString
import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

internal fun localizedChatString(@StringRes id: Int, vararg formatArgs: Any): String {
    val app = android.app.ActivityThread.currentApplication() ?: return id.toString()
    return app.getString(id, *formatArgs)
}

internal fun Context.localizedChatString(@StringRes id: Int, vararg formatArgs: Any): String =
    getString(id, *formatArgs)