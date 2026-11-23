package com.AARCNoa.zhiyou.utils.fs

import android.os.Environment
import com.AARCNoa.zhiyou.BuildConfig
import com.AARCNoa.zhiyou.constants.PackageNames
import com.AARCNoa.zhiyou.utils.HostInfo
import java.nio.file.Path
import kotlin.io.path.div

object KnownPaths {

    /** 导出 / 上传的公开目录名。与 [com.AARCNoa.zhiyou.BuildConfig.TAG] 分开：
     *  TAG 还用于日志和内部数据目录，改名会丢历史日志，这里只影响 Download 下的可见目录。 */
    const val PUBLIC_DIR = "zhiyou"


    val internalStorage: Path by lazy {
        Environment.getExternalStorageDirectory().asPath
    }

    val moduleData by lazy {
        (internalStorage / "Android" / "data" /
                runCatching { HostInfo.packageName }.getOrDefault(PackageNames.WECHAT) /
                BuildConfig.TAG).createDirsSafe()
    }

    val codeCacheDir: Path by lazy {
        HostInfo.application.codeCacheDir.asPath
    }

    /** 模块数据根目录（Python 脚本等扩展数据挂在它下面）。 */
    val moduleRoot: Path by lazy { moduleData }

    val moduleCache by lazy {
        (internalStorage / "Android" / "data" /
                runCatching { HostInfo.packageName }.getOrDefault(PackageNames.WECHAT)
                / "cache" / BuildConfig.TAG).createDirsSafe()
    }

    val moduleAssets by lazy {
        (moduleData / "assets").createDirsSafe()
    }

    val downloads by lazy {
        (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath() / PUBLIC_DIR)
            .createDirsSafe()
    }
}
