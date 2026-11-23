package com.AARCNoa.zhiyou.utils.hook_status

import android.content.Context
import com.AARCNoa.zhiyou.constants.PackageNames
import com.AARCNoa.zhiyou.utils.HostFramework
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.libxposed.service.XposedServiceHelper.OnServiceListener
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Activation status detection via the libxposed service.
 *
 * Only intended to be used in the module process. Modern frameworks (LSPosed etc.)
 * bind an [XposedService] to the module process, from which the activation state and
 * scope can be read. The legacy de.robv api is not consulted here.
 */
object HookStatus {

    val xposedService: MutableStateFlow<XposedService?> = MutableStateFlow(null)

    private var xposedServiceListenerRegistered = false
    private val xposedServiceListener = object : OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            xposedService.value = service
            // 框架自报的名字/版本最准：写进跨进程缓存，微信内设置页读同一份（多进程 MMKV）。
            HostFramework.recordFromService(
                runCatching { service.frameworkName }.getOrNull(),
                runCatching { service.frameworkVersion }.getOrNull(),
                runCatching { service.apiVersion }.getOrDefault(0),
            )
        }

        override fun onServiceDied(service: XposedService) {
            xposedService.value = null
        }
    }

    fun init(context: Context) {
        if (context.packageName == PackageNames.MODULE && !xposedServiceListenerRegistered) {
            XposedServiceHelper.registerListener(xposedServiceListener)
            xposedServiceListenerRegistered = true
        }
    }
}
