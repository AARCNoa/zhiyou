package com.AARCNoa.zhiyou.features.core

import android.content.Context
import com.AARCNoa.zhiyou.constants.Preferences
import com.AARCNoa.zhiyou.preferences.WePrefs
import com.AARCNoa.zhiyou.utils.TargetProcesses
import com.AARCNoa.zhiyou.utils.WeLogger

abstract class SwitchFeature : BaseFeature() {

    companion object {
        /** 带分类总开关的分类名（见 [Preferences.hiddenContactsMasterEnabled]）。 */
        const val CATEGORY_WITH_MASTER_SWITCH = "密友功能"
    }

    /** Default state when the user has never toggled this feature. */
    open val defaultEnabled: Boolean = false

    /**
     * 开关的「期望状态」：用户显式设置过就用存储值，否则回落到 [defaultEnabled]。
     *
     * 设置 UI 的计数与初始渲染都必须用它。只读 `WePrefs.getBoolOrFalse` 会把「从未点过、
     * 但默认开启」的功能显示成关闭，而标题计数读运行时 `isEnabled` 又把它算作已启用，
     * 于是出现「已启用 1 · 共 10 项」但列表里全是关闭。
     */
    val effectiveEnabled: Boolean
        get() = masterSwitchAllows && WePrefs.getBoolOrDef(name, defaultEnabled)

    /** 「密友功能」总开关关掉时该分类下所有功能都不生效，与 [startup] 的加载条件保持一致。 */
    private val masterSwitchAllows: Boolean
        get() = CATEGORY_WITH_MASTER_SWITCH !in categories || Preferences.hiddenContactsMasterEnabled

    /**
     * 该功能允许在哪些进程启动，按位组合 [TargetProcesses.PROC_MAIN] / [TargetProcesses.PROC_PUSH] 等。
     *
     * 用“声明”而不是“当场判断当前进程”的原因：非主进程的加载由 [NonMainFeatures] 白名单驱动，
     * 主进程需要**静态**知道哪些功能本应在非主进程跑，才能校验白名单有没有漏登记。
     */
    open val targetProcessMask: Int
        get() = TargetProcesses.PROC_MAIN

    /** Whether this feature should load in the current process. Defaults to the main process only. */
    protected open val shouldLoadInCurrentProcess: Boolean
        get() = (targetProcessMask and TargetProcesses.currentType) != 0

    /** Whether the feature should be active at startup, given the cached preference. */
    protected open val shouldEnableOnStartup: Boolean
        get() = _isEnabled

    final override fun startup() {
        if (!shouldLoadInCurrentProcess) return
        // 「密友功能」分类有总开关：关掉总开关时整个分类都不加载（各功能自身开关状态保留）。
        if (CATEGORY_WITH_MASTER_SWITCH in categories && !Preferences.hiddenContactsMasterEnabled) return
        _isEnabled = effectiveEnabled
        if (shouldEnableOnStartup) enable()
    }

    /** Cached user preference (desired state). Distinct from [isActive], the runtime truth. */
    @Suppress("PropertyName")
    protected var _isEnabled = false

    var isEnabled
        get() = _isEnabled
        set(value) {
            if (_isEnabled == value) return
            _isEnabled = value
            if (value) {
                WeLogger.i("SwitchFeature", "enabling $displayName...")
                enable()
            } else {
                WeLogger.i("SwitchFeature", "disabling $displayName...")
                disable()
            }
        }

    private var toggleCompletionCallback: (() -> Unit)? = null

    open fun onBeforeToggle(newState: Boolean, context: Context): Boolean = true

    /** 设置 UI 注册的「状态已变更」回调；页面销毁时必须传 null 清除，避免单例持有 Activity。 */
    fun setToggleCompletionCallback(callback: (() -> Unit)?) {
        toggleCompletionCallback = callback
    }

    /** 在确认弹窗里确认后调用：落库、立即应用新状态，并通知 UI 刷新。 */
    fun applyToggle(newState: Boolean) {
        WePrefs.putBool(name, newState)
        isEnabled = newState
        toggleCompletionCallback?.invoke()
    }
}
