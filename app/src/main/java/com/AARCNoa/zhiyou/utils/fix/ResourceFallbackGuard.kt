package com.AARCNoa.zhiyou.utils.fix

import android.content.res.Resources
import android.util.TypedValue
import com.AARCNoa.zhiyou.utils.WeLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException

/**
 * 「资源表里有 ID、APK 里没有文件」的资源缺失兜底。
 *
 * 触发场景（实测：微信 8.0.80 + 小米 HyperOS，资源补丁不完整或 APK 资源被精简）：
 * - `layout/baq` 的 `WeImageView` 读 `android:src="@raw/icon_filled_hangup"`，Resources 解析出
 *   `res/raw/icon_filled_hangup.svg`，但 APK 里没有这个文件 → `Resources$NotFoundException`
 *   → 经 `LayoutInflater` 包成 `InflateException`，打崩主线程；
 * - `layout/bxt` 的 CheckBox 读 `background` → `drawable/aoe`(selector) → 其中 `<item>` 引用的
 *   raw svg 同样缺失 → 抛的是 `XmlPullParserException: <item> tag requires a 'drawable' attribute`
 *   → 外层仍是 `Resources$NotFoundException` → CheckBox 构造失败，同样打崩主线程
 *   （「通话中来电」界面就是这么崩的）。
 *
 * 异常与模块无关（资源属于 com.tencent.mm），但崩溃发生在微信内部、用户无法自救，
 * 因此这里做最小化兜底。
 *
 * 兜底策略（只保护 res/raw 下的 svg 与所有 xml drawable）：
 * - 其余资源（图片、字符串等）：类型检查后立即放行，**不改变原调用路径**；
 * - 命中目标：调用原实现，只有确实抛「资源/文件找不到」时才降级为 null
 *   （ImageView 的 src 为空、selector 背景为空，界面继续渲染，不闪退）；
 *   其它异常原样抛出，不掩盖真实问题。
 *
 * 注意：`loadDrawableForCookie` 的 0 号参数是 Resources wrapper，`TypedValue` 在 1 号，
 * 不能写死下标，只能在参数里找第一个 TypedValue。
 */
object ResourceFallbackGuard {

    private const val TAG = "ResourceFallbackGuard"
    private const val RAW_SVG_PREFIX = "res/raw/"
    private const val SVG_SUFFIX = ".svg"
    private const val XML_SUFFIX = ".xml"

    @Volatile
    private var installed = false

    /** hook 是否真的挂上：[installed] 是「已尝试」哨兵，防止重复安装。 */
    @Volatile
    private var hookActive = false

    val isInstalled: Boolean get() = hookActive

    fun install() {
        if (installed) return
        installed = true
        runCatching {
            // ResourcesImpl 是 hidden 类（不在公开 SDK 中），必须用反射按名取。
            val resourcesImpl = Class.forName("android.content.res.ResourcesImpl", false, null)
            XposedBridge.hookAllMethods(
                resourcesImpl,
                "loadDrawableForCookie",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 签名在不同 Android 版本是 (Resources, TypedValue, int, int) /
                        // (Resources, TypedValue, int, int, String)，0 号参数一直是 Resources
                        // wrapper —— 早先按 args[0] 取 TypedValue 恒为 null，整段兜底一次都没命中过。
                        val value = param.args.firstOrNull { it is TypedValue } as? TypedValue ?: return
                        val path = value.string ?: return
                        // 只对 raw svg 与 xml drawable 走兜底，其它资源直接放行。
                        val isRawSvg = path.startsWith(RAW_SVG_PREFIX) && path.endsWith(SVG_SUFFIX)
                        val isXml = path.endsWith(XML_SUFFIX)
                        if (!isRawSvg && !isXml) return

                        try {
                            param.result = XposedBridge.invokeOriginalMethod(
                                param.method,
                                param.thisObject,
                                param.args,
                            )
                        } catch (t: Throwable) {
                            if (t.isMissingResource()) {
                                WeLogger.w(TAG, "missing resource, degrade to null: $path")
                                param.result = null
                            } else {
                                throw t
                            }
                        }
                    }
                },
            )
            hookActive = true
            // 成功状态由 WeLauncher 的启动横幅统一输出，这里只留 logcat。
            WeLogger.d(TAG, "installed for svg / xml drawables")
        }.onFailure { e ->
            WeLogger.e(TAG, "install failed", e)
        }
    }

    private fun Throwable.isMissingResource(): Boolean {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < 8) {
            if (current is Resources.NotFoundException || current is FileNotFoundException) return true
            // selector / state-list 里 item 引用的子 drawable 缺失时抛的是这个，
            // 不是 NotFoundException，必须一起兜住。
            if (current is XmlPullParserException &&
                current.message.orEmpty().contains("requires a 'drawable' attribute")
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }
}
