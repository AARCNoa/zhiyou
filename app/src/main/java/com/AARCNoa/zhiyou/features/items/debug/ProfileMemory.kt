package com.AARCNoa.zhiyou.features.items.debug

import android.os.Debug
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.AARCNoa.zhiyou.features.core.ClickableFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.ui.content.AlertDialogContent
import com.AARCNoa.zhiyou.ui.content.DefaultColumn
import com.AARCNoa.zhiyou.ui.content.TextButton
import com.AARCNoa.zhiyou.ui.utils.showComposeDialog
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.copyToClipboard

@Feature(name = "内存分析", categories = ["调试"], description = "分析微信内存占用组成")
object ProfileMemory : ClickableFeature() {

    private const val TAG = "ProfileMemory"

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val snapshot = remember { takeSnapshot() }

            AlertDialogContent(
                title = { Text("内存分析") },
                text = {
                    DefaultColumn {
                        Text(
                            snapshot,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                        Text(
                            "PSS 是系统统计的实际占用；VmRSS / VmPeak 是虚拟内存，" +
                                "包含大量保留地址，不能当作真实占用。"
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("关闭") } },
                confirmButton = {
                    TextButton(onClick = {
                        copyToClipboard(snapshot)
                        onDismiss()
                    }) { Text("复制") }
                }
            )
        }
    }

    /**
     * 采样一次并写入运行日志。
     *
     * 用 I 级而不是 D 级：D 在正式包里被 [WeLogger] 直接丢弃，用户点完等于什么都没发生，
     * 而这条数据正是反馈内存问题时最需要的。
     */
    private fun takeSnapshot(): String {
        val pid = Process.myPid()

        // 1. Debug.MemoryInfo — detailed PSS/RSS breakdown
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)

        // 2. Runtime heap stats
        val rt = Runtime.getRuntime()
        val heapUsedKb = (rt.totalMemory() - rt.freeMemory()) / 1024
        val heapMaxKb = rt.maxMemory() / 1024

        // 3. /proc/self/status — native RSS
        val procStatus = readProcStatus()

        val snapshot = buildString {
            appendLine("=== $TAG snapshot (pid=$pid) ===")
            appendLine("PSS total      : ${mi.totalPss} kB")
            appendLine("- dalvik PSS   : ${mi.dalvikPss} kB")
            appendLine("- native PSS   : ${mi.nativePss} kB")
            appendLine("- other PSS    : ${mi.otherPss} kB")
            appendLine("Private dirty  : ${mi.totalPrivateDirty} kB")
            appendLine("Private clean  : ${mi.totalPrivateClean} kB")
            appendLine("Shared dirty   : ${mi.totalSharedDirty} kB")
            appendLine("Heap used (RT) : $heapUsedKb kB / $heapMaxKb kB")
            appendLine("Native VmRSS   : ${procStatus["VmRSS"]} kB")
            appendLine("Native VmPeak  : ${procStatus["VmPeak"]} kB")
            appendLine("Native VmSwap  : ${procStatus["VmSwap"]} kB")
            appendLine("Threads        : ${procStatus["Threads"]}")
        }

        WeLogger.i(TAG, "\n" + snapshot)
        return snapshot
    }

    // ── /proc/self/status parser ──────────────────────────────────────────────

    private fun readProcStatus(): Map<String, String> = buildMap {
        runCatching {
            java.io.File("/proc/self/status").forEachLine { line ->
                val parts = line.split(":\\s+".toRegex(), limit = 2)
                if (parts.size == 2) put(parts[0].trim(), parts[1].trim().removeSuffix(" kB"))
            }
        }.onFailure { WeLogger.e(TAG, "/proc read failed", it) }
    }
}
