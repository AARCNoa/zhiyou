package com.AARCNoa.zhiyou.features.items.chat

import com.AARCNoa.zhiyou.features.core.ApiFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.utils.reflection.ClassLoaders
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.invokeOriginal
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge

/**
 * 聊天页「附近直播」浮窗崩溃防御，静默功能：没有开关，随模块启动常驻生效。
 *
 * 实测崩溃（微信 8.0.80 / Android 16，小米 shennong）：
 * ```
 * java.lang.NullPointerException: ... View.getResources()' on a null object reference
 *   at cn2.n0.a
 *   at pa6.n.getValue
 *   at com.tencent.mm.plugin.finder.feed.eg.<init>
 *   at com.tencent.mm.plugin.finder.nearby.live.view.NearbyLivePreviewView.m
 *   at com.tencent.mm.ui.chatting.component.c4.t0
 *   at com.tencent.mm.sdk.platformtools.i3.dispatchMessage
 *   at android.os.Looper.loopOnce
 * ```
 * 这是微信自身缺陷（栈内无任何模块代码），无法用常规 hookBefore/After 拦截——
 * 异常发生在微信自己的方法体内，构造器与 hookAfter 都接不住。
 *
 * 防御挂点：崩溃链上最外层的**普通方法**，按顺序试两个（见 [onEnable]）：
 * 1. 延迟任务 `iq0.o.run`（Runnable）——只有部分 8.0.80 渠道包有这个类；
 * 2. 浮窗控件自己 `NearbyLivePreviewView.m(I)V`——8.0.80 实装包里没有 `iq0.o`，实际走这条。
 * 都找不到说明该版本没有可防御的对象，只留一行 debug，不是故障。
 *
 * 在挂点上套「替换 + invokeOriginal + try-catch」：仅当异常栈命中已知的微信自身
 * 崩溃（含 finder 直播预览、不含任何模块帧）时才吞掉——消息循环继续，浮窗不显示，
 * 进程不闪退；其它一切异常照常 rethrow，交给崩溃处理器记录。
 *
 * 注意：绝不能挂在 i3.dispatchMessage 上——那是主线程所有消息的入口，
 * replacement + invokeOriginal 每条消息都要走一次反射，实测会拖慢整个微信。
 */
@Feature(
    name = "聊天直播浮窗崩溃防御",
    categories = ["聊天"],
    description = "修复聊天页「附近直播」浮窗初始化的闪退"
)
object ChatLivePreviewCrashGuard : ApiFeature() {

    private const val TAG = "ChatLivePreviewCrashGuard"

    /** 模块自身的包名前缀：异常栈里只要出现模块帧，就说明是模块引起的，绝不能吞。 */
    private val MODULE_PACKAGES = listOf(
        "com.AARCNoa.zhiyou",
        "h.Hchat",
        "me.hd.wauxv",
        "bsh.",
        "org.luckypray.dexkit",
    )

    /** 已知微信自身崩溃的特征（聊天页「附近直播」预览浮窗初始化 NPE）。 */
    private const val VICTIM_PACKAGE = "com.tencent.mm.plugin.finder"
    private const val VICTIM_HINT = "nearby"

    /** 预览浮窗控件：类名未混淆、跨版本稳定；崩溃栈里就是它的 `m`。 */
    private const val PREVIEW_VIEW_CLASS =
        "com.tencent.mm.plugin.finder.nearby.live.view.NearbyLivePreviewView"

    /**
     * 该 NPE 只出现在 8.0.80 及以上；8.0.79 的崩溃栈里（`cn2.n0` / `pa6.n`）没有可挂的普通方法，
     * 等于没有可防御的对象，直接跳过，避免每次启动刷一条「找不到挂点」的日志。
     */
    override fun onEnable() {
        if (!hostVersionAtLeast(8, 0, 80)) {
            WeLogger.d(TAG, "微信版本低于 8.0.80，不需要该防御")
            return
        }
        // 挂点一：上游用的延迟任务（部分 8.0.80 渠道包有这个类）。命中就不再挂第二层。
        if (hookDelayedTask()) return
        // 挂点二：直接挂浮窗控件自己。类名未混淆；8.0.80（versionCode 3200）实测没有 iq0.o。
        if (hookPreviewView()) return
        // 两个都找不到说明这一版没有可防御的对象，不是故障：只留一行 debug，不打 W。
        WeLogger.d(TAG, "当前微信版本没有已知的直播浮窗崩溃挂点，防御未安装")
    }

    private fun hookDelayedTask(): Boolean {
        val taskClass = runCatching {
            Class.forName("iq0.o", false, ClassLoaders.HOST)
        }.getOrNull() ?: return false
        if (!Runnable::class.java.isAssignableFrom(taskClass)) return false
        val runMethod = runCatching {
            taskClass.getDeclaredMethod("run").apply { isAccessible = true }
        }.getOrNull() ?: return false
        return guardMethod(runMethod, "iq0.run")
    }

    /**
     * 直接挂 `NearbyLivePreviewView.m(I)V`。
     *
     * 8.0.80（versionCode 3200）的 dex 里没有 `iq0.o`，但崩溃栈最内层的普通方法就是
     * 这个 `m` —— 它构造「附近直播」预览浮窗的整棵 UI（`w43.d5` 渲染层、`RatioLayout`、
     * `iy2` 包下的一串监听器都在这里 new）。类名未混淆（微信自己用类名做埋点），方法名是
     * 8.0.80 的混淆名，按「类名 + m(I)V」定位，找不到就跳过。
     */
    private fun hookPreviewView(): Boolean {
        val clazz = runCatching {
            Class.forName(PREVIEW_VIEW_CLASS, false, ClassLoaders.HOST)
        }.getOrNull() ?: return false
        val method = runCatching {
            clazz.declaredMethods.firstOrNull {
                it.name == "m" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.returnType == Void.TYPE
            }
        }.getOrNull() ?: return false
        return guardMethod(method, "${clazz.simpleName}.m(I)V")
    }

    /** 套「replace + invokeOriginal + try-catch」：只吞已知的微信自身崩溃，其它原样抛。 */
    private fun guardMethod(method: java.lang.reflect.Method, label: String): Boolean = runCatching {
        XposedBridge.hookMethod(method, object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                return try {
                    param.invokeOriginal()
                } catch (t: Throwable) {
                    if (isKnownWeChatCrash(t)) {
                        WeLogger.e(
                            TAG,
                            "已吞掉微信自身崩溃（聊天页「附近直播」浮窗 NPE，本次浮窗不显示）: ${t.message}",
                        )
                        null
                    } else {
                        throw t
                    }
                }
            }
        })
        WeLogger.i(TAG, "聊天直播浮窗崩溃防御已安装（$label，仅豁免该已知崩溃）")
        true
    }.getOrElse {
        WeLogger.w(TAG, "hook $label 失败，防御不可用", it)
        false
    }

    /** 栈里有 finder 直播预览帧、且没有任何模块帧 → 视为已知的微信自身崩溃。 */
    private fun isKnownWeChatCrash(t: Throwable): Boolean {
        var foundVictim = false
        var foundModule = false

        var cause: Throwable? = t
        while (cause != null && !foundModule) {
            for (frame in cause.stackTrace) {
                val cls = frame.className
                if (MODULE_PACKAGES.any { cls.startsWith(it) }) {
                    foundModule = true
                    break
                }
                if (!foundVictim && cls.startsWith(VICTIM_PACKAGE) && cls.contains(VICTIM_HINT)) {
                    foundVictim = true
                }
            }
            cause = cause.cause
        }

        return foundVictim && !foundModule
    }
}
