package com.AARCNoa.zhiyou.ui.utils.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.AARCNoa.zhiyou.constants.Preferences
import com.AARCNoa.zhiyou.preferences.WePrefs
import com.AARCNoa.zhiyou.ui.utils.theme.ThemeSettings.applyToWechat
import com.AARCNoa.zhiyou.ui.utils.theme.ThemeSettings.colorSpec
import com.AARCNoa.zhiyou.ui.utils.theme.ThemeSettings.dynamicWallpaper
import com.AARCNoa.zhiyou.ui.utils.theme.ThemeSettings.paletteStyle
import com.AARCNoa.zhiyou.ui.utils.theme.ThemeSettings.seedColor
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/** How the Settings UI decides light vs. dark. */
enum class AppThemeMode(val displayName: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式");

    /** Reads [isSystemInDarkTheme] for [SYSTEM]; must be called in a composable. */
    @Composable
    fun resolve(): Boolean = when (this) {
        SYSTEM -> isSystemInDarkTheme()
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromName(value: String?) = entries.find { it.name == value } ?: SYSTEM
    }
}

/**
 * Palette generation style. [supportsSpec2025] mirrors miuix's own downgrade rule (only these four
 * styles honor Spec2025; the rest fall back to Spec2021 regardless). [miuix] drives the settings
 * MiuixTheme; [materialKolor] drives the Material 3 scheme (both consume the same seed so the two
 * design systems stay in sync).
 */
enum class AppPaletteStyle(
    val displayName: String,
    val miuix: ThemePaletteStyle,
    val materialKolor: PaletteStyle,
) {
    TONAL_SPOT("Tonal Spot", ThemePaletteStyle.TonalSpot, PaletteStyle.TonalSpot),
    NEUTRAL("Neutral", ThemePaletteStyle.Neutral, PaletteStyle.Neutral),
    VIBRANT("Vibrant", ThemePaletteStyle.Vibrant, PaletteStyle.Vibrant),
    EXPRESSIVE("Expressive", ThemePaletteStyle.Expressive, PaletteStyle.Expressive),
    RAINBOW("Rainbow", ThemePaletteStyle.Rainbow, PaletteStyle.Rainbow),
    FRUIT_SALAD("Fruit Salad", ThemePaletteStyle.FruitSalad, PaletteStyle.FruitSalad),
    MONOCHROME("Monochrome", ThemePaletteStyle.Monochrome, PaletteStyle.Monochrome),
    FIDELITY("Fidelity", ThemePaletteStyle.Fidelity, PaletteStyle.Fidelity),
    CONTENT("Content", ThemePaletteStyle.Content, PaletteStyle.Content);

    val supportsSpec2025: Boolean
        get() = this == TONAL_SPOT || this == NEUTRAL || this == VIBRANT || this == EXPRESSIVE

    companion object {
        fun fromName(value: String?) = entries.find { it.name == value } ?: TONAL_SPOT
    }
}

/** Material color specification version. */
enum class AppColorSpec(
    val displayName: String,
    val miuix: ThemeColorSpec,
    val materialKolor: ColorSpec.SpecVersion,
) {
    SPEC_2021("Material 3 (2021)", ThemeColorSpec.Spec2021, ColorSpec.SpecVersion.SPEC_2021),
    SPEC_2025("Expressive (2025)", ThemeColorSpec.Spec2025, ColorSpec.SpecVersion.SPEC_2025);

    companion object {
        fun fromName(value: String?) = entries.find { it.name == value } ?: SPEC_2025
    }
}

/**
 * Observable theme state. Backed by [mutableStateOf] seeded from MMKV, so a change from a settings
 * row re-themes the visible module UI immediately; setters persist to MMKV. Enums are stored by
 * [Enum.name].
 *
 * Two consumers:
 * - the module's own UI ([ModuleTheme]) re-themes live from every value here;
 * - the UI injected into WeChat ([com.AARCNoa.zhiyou.ui.utils.InjectedUiTheme]) + native recoloring ([com.AARCNoa.zhiyou.features.items.beautify.MonetEngine]) only
 *   consult [applyToWechat]/[dynamicWallpaper]/[seedColor]/[paletteStyle]/[colorSpec], and NOT live —
 *   they read the persisted values once per WeChat launch.
 */
object ThemeSettings {

    /**
     * 偏好是否可读。
     *
     * MMKV 只在**微信进程**里由 `NativeLoader.init()` 初始化；模块自己的 App（知友 APK）
     * 是独立进程、独立 files 目录，读偏好会抛
     * `IllegalStateException: You should Call MMKV.initialize() first.`。
     *
     * 而 [com.AARCNoa.zhiyou.ui.style.WxPalette] 的取色函数会读 [themeMode]，Compose 一挂到窗口
     * 就触发本类初始化 —— 之前没有这层保护，点开知友 App 立刻闪退。
     *
     * 读不到就全部按默认值（跟随系统），App 端本来也不提供主题设置。
     * 首次判定结果缓存：微信进程里此时 MMKV 必然已就绪，App 进程里必然没有。
     */
    private val prefsReadable: Boolean by lazy { runCatching { WePrefs.default }.isSuccess }

    private fun readString(key: String): String? =
        if (prefsReadable) runCatching { WePrefs.getString(key) }.getOrNull() else null

    private fun readBool(key: String): Boolean =
        if (prefsReadable) runCatching { WePrefs.getBoolOrFalse(key) }.getOrDefault(false) else false

    private fun readInt(key: String, def: Int): Int =
        if (prefsReadable) runCatching { WePrefs.getIntOrDef(key, def) }.getOrDefault(def) else def

    var themeMode by mutableStateOf(AppThemeMode.fromName(readString(Preferences.THEME_MODE)))
        private set
    var customColor by mutableStateOf(readBool(Preferences.THEME_CUSTOM_COLOR))
        private set

    /** Seed the accent from the platform wallpaper accent (SDK >= 31) instead of [seedColor]. */
    var dynamicWallpaper by mutableStateOf(readBool(Preferences.THEME_DYNAMIC_WALLPAPER))
        private set
    var paletteStyle by mutableStateOf(
        AppPaletteStyle.fromName(readString(Preferences.THEME_PALETTE_STYLE))
    )
        private set
    var colorSpec by mutableStateOf(AppColorSpec.fromName(readString(Preferences.THEME_COLOR_SPEC)))
        private set

    /** Custom seed color (ARGB int) used when custom color is on and wallpaper color is off. */
    var seedColor by mutableIntStateOf(readInt(Preferences.THEME_SEED_COLOR, DEFAULT_SEED_COLOR))
        private set

    /**
     * Whether the custom color also applies to WeChat itself (injected module ComposeUI + native
     * recoloring via [com.AARCNoa.zhiyou.features.items.beautify.MonetEngine]). Does NOT take effect live — requires restarting WeChat.
     */
    var applyToWechat by mutableStateOf(readBool(Preferences.THEME_APPLY_TO_WECHAT))
        private set

    /** Spec coerced to 2021 when the current palette style can't honor 2025. */
    val effectiveColorSpec: AppColorSpec
        get() = if (paletteStyle.supportsSpec2025) colorSpec else AppColorSpec.SPEC_2021

    fun updateThemeMode(value: AppThemeMode) {
        themeMode = value
        if (prefsReadable) runCatching { WePrefs.putString(Preferences.THEME_MODE, value.name) }
    }

    fun updateCustomColor(value: Boolean) {
        customColor = value
        if (prefsReadable) runCatching { WePrefs.putBool(Preferences.THEME_CUSTOM_COLOR, value) }
    }

    fun updateDynamicWallpaper(value: Boolean) {
        dynamicWallpaper = value
        if (prefsReadable) runCatching { WePrefs.putBool(Preferences.THEME_DYNAMIC_WALLPAPER, value) }
    }

    fun updatePaletteStyle(value: AppPaletteStyle) {
        paletteStyle = value
        if (prefsReadable) runCatching { WePrefs.putString(Preferences.THEME_PALETTE_STYLE, value.name) }
    }

    fun updateColorSpec(value: AppColorSpec) {
        colorSpec = value
        if (prefsReadable) runCatching { WePrefs.putString(Preferences.THEME_COLOR_SPEC, value.name) }
    }

    fun updateSeedColor(value: Int) {
        seedColor = value
        if (prefsReadable) runCatching { WePrefs.putInt(Preferences.THEME_SEED_COLOR, value) }
    }

    fun updateApplyToWechat(value: Boolean) {
        applyToWechat = value
        if (prefsReadable) runCatching { WePrefs.putBool(Preferences.THEME_APPLY_TO_WECHAT, value) }
    }

    /** Default seed accent (WeChat green 0xFF07C160). */
    const val DEFAULT_SEED_COLOR: Int = 0xFF07C160.toInt()
}
