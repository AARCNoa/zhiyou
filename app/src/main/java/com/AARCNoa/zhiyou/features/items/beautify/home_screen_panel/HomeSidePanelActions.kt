package com.AARCNoa.zhiyou.features.items.beautify.home_screen_panel

import android.app.Activity
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector

import com.AARCNoa.zhiyou.features.items.beautify.BeautifyText
import com.AARCNoa.zhiyou.features.items.beautify.WeChatQuickActions
import com.AARCNoa.zhiyou.ui.icons.FilledIcons
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.killHost
import com.AARCNoa.zhiyou.utils.restartHost
import kotlinx.coroutines.CoroutineScope

internal data class HomeSidePanelActionSpec(
    val kind: HomeSidePanelActionKind,
    val labelRes: String,
    val icon: ImageVector,
)

internal fun homeSidePanelActionSpec(kind: HomeSidePanelActionKind): HomeSidePanelActionSpec = when (kind) {
    HomeSidePanelActionKind.SCAN -> HomeSidePanelActionSpec(
        kind,
        "扫一扫",
        FilledIcons.Qr_code_scanner,
    )

    HomeSidePanelActionKind.MOMENTS -> HomeSidePanelActionSpec(
        kind,
        "朋友圈",
        FilledIcons.Camera,
    )

    HomeSidePanelActionKind.WALLET -> HomeSidePanelActionSpec(
        kind,
        "钱包",
        FilledIcons.Wallet,
    )

    HomeSidePanelActionKind.CHANNELS -> HomeSidePanelActionSpec(
        kind,
        "视频号",
        FilledIcons.Movie,
    )

    HomeSidePanelActionKind.WECHAT_SETTINGS -> HomeSidePanelActionSpec(
        kind,
        "设置",
        FilledIcons.Settings,
    )

    HomeSidePanelActionKind.FAVORITES -> HomeSidePanelActionSpec(
        kind,
        "收藏夹",
        FilledIcons.Favorite,
    )

    HomeSidePanelActionKind.WEKIT_SETTINGS -> HomeSidePanelActionSpec(
        kind,
        "模块设置",
        FilledIcons.Extension,
    )

    HomeSidePanelActionKind.RESTART_WECHAT -> HomeSidePanelActionSpec(
        kind,
        "重启微信",
        FilledIcons.Update,
    )

    HomeSidePanelActionKind.FORCE_STOP_WECHAT -> HomeSidePanelActionSpec(
        kind,
        "强行停止",
        FilledIcons.Cancel,
    )

    HomeSidePanelActionKind.MARK_ALL_READ -> HomeSidePanelActionSpec(
        kind,
        "清空未读",
        FilledIcons.Mark_chat_read,
    )
}

internal class HomeSidePanelActionExecutor(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val closePanel: ((() -> Unit)?) -> Unit,
    private val publishMessage: (BeautifyText) -> Unit,
) {

    fun execute(kind: HomeSidePanelActionKind) {
        // 关闭面板后立即执行动作，不依赖关闭动画的回调：
        // HomeSidePanel.animateTo 在动画被 cancel 时 onAnimationEnd 会直接 return，
        // afterClosed 将永不触发，导致动作丢失（表现为“点击无反应”）。
        WeLogger.i(TAG, "侧边栏动作触发：$kind")
        // 先提交目标页面启动，再收起浮层：目标 Activity 会立刻盖上来，用户感觉是「秒跳」，
        // 也不用为根本看不到的关闭动画逐帧付掉帧的代价。
        runCatching { executeAfterPanelClosed(kind) }
            .onFailure { WeLogger.e(TAG, "侧边栏动作执行异常：$kind", it) }
        // 收起浮层挪到下一帧：这一帧已经被 startActivity 的 Binder 调用占满，
        // 再叠加面板收起动画就会明显掉帧（用户感知就是「卡一下才跳」）。
        val deferredClose = Runnable {
            runCatching { closePanel(null) }
                .onFailure { WeLogger.w(TAG, "关闭面板失败（动作已执行）：$kind", it) }
        }
        val decor = activity.window?.decorView
        if (decor != null) decor.post(deferredClose) else deferredClose.run()
    }

    fun openPaymentCode() {
        closePanel { WeChatQuickActions.openPaymentCode(activity) }
    }

    private fun executeAfterPanelClosed(kind: HomeSidePanelActionKind) {
        when (kind) {
            HomeSidePanelActionKind.SCAN -> {
                startWeChatActivity("com.tencent.mm.plugin.scanner.ui.BaseScanUI")
            }

            HomeSidePanelActionKind.MOMENTS -> {
                startWeChatActivity("com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI")
            }

            HomeSidePanelActionKind.WALLET -> {
                startWeChatActivity("com.tencent.mm.plugin.mall.ui.MallIndexUIv2") {
                    putExtra("key_not_goto_launcher_ui_when_back", true)
                }
            }

            HomeSidePanelActionKind.CHANNELS -> {
                startWeChatActivity("com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI")
            }

            HomeSidePanelActionKind.WECHAT_SETTINGS -> {
                startWeChatActivity("com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI")
            }

            HomeSidePanelActionKind.FAVORITES -> {
                startWeChatActivity("com.tencent.mm.plugin.fav.ui.FavoriteIndexUI")
            }

            HomeSidePanelActionKind.WEKIT_SETTINGS -> WeChatQuickActions.openModuleSettings(activity)

            HomeSidePanelActionKind.RESTART_WECHAT -> restartHost()
            HomeSidePanelActionKind.FORCE_STOP_WECHAT -> killHost()
            // 与 FAB 共用同一份实现：标已读 + 刷新会话列表，结果用 Toast 提示
            // （浮层此时已关闭，面板内消息用户看不到，会被当成「点了没反应」）。
            HomeSidePanelActionKind.MARK_ALL_READ -> WeChatQuickActions.markAllReadAsync(activity)
        }
    }

    private fun startWeChatActivity(
        className: String,
        extras: Intent.() -> Unit = {},
    ) {
        WeChatQuickActions.openWeChatActivity(activity, className, extras)
    }

    private companion object {
        const val TAG = "HomeSidePanelAction"
    }
}
