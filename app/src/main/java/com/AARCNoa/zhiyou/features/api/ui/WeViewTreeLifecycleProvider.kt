package com.AARCNoa.zhiyou.features.api.ui

import android.app.Activity
import com.tencent.mm.ui.LauncherUI
import com.AARCNoa.zhiyou.features.core.ApiFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.ui.utils.LifecycleOwnerProvider
import com.AARCNoa.zhiyou.ui.utils.rootView
import com.AARCNoa.zhiyou.ui.utils.setLifecycleOwner

@Feature(name = "Compose 生命周期提供方", categories = ["API"])
object WeViewTreeLifecycleProvider : ApiFeature() {

    override fun onEnable() {
        LauncherUI::class.hookAfterOnCreate {
            val activity = thisObject as Activity

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner

            val decorView = activity.window.decorView
            decorView.setLifecycleOwner(lifecycleOwner)
            activity.rootView.setLifecycleOwner(lifecycleOwner)
        }
    }
}
