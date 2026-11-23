package com.AARCNoa.zhiyou.features.items.system

import android.app.ActivityThread
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import dev.ujhhgtg.reflekt.reflekt
import com.AARCNoa.zhiyou.constants.PackageNames
import com.AARCNoa.zhiyou.features.core.ClickableFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.features.items.system.PredictiveBackGestures.PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK
import com.AARCNoa.zhiyou.ui.content.AlertDialogContent
import com.AARCNoa.zhiyou.ui.content.Button
import com.AARCNoa.zhiyou.ui.utils.showComposeDialog
import com.AARCNoa.zhiyou.utils.WeLogger

// https://github.com/Ujhhgtg/PandorasBox
@Feature(name = "预见性返回动画", categories = ["系统与隐私"], description = "为微信的活动强制启用预见性返回动画\n需系统 Android SDK >= 33")
object PredictiveBackGestures : ClickableFeature() {

    private const val PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 2
    private const val PRIVATE_FLAG_DISABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 3
    private const val PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 3

    private const val TAG = "PredictiveBackGestures"

    /**
     * ## 背景
     *
     * 本功能通过强开 [PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK] 让微信的活动进入
     * Android 13+ 的「提前分发 (ahead-of-time)」返回模型，从而获得系统预见性返回动画。
     *
     * ## 旧实现为何出错
     *
     * 一旦进入提前分发模型:
     *
     * 1. 系统在 `Activity.onCreate` 里给每个活动注册一个 **PRIORITY_SYSTEM** 的默认回调
     *    (`Activity.onBackInvoked` / API 33 上是 `navigateBack`)，它只在服务端 finish 活动，
     *    **不会**调用活动重写的 `onBackPressed()`。
     * 2. 系统不再向活动派发 KEYCODE_BACK；且 `Activity.onKeyUp` 仅在 `mDefaultBackCallback == null`
     *    时才回落到 `onBackPressed()`——而该字段此时非空。
     * 3. 预见性动画 (跨活动 / 回桌面) **只有在栈顶回调是 system 回调时**才会播放
     *    (`BackNavigationController`: 非 system 回调一律降级为无动画的 `TYPE_CALLBACK`)。
     *
     * 于是:
     * - 什么都不做 → 动画正常，但系统默认回调绕过了微信自己的返回链
     *   (`onBackPressListeners`、`LauncherUI.moveTaskToBack`、聊天页 fragment 的 `onKeyDown` 返回等)，
     *   导致大量界面直接 finish / 回桌面。
     * - 自己注册一个应用回调 → 由于应用回调不能是 system 优先级 (负优先级会抛异常)，它会顶掉
     *   系统默认回调，动画随之消失；且返回逻辑仍不完整。
     *
     * ## 本实现
     *
     * 给微信活动的 `ActivityInfo.privateFlags` 打开 [PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK]、
     * 关掉 DISABLE 位：活动进入提前分发模型，栈顶仍是那个 system 默认回调，**动画因此保持完整**。
     * 对所有活动统一生效，不需要再对 LauncherUI / 聊天页做特殊处理。
     *
     * 上面的分析保留下来是为了说明**不要**改成自注册应用回调 —— 应用回调不是 system 优先级，
     * 会顶掉系统默认回调，动画随之消失。
     */
    override fun onEnable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            WeLogger.w(TAG, "sdk < 33, not enabling predictive back gestures")
            return
        }

        ApplicationInfo::class.reflekt()
            .firstConstructor {
                parameters(ApplicationInfo::class.java)
            }.hookAfter {
                val info = args[0] as? ApplicationInfo ?: return@hookAfter
                runCatching {
                    val field =
                        info.reflekt().firstField { name = "privateFlagsExt" }
                    var flags = field.get() as Int
                    flags = flags or PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK
                    field.set(flags)
                }
            }

        ActivityInfo::class.reflekt()
            .firstConstructor()
            .hookAfter {
                val info = thisObject as? ActivityInfo ?: return@hookAfter
                if (info.name?.startsWith(PackageNames.MODULE) != true) return@hookAfter
                applyFlag(info)
            }

        ActivityThread::class.reflekt()
            .firstMethod { name = "handleLaunchActivity" }
            .hookBefore {
                val record = args[0] ?: return@hookBefore
                val infoField =
                    record.reflekt().firstField { name = "activityInfo" }
                val info = infoField.get() as? ActivityInfo ?: return@hookBefore
                if (!info.name.startsWith(PackageNames.MODULE)) return@hookBefore
                applyFlag(info)
            }
    }

    private fun applyFlag(info: ActivityInfo) {
        runCatching {
            val field = info.reflekt().firstField { name = "privateFlags" }
            var flags = field.get() as Int
            flags = flags or PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK
            flags = flags and PRIVATE_FLAG_DISABLE_ON_BACK_INVOKED_CALLBACK.inv()
            field.set(flags)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("预见性返回动画") },
                text = {
                    Text("未生效：需 Android 13+")
                },
                confirmButton = { Button(onDismiss) { Text("关闭") } })
        }
    }
}
