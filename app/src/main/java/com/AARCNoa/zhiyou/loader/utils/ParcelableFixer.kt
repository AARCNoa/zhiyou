package com.AARCNoa.zhiyou.loader.utils

import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.AARCNoa.zhiyou.constants.PackageNames
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.hookDirectly
import com.AARCNoa.zhiyou.utils.reflection.BString
import com.AARCNoa.zhiyou.utils.reflection.ClassLoaders

object ParcelableFixer {

    private const val TAG = "ParcelableFixer"

    lateinit var hybridClassLoader: ClassLoader
        private set

    private var initialized = false

    @Suppress("unused")
    fun init() {
        if (initialized) return
        initialized = true

        hybridClassLoader = object : ClassLoader(ClassLoaders.HOST) {
            override fun findClass(name: String): Class<*> = ClassLoaders.MODULE.loadClass(name)
        }

        hookIntentMethods()
    }

    private fun fixIntentExtrasClassLoader(intent: Intent?) {
        if (!isTargetIntent(intent)) return
        val cl = hybridClassLoader
        runCatching { intent?.setExtrasClassLoader(cl) }
    }

    private fun isTargetIntent(intent: Intent?): Boolean {
        intent ?: return false
        val className = intent.component?.className ?: return false
        return className.startsWith(PackageNames.MODULE)
    }

    private fun hookIntentMethods() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                (param.thisObject as? Intent)?.let { fixIntentExtrasClassLoader(it) }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val intent = param.thisObject as? Intent ?: return
                if (!isTargetIntent(intent)) return
                val cl = hybridClassLoader
                (param.result as? Bundle)?.classLoader = cl
            }
        }

        // 每个方法独立安装：Android 13 才有的 (String, Class) 重载在低版本不存在，
        // 若用一个 runCatching 包住整组，第一个失败会让后面全部不挂（旧实现就是这样）。
        val targets: List<Triple<String, List<Class<*>>, Boolean>> = listOf(
            Triple("getExtras", emptyList(), false),
            Triple("getBundleExtra", listOf(BString), false),
            Triple("getParcelableExtra", listOf(BString), false),
            Triple("getParcelableArrayListExtra", listOf(BString), false),
            Triple("getSerializableExtra", listOf(BString), false),
            // Android 13+
            Triple("getParcelableExtra", listOf(BString, Class::class.java), true),
            Triple("getParcelableArrayListExtra", listOf(BString, Class::class.java), true),
            Triple("getSerializableExtra", listOf(BString, Class::class.java), true),
        )

        targets.forEach { (name, params, optional) ->
            runCatching {
                val ref = Intent::class.reflekt()
                val method = if (params.isEmpty()) {
                    ref.firstMethod { this.name = name; parameters() }
                } else {
                    ref.firstMethod {
                        this.name = name
                        parameters(*params.toTypedArray())
                    }
                }
                method.hookDirectly(hook)
            }.onFailure {
                if (!optional) WeLogger.w(TAG, "failed to hook Intent.$name: ${it.message}")
            }
        }
    }
}
