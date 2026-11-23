@file:Suppress("unused")

package com.AARCNoa.zhiyou.loader.entry.lxp

import androidx.annotation.Keep
import com.AARCNoa.zhiyou.constants.PackageNames
import com.AARCNoa.zhiyou.loader.entry.common.ModuleLoader
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

@Keep
class LxpHookEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        LxpHookImpl.init(this)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (PackageNames.isWeChat(param.packageName)) {
            if (param.isFirstPackage) {
                ModuleLoader.init(
                    param.classLoader,
                    LxpHookImpl,
                    LxpHookImpl,
                    this.moduleApplicationInfo.sourceDir
                )
            }
        }
    }
}
