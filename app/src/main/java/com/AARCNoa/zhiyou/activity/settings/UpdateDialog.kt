package com.AARCNoa.zhiyou.activity.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.AARCNoa.zhiyou.BuildConfig
import com.AARCNoa.zhiyou.activity.settings.ai.AiPrimaryButton
import com.AARCNoa.zhiyou.activity.settings.ai.AiSecondaryButton
import com.AARCNoa.zhiyou.ui.content.ScrimDialog
import com.AARCNoa.zhiyou.ui.style.WxPalette
import com.AARCNoa.zhiyou.update.UpdateAsset
import com.AARCNoa.zhiyou.update.UpdateChecker
import com.AARCNoa.zhiyou.update.UpdateManifest
import com.AARCNoa.zhiyou.utils.android.showToast

/** 更新弹窗要显示什么。首页与设置页共用同一份状态，页面只负责触发检查。 */
internal sealed interface UpdateDialogState {
    data object Hidden : UpdateDialogState
    data object Checking : UpdateDialogState
    data class Available(val manifest: UpdateManifest, val asset: UpdateAsset) : UpdateDialogState
    data object UpToDate : UpdateDialogState
    data class Failed(val reason: String) : UpdateDialogState
}

/**
 * 更新弹窗。
 *
 * 「有新版」这一态额外做一件事：并发实测清单里那几条下载地址，只把最快的一条
 * 交给浏览器 —— 用户不需要知道什么叫「加速线路」，也不该自己试哪个能开。
 */
@Composable
internal fun UpdateDialogHost(
    state: UpdateDialogState,
    onDismiss: () -> Unit,
    // 首页只从「有新版」进来，没有失败重试这条路径；设置页手动检查时传入。
    onRetry: () -> Unit = {},
) {
    ScrimDialog(
        show = state !is UpdateDialogState.Hidden,
        onDismissRequest = onDismiss,
        title = when (state) {
            is UpdateDialogState.Available -> "发现新版本 ${state.manifest.versionName}"
            is UpdateDialogState.Failed -> "检查更新失败"
            else -> "检查更新"
        },
    ) {
        when (state) {
            UpdateDialogState.Hidden -> Unit
            UpdateDialogState.Checking -> Body("正在连接更新服务器…")
            is UpdateDialogState.Available -> AvailableBody(state, onDismiss)
            UpdateDialogState.UpToDate -> {
                Body("已是最新版本 ${BuildConfig.VERSION_NAME}。")
                AiSecondaryButton(text = "关闭", onClick = onDismiss)
            }
            is UpdateDialogState.Failed -> {
                Body(state.reason)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AiSecondaryButton(text = "关闭", onClick = onDismiss, modifier = Modifier.weight(1f))
                    AiPrimaryButton(text = "重试", onClick = onRetry, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text = text, fontSize = 13.sp, color = WxPalette.textSecondary(), lineHeight = 19.sp)
}

@Composable
private fun AvailableBody(state: UpdateDialogState.Available, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manifest = state.manifest

    // 按版本号记住探测结果：重组、改文案都不该重新发 128KB。
    var picked by remember(manifest.versionCode) { mutableStateOf<String?>(null) }
    var probing by remember(manifest.versionCode) { mutableStateOf(true) }
    LaunchedEffect(manifest.versionCode, state.asset.urls) {
        picked = UpdateChecker.pickFastestUrl(state.asset.urls)
        probing = false
    }

    Body(
        "当前 ${BuildConfig.BRAND} ${BuildConfig.VERSION_NAME}" +
            if (manifest.publishedAt.length >= 10) " · ${manifest.publishedAt.take(10)}" else "",
    )

    val lines = remember(manifest.changelog) {
        manifest.changelog.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_CHANGELOG_LINES)
            .toList()
    }
    if (lines.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "更新内容",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textPrimary(),
            )
            lines.forEach { line ->
                Text(
                    text = "· $line",
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                    lineHeight = 18.sp,
                )
            }
        }
    }

    val chosen = picked
    when {
        // 探测最多 3 秒：按钮先占位，避免用户在「还在选线路」时点了没反应。
        probing -> AiPrimaryButton(text = "正在选择下载线路…", onClick = {}, enabled = false)
        chosen != null -> AiPrimaryButton(
            text = "用浏览器下载",
            onClick = { openInBrowser(context, chosen) },
        )
        else -> AiPrimaryButton(
            text = "打开发布页",
            onClick = { openInBrowser(context, UpdateChecker.RELEASES_PAGE) },
        )
    }
    if (!probing && chosen == null) {
        Text(
            text = "几条下载线路都连不上，发布页里仍有完整安装包。",
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
        )
    }
    AiSecondaryButton(text = "以后再说", onClick = onDismiss)
}

/** 交给系统浏览器：下载与安装都用浏览器自己的权限，我们既不申请也不经手文件。 */
private fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (!runCatching { context.startActivity(intent) }.isSuccess) showToast("没有可用的浏览器")
}

/** 更新说明最多展示 12 行：再多就把按钮挤出屏幕。 */
private const val MAX_CHANGELOG_LINES = 12
