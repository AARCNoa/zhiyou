package com.AARCNoa.zhiyou.features.core

import com.AARCNoa.zhiyou.utils.TargetProcesses

abstract class ApiFeature : BaseFeature() {

    override fun startup() {
        if (!TargetProcesses.isInMain) return
        enable()
    }
}
