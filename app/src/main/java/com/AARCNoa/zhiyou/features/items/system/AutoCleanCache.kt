package com.AARCNoa.zhiyou.features.items.system

import androidx.activity.ComponentActivity
import com.AARCNoa.zhiyou.features.core.ClickableFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.showToastSuspend
import com.AARCNoa.zhiyou.utils.formatBytesSize
import com.AARCNoa.zhiyou.utils.formatEpoch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.milliseconds

@Feature(name = "清理缓存垃圾", categories = ["系统与隐私"], description = "自动或手动清理微信的缓存")
object AutoCleanCache : ClickableFeature() {

    private const val TAG = "AutoCleanCache"
    private const val CLEAN_INTERVAL = 30 * 60 * 1000L // 每 30 分钟清理一次

    private var cleanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cleanPaths: List<Path>
        get() {
            val paths = mutableListOf<Path>()

            val dataDir = HostInfo.application.filesDir.parentFile?.toPath() ?: return paths
            paths.add(dataDir / "cache")
            paths.add(dataDir / "MicroMsg" / "crash")
            paths.add(dataDir / "appbrand")
            paths.add(dataDir / "cache" / "appbrand")
            paths.add(dataDir / "MicroMsg" / "appbrand")
            paths.add(dataDir / "cache" / "liteapp")
            paths.add(dataDir / "files" / "liteapp")
            paths.add(dataDir / "tinker")
            paths.add(dataDir / "tinker_server")
            paths.add(dataDir / "tinker_temp")

            val externalCacheDir = HostInfo.application.externalCacheDir
            if (externalCacheDir != null) {
                val storageDataDir = externalCacheDir.toPath().parent ?: return paths
                paths.add(storageDataDir / "cache")
                paths.add(storageDataDir / "files" / "xlog")
                paths.add(storageDataDir / "files" / "onelog")
                paths.add(storageDataDir / "files" / "tbslog")
                paths.add(storageDataDir / "files" / "Tencent" / "tbs_common_log")
                paths.add(storageDataDir / "files" / "Tencent" / "tbs_live_log")
            }

            return paths
        }

    override fun onEnable() {
        startCleaningJob()
    }

    override fun onDisable() {
        // 关闭开关后必须停掉定时任务：否则用户已经关掉「自动清理」，
        // 这个 while 循环仍会每 30 分钟删一次缓存文件。
        cleanJob?.cancel()
        cleanJob = null
    }

    private fun startCleaningJob() {
        cleanJob?.cancel()
        cleanJob = scope.launch {
            while (isActive) {
                performClean()
                delay(CLEAN_INTERVAL.milliseconds)
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun performClean(): Long {
        var totalDeletedBytes = 0L
        cleanPaths.forEach { path ->
            try {
                WeLogger.d(TAG, "deleting $path")
                if (path.exists()) {
                    totalDeletedBytes += calculateSize(path)
                    path.deleteRecursively()
                }
            } catch (e: Exception) {
                WeLogger.w(TAG, "exception during cleaning: ${path.fileName}, ${e.message}")
            }
        }
        return totalDeletedBytes
    }

    private fun calculateSize(path: Path): Long {
        val file = path.toFile()
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()

        var size = 0L
        file.listFiles()?.forEach {
            size += if (it.isDirectory) calculateSize(it.toPath()) else it.length()
        }
        return size
    }

    override fun onClick(context: ComponentActivity) {
        scope.launch {
            val deletedSize = performClean()
            val sizeText = formatBytesSize(deletedSize)

            val timeText =
                if (isEnabled) "\n下次自动清理将在 ${formatEpoch(System.currentTimeMillis() + CLEAN_INTERVAL)} 进行"
                else ""

            showToastSuspend(context, "缓存清理完成, 共释放 $sizeText$timeText")

            if (isEnabled) startCleaningJob()
        }
    }
}
