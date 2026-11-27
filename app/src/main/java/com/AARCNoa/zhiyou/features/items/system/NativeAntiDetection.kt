package com.AARCNoa.zhiyou.features.items.system

import android.content.pm.PackageManager
import com.AARCNoa.zhiyou.constants.PackageNames
import com.AARCNoa.zhiyou.features.core.ApiFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.fs.KnownPaths
import com.AARCNoa.zhiyou.utils.fs.createDirsSafe
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.writeText

/**
 * Native 层反检测，静默功能：没有开关，随模块启动常驻生效。
 */
@Feature(
    name = "阻止 Native 检测",
    categories = ["系统与隐私"],
    description = "在 Native 层拦截 /proc/self/maps 扫描、链接库枚举、模块安装探测与反调试, 防止微信通过绕过 Java Hook 的方式检测本模块与 Hook 框架"
)
object NativeAntiDetection : ApiFeature() {

    private const val TAG = "NativeAntiDetection"

    /**
     * On-disk request flag. `native_init` reads this the moment
     * `libzhiyou_native.so` loads (before WeChat's anti-tamper runs) and, if it
     * exists, installs the libc/libdl hooks using the tokens listed inside it.
     *
     * 它只负责「下次启动」的最早安装；本次运行内的立即生效走 [nativeEnable]。
     *
     * The directory must match `flag_file_path()` in `native_hook.rs`. That path
     * is fixed inside the prebuilt `.so` under `jniLibs` and is therefore `zhiyou`,
     * not [KnownPaths.moduleData]; moving it needs the native library rebuilt.
     */
    private val flagFile by lazy {
        KnownPaths.moduleData.resolveSibling("zhiyou").createDirsSafe() /
                "native_anti_detection_enabled.flag"
    }

    /** 立即安装 native 钩子（无需重启微信）；入参为换行分隔的隐藏 token。 */
    private external fun nativeEnable(tokens: String): Boolean

    /** 运行期关闭 native 钩子（替换函数保留但直接放行，等同未安装）。 */
    private external fun nativeDisable()

    override fun onEnable() {
        val payload = collectModuleTokens().joinToString("\n")
        // 给下次启动准备的持久化请求。
        runCatching { flagFile.writeText(payload) }
            .onFailure { WeLogger.e(TAG, "failed to write anti-detection flag", it) }
        // 首次安装/升级的那次启动，native_init（早于 Java）读不到 flag，
        // 这里直接调 JNI 当次安装，做到装完即生效。
        val immediate = runCatching { nativeEnable(payload) }
            .onFailure { WeLogger.w(TAG, "native hooks not installed immediately: ${it.message}") }
            .getOrDefault(false)
        WeLogger.i(TAG, "anti-detection enabled (flag written, nativeNow=$immediate)")
    }

    override fun onDisable() {
        runCatching { flagFile.deleteIfExists() }
            .onFailure { WeLogger.e(TAG, "failed to remove anti-detection flag", it) }
        runCatching { nativeDisable() }
            .onFailure { WeLogger.w(TAG, "native hooks not disabled immediately: ${it.message}") }
        WeLogger.i(TAG, "anti-detection disabled at runtime")
    }

    /**
     * Resolve every on-disk string that identifies this module so the native
     * hooks can hide them from WeChat. Always includes the package name;
     * augments with the APK source dir, data dir and native lib dir when the
     * host lets us query them.
     */
    private fun collectModuleTokens(): List<String> {
        val tokens = linkedSetOf(PackageNames.MODULE)

        runCatching {
            val pm = HostInfo.application.packageManager
            val info = pm.getApplicationInfo(PackageNames.MODULE, 0)
            info.sourceDir?.let(tokens::add)
            info.publicSourceDir?.let(tokens::add)
            info.dataDir?.let(tokens::add)
            info.nativeLibraryDir?.let(tokens::add)
        }.onFailure {
            if (it is PackageManager.NameNotFoundException) {
                WeLogger.d(TAG, "module package not visible; hiding by name only")
            } else {
                WeLogger.w(TAG, "failed to resolve module paths", it)
            }
        }

        return tokens.filter { it.isNotEmpty() }
    }
}
