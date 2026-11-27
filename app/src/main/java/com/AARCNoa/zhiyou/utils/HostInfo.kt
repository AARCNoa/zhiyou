package com.AARCNoa.zhiyou.utils

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.AARCNoa.zhiyou.constants.PackageNames

enum class HostSpecies { WeChat, WeKit, Unknown }

data class HostInfoImpl(
    val application: Application,
    val packageName: String,
    val hostName: String,
    val versionCode: Long,
    val versionName: String,
    val hostSpecies: HostSpecies
)

object HostInfo {

    private lateinit var _info: HostInfoImpl

    val info: HostInfoImpl get() = _info
    val application: Application get() = _info.application
    val appInfo: ApplicationInfo get() = application.applicationInfo
    val packageName: String get() = _info.packageName
    val versionCode: Long get() = _info.versionCode
    val versionName: String get() = _info.versionName
    val isModule: Boolean get() = _info.hostSpecies == HostSpecies.WeKit
    val isHost: Boolean get() = !isModule

    /**
     * 宿主是否为 Google Play 版。
     *
     * 读取的是微信自己的 `BuildConfig`，该 class 只存在于微信进程：模块自己的设置页进程
     * （包名 com.AARCNoa.zhiyou）加载不到它，会抛 NoClassDefFoundError。设置页的
     * `effectiveEnabled` 会用到按此判断的「默认开启」功能，因此这里必须吞掉异常退回
     * false（视为非 Play），绝不能让模块自己闪退。
     */
    val isHostGooglePlay: Boolean by lazy {
        runCatching {
            com.tencent.mm.boot.BuildConfig.BUILD_TAG.contains("GP", ignoreCase = true)
        }.getOrDefault(false)
    }

    fun init(application: Application) {
        check(!::_info.isInitialized) { "HostInfo has already been initialized" }

        val pm = application.packageManager
        val packageName = application.packageName
        val packageInfo = try {
            pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            WeLogger.e("HostInfo", "failed to get package info", e)
            throw e
        }

        _info = HostInfoImpl(
            application = application,
            packageName = packageName,
            hostName = application.applicationInfo.loadLabel(pm).toString(),
            versionCode = packageInfo.longVersionCode,
            versionName = packageInfo.versionName.orEmpty(),
            hostSpecies = run {
                if (PackageNames.isWeChat(packageName)) return@run HostSpecies.WeChat
                return@run when (packageName) {
                    PackageNames.MODULE -> HostSpecies.WeKit
                    else -> HostSpecies.Unknown
                }
            }
        )
    }
}
