package com.AARCNoa.zhiyou.features.core

import com.AARCNoa.zhiyou.features.items.chat.BlockAllMentionNotifications
import com.AARCNoa.zhiyou.features.items.contacts.hidecontacts.HideContactsNotifications
import com.AARCNoa.zhiyou.features.items.miniapps.BypassUnderageGamingLimit
import com.AARCNoa.zhiyou.features.items.miniapps.ErudaConsole
import com.AARCNoa.zhiyou.features.items.miniapps.SkipRewardedAds
import com.AARCNoa.zhiyou.features.items.miniapps.SkipSplash
import com.AARCNoa.zhiyou.features.items.payment.FingerprintPay

/**
 * 非主进程（push / appbrand）需要启动的功能白名单。
 *
 * 为什么要有这个文件：[FeaturesProvider.ALL_HOOK_ITEMS] 一被读取就会实例化全部功能对象。
 * 实测 push 进程因此从原生 108MB 涨到 307MB，在内存吃紧的机器上会被 LMK 反复杀死；
 * 进程被杀时 socket 留在 CLOSE-WAIT，微信连接池没有可用连接，发消息就全是红色感叹号。
 *
 * 维护规则：新增功能若把 [SwitchFeature.targetProcessMask] 声明到非主进程位，
 * **必须**同时在这里登记。主进程启动时 [FeaturesLoader] 会校验，漏了会打 E 日志。
 */
internal object NonMainFeatures {

    /** 白名单里的类，供主进程校验用（lazy：非主进程不会读取它，避免额外类加载）。 */
    val registeredClasses: Set<Class<*>> by lazy {
        setOf(
            HideContactsNotifications::class.java,
            BlockAllMentionNotifications::class.java,
            BypassUnderageGamingLimit::class.java,
            ErudaConsole::class.java,
            SkipRewardedAds::class.java,
            SkipSplash::class.java,
            FingerprintPay::class.java,
        )
    }

    /**
     * 补上元数据。
     *
     * [BaseFeature.name] / [BaseFeature.categories] 平时是由构建期生成的 FeaturesProvider 在构造时赋值的；
     * 非主进程刻意不读那个列表，所以必须在这里显式补一份，否则：
     *   - `name` 为空 → 缓存路径退化成 `dex_cache/.json`，描述符还原必然失败；
     *   - `name` 为空 → 偏好键也是空串，功能永远读不到自己的开关。
     */
    private fun <T : BaseFeature> T.meta(name: String, categories: List<String>): T = apply {
        this.name = name
        this.categories = categories
    }

    /**
     * 本进程需要启动的功能实例。
     *
     * 用 when 分支而不是 listOf(全部)：只有命中的那一支会初始化对应对象，
     * 避免 push 进程顺带把 appbrand 的五个功能类也加载进来。
     */
    fun itemsForCurrentProcess(): List<BaseFeature> =
        when (com.AARCNoa.zhiyou.utils.TargetProcesses.currentType) {
            // push 进程只剩通知抑制：微信在 :push 里构建通知，主进程装的 hook 收不到。
            com.AARCNoa.zhiyou.utils.TargetProcesses.PROC_PUSH -> listOf(
                HideContactsNotifications.meta("隐藏联系人通知抑制", listOf("API")),
                BlockAllMentionNotifications.meta("屏蔽@全体成员提醒", listOf("聊天")),
            )

            com.AARCNoa.zhiyou.utils.TargetProcesses.PROC_APPBRAND -> listOf(
                BypassUnderageGamingLimit.meta("绕过防沉迷", listOf("小程序")),
                ErudaConsole.meta("Eruda 调试面板", listOf("小程序")),
                SkipRewardedAds.meta("跳过激励广告", listOf("小程序")),
                SkipSplash.meta("跳过启动页面", listOf("小程序")),
                FingerprintPay.meta("指纹支付", listOf("红包与支付")),
            )

            else -> emptyList()
        }

    fun startAll() {
        itemsForCurrentProcess().forEach { feature ->
            runCatching { feature.startup() }
                .onFailure { com.AARCNoa.zhiyou.utils.WeLogger.e("NonMainFeatures", "startup failed: ${feature.name}", it) }
        }
    }
}
