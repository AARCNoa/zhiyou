package com.AARCNoa.zhiyou.features.items.miniapps

import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import com.AARCNoa.zhiyou.features.core.Feature

import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.utils.TargetProcesses
import com.AARCNoa.zhiyou.utils.WeLogger

@Feature(
    name = "绕过防沉迷",
    categories = ["小程序"],
    description = "绕过微信游戏小程序的未成年防沉迷\n绕过后可能没有声音, 看广告能恢复"
)
object BypassUnderageGamingLimit : SwitchFeature() {

    private const val TAG = "BypassUnderageGamingLimit"

    /**
     * 防沉迷拦截页的 URL 特征（8.0.80 实测）：
     * - 旧版人脸识别页：`jiazhang.qq.com/healthy/dist/faceRecognition/game_no.html`
     * - 8.0.80 家长平台：`jiazhang.qq.com/wap/family`
     * - 8.0.80 小游戏年龄提示：`game.weixin.qq.com/.../minigame/static/age-tips`
     *
     * 旧实现只认第一条的完整前缀。后两条在 8.0.80 上是新页面，认不到就等于没绕过；
     * 用 contains 覆盖原来的 startsWith，旧页面同样命中。
     */
    private val LIMIT_URL_MARKERS = arrayOf(
        "jiazhang.qq.com/healthy/dist/faceRecognition/game_no.html",
        "jiazhang.qq.com/wap/family",
        "minigame/static/age-tips",
        "faceRecognition",
    )

    override val targetProcessMask get() = TargetProcesses.PROC_MAIN or TargetProcesses.PROC_APPBRAND

    override fun onEnable() {
        var hooked = 0
        listOf(
            "com.tencent.xweb.pinus.PSWebview",
            "com.tencent.xweb.pinus.sdk.WebView",
            "com.tencent.xweb.WebView"
        ).forEach { className ->
            val clazz = className.toClassOrNull() ?: return@forEach
            runCatching {
                clazz.reflekt().firstMethod("loadUrl").hookBefore {
                    val url = args.getOrNull(0) as? String ?: return@hookBefore
                    if (LIMIT_URL_MARKERS.none { url.contains(it) }) return@hookBefore
                    val webView = thisObject as? View ?: return@hookBefore
                    // 移出可视区域而不是拦加载：页面本身正常存在，微信拿不到用户操作。
                    webView.translationX = 99999.0f
                    webView.translationY = 99999.0f
                    webView.scaleX = 0.01f
                    webView.scaleY = 0.01f
                    WeLogger.i(TAG, "已移走防沉迷页面：${url.take(120)}")
                }
                hooked++
            }.onFailure { WeLogger.e(TAG, "hook $className.loadUrl 失败", it) }
        }
        if (hooked == 0) {
            WeLogger.w(TAG, "三个 WebView 类都没 hook 上，防沉迷不会生效")
        }
    }
}
