package com.AARCNoa.zhiyou.features.items.moments

import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.DexMethodDelegate
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.getTopMostActivity
import com.AARCNoa.zhiyou.utils.reflection.ClassLoaders
import android.os.SystemClock
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

@Feature(
    name = "禁止自动播放视频",
    categories = ["朋友圈"],
    description = "禁止朋友圈中的视频自动播放"
)
object DisableVideosAutoPlay : SwitchFeature(), IResolveDex {

    private const val TAG = "DisableVideosAutoPlay"

    /**
     * 播放器类名未混淆（微信自己用类名做埋点），方法名是混淆的：
     * `K()V` = resumePlay、`J()Z` = resumeByDataGain、`onPrepared()` = 缓冲完成后的自动 start。
     */
    private val PLAYER_CLASSES = arrayOf(
        // 8.0.80 实测：改善版时间线（ImproveSnsTimelineUI）与广告卡的播放器在这三个包里。
        "com.tencent.mm.plugin.sns.ui.OnlineVideoView",
        "com.tencent.mm.plugin.sns.ui.video.SnsTimelineVideoView",
        "com.tencent.mm.plugin.sns.ad.timeline.video.online.CardOnlineVideoView",
        // 后两条是按早期资料写的旧包名，当前版本不存在；留着零成本（Class.forName 失败即跳过），
        // 万一老版本真用这两个名字也能命中。
        "com.tencent.mm.plugin.sns.ui.SnsTimelineVideoView",
        "com.tencent.mm.plugin.sns.ui.CardOnlineVideoView",
    )

    /** 全屏播放页：用户点开视频后才有的页面，这里必须放行。 */
    private val FULLSCREEN_PAGES = arrayOf(
        "SnsOnlineVideoActivity", "SnsGalleryUI", "SnsVideoDetailUI",
    )

    /** 最后一次点击的时刻：点播放按钮 / 点开视频后 1.2 秒内放行。 */
    @Volatile
    private var lastClickAt = 0L

    private const val CLICK_WINDOW_MS = 1200L

    /**
     * `onPrepared` 的放行窗口要比 resume 宽：它是播放器缓冲完成后的回调，
     * 用户点播放到真正准备完可能隔几秒，1.2 秒窗口会导致「点了播放却播不了」。
     */
    private const val PREPARED_WINDOW_MS = 8_000L

    /** 播放器接口：`OnlineVideoView` 持有它的一个实例字段（`B`）。 */
    private const val PLAYER_INTERFACE = "com.tencent.mm.pluginsdk.ui.tools.e4"

    /**
     * 本次调用被判定为「自动播放」：before 里置位，after 里消费。
     */
    private val blockedThisCall = ThreadLocal<Boolean>()

    // Hook ①：SnsAutoPlayUtil.checkAutoPlay — 自动播放策略判断
    private val methodCheckAutoPlay by dexMethod {
        matcher {
            usingEqStrings(
                "checkAutoPlay",
                "com.tencent.mm.plugin.sns.util.SnsAutoPlayUtil"
            )
        }
    }

    // Hook ②：ImproveAutoPlayManager.autoPlay$2.invoke — 改进版自动播放触发
    private val methodImproveAutoPlayInvoke by dexMethod {
        matcher {
            usingEqStrings(
                "invoke",
                $$"com.tencent.mm.plugin.sns.ui.improve.util.ImproveAutoPlayManager$autoPlay$2"
            )
        }
    }

    override fun onEnable() {
        // Hook ① 是「返回 boolean 就自动播」的判定，强制 false 即可；
        // Hook ② 的 invoke 返回 Object，[forceFalse] 不会动它——留着给返回 boolean 的旧版本。
        // 锚点解析不到时 hookBefore 内部会跳过（并打 D），不会拿占位方法去 hook。
        forceFalse(methodCheckAutoPlay, "checkAutoPlay")
        forceFalse(methodImproveAutoPlayInvoke, "ImproveAutoPlay")

        installPlayerHooks()
    }

    /**
     * 让一个返回 boolean 的方法恒为 false。
     *
     * 返回值不是 boolean（例如 `onPrepared` 这类 void 方法）就不动它 ——
     * 一律写 `result` 会把调用方拿到的对象替换成 null，引出别的崩溃。
     * 回调内不自己 try：`executeHookAction` 已经包了一层并打 E 日志，
     * 这里再吞掉异常反而让问题在日志里消失。
     */
    private fun forceFalse(delegate: DexMethodDelegate, label: String) {
        try {
            delegate.hookBefore {
                // param.method 是 Member（可能是构造器），要取返回类型必须先落到 Method。
                val type = (method as? java.lang.reflect.Method)?.returnType ?: return@hookBefore
                if (type == Boolean::class.javaPrimitiveType || type == Boolean::class.java) {
                    result = false
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "$label hook 注册失败", e)
        }
    }

    /**
     * 按播放器类名补一层拦截，两条放行条件（见 [allowWithin]）：
     * 1. 当前是全屏播放页（点开视频后才有的 Activity）；
     * 2. 最近 [CLICK_WINDOW_MS] 内有过真实点击——点播放按钮、点开视频都算。
     *
     * 滑动不产生点击信号，所以滑动永远不放行；拿不到当前页面也不放行：
     * 这些类只服务朋友圈，误拦的代价是「滑回来不自动播」，放行则等于自动播放照旧。
     *
     * 拿不到点击信号就整段跳过：否则用户点播放也播不了，比自动播放更糟。
     */
    private fun installPlayerHooks() {
        if (!hookClickSignal()) {
            WeLogger.w(TAG, "点击信号 hook 失败，跳过播放器拦截（否则点播放也播不了）")
            return
        }
        // 启动时打一行「播放器接口字段叫什么」：字段名会随改版变，出问题时先看这行。
        // 找不到也不影响后续安装——下面按方法名挂，不依赖这个字段。
        runCatching {
            var c: Class<*>? = Class.forName(
                "com.tencent.mm.plugin.sns.ui.OnlineVideoView", false, ClassLoaders.HOST,
            )
            while (c != null) {
                val field = c.declaredFields.firstOrNull { it.type.name == PLAYER_INTERFACE }
                if (field != null) {
                    WeLogger.i(
                        TAG,
                        "播放器字段：${c.name.substringAfterLast('.')}.${field.name}:${field.type.name}",
                    )
                    return@runCatching
                }
                c = c.superclass
            }
            WeLogger.w(TAG, "OnlineVideoView 上没有 $PLAYER_INTERFACE 类型的字段，只能退回跳过方法")
        }
        val installed = mutableListOf<String>()
        PLAYER_CLASSES.forEach { className ->
            val clazz = runCatching { Class.forName(className, false, ClassLoaders.HOST) }.getOrNull()
                ?: return@forEach
            val simple = clazz.name.substringAfterLast('.')
            // K()V = resumePlay，J()Z = resumeByDataGain
            if (hookResume(clazz, "K", isVoid = true)) installed += "$simple.K()V"
            if (hookResume(clazz, "J", isVoid = false)) installed += "$simple.J()Z"
            // 关键：缓冲完成后播放器自己在这里 start()，不经过 K/J。
            // 只拦 resume 的话，滑动停下 → prepare → onPrepared → 照旧自动播。
            if (hookResume(clazz, "onPrepared", isVoid = true, windowMs = PREPARED_WINDOW_MS)) {
                installed += "$simple.onPrepared()V"
            }
        }
        if (installed.isEmpty()) {
            WeLogger.w(TAG, "没有找到播放器恢复方法（K/J），只靠策略层拦截")
        } else {
            // 把具体类名列出来：只打数量的话，下次某个包名写错仍然看不出来。
            WeLogger.i(TAG, "播放器拦截已安装：${installed.joinToString("、")}")
        }
    }

    private fun hookResume(
        clazz: Class<*>,
        name: String,
        isVoid: Boolean,
        windowMs: Long = CLICK_WINDOW_MS,
    ): Boolean {
        val method = runCatching {
            clazz.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty() &&
                    if (isVoid) it.returnType == Void.TYPE
                    else it.returnType == Boolean::class.javaPrimitiveType
            }
        }.getOrNull() ?: run {
            WeLogger.d(TAG, "${clazz.name.substringAfterLast('.')} 没有 $name 的匹配方法")
            return false
        }
        val label = "${clazz.name.substringAfterLast('.')}.$name"
        val installed = runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (allowWithin(windowMs)) return
                    noteBlocked(label)
                    blockedThisCall.set(true)
                    // 跳过整个方法：既不 start，也不执行后面的 startPlay / startTimer
                    // （那两个会把 UI 切成「播放中」，Surface 露出来就是纯色）。
                    param.result = if (isVoid) null else false
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (blockedThisCall.get() != true) return
                    blockedThisCall.remove()
                    // 播放器已经 prepare 过，Surface 是纯色/半成品。调微信自己的「用户暂停」
                    // 把画面收回来：SnsTimelineVideoView.F() 会先截当前帧设为封面再 super.F()
                    // （pausePlay：关 allowStart → e4.pause() → 回调 onPause）。
                    // 不要用 E()（pauseByDataBlock）：那条路是「数据不足」，UI 会一直转加载圈。
                    pausePlay(param.thisObject)
                }
            })
        }.onFailure {
            WeLogger.w(TAG, "hook $label 失败：${it.message}", it)
        }.isSuccess
        return installed
    }

    /** 调 `OnlineVideoView.F()` / `SnsTimelineVideoView.F()`（pausePlay），子类优先。 */
    private fun pausePlay(view: Any?) {
        view ?: return
        var c: Class<*>? = view.javaClass
        while (c != null) {
            val method = c.declaredMethods.firstOrNull {
                it.name == "F" && it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
            }
            if (method != null) {
                method.isAccessible = true
                runCatching { method.invoke(view) }.onFailure {
                    WeLogger.w(TAG, "pausePlay 调用失败：${it.message}")
                }
                return
            }
            c = c.superclass
        }
    }

    /** 拦截打点：2 秒内只打一条，既能确认「拦到了」，也不会把日志刷满。 */
    @Volatile
    private var lastBlockLogAt = 0L

    private fun noteBlocked(label: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockLogAt < 2000) return
        lastBlockLogAt = now
        WeLogger.d(TAG, "已拦截自动播放：$label（点播放按钮或进全屏页可正常播）")
    }

    /** 点击信号：任何点击都记一次，用于给「用户真的要播」开窗。 */
    private fun hookClickSignal(): Boolean = runCatching {
        XposedBridge.hookAllMethods(View::class.java, "performClick", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 这里故意不判页面：getTopMostActivity() 在滑动/首帧时可能为 null，
                // 判页面会导致点击窗口记不上，用户点播放也播不了。
                lastClickAt = SystemClock.elapsedRealtime()
            }
        })
    }.isSuccess

    private fun allowWithin(windowMs: Long): Boolean {
        val name = getTopMostActivity()?.javaClass?.name
        // 全屏播放页一律放行。
        if (name != null && FULLSCREEN_PAGES.any { name.contains(it) }) return true
        // 窗口内有过真实点击：点播放按钮、点开视频都算。
        if (SystemClock.elapsedRealtime() - lastClickAt < windowMs) return true
        // 拿不到页面也不放行：这几个播放器类都在 plugin.sns 下，只服务朋友圈；
        // 误拦的代价只是「滑动回来不自动播」，而放行就等于自动播放照旧。
        return false
    }
}