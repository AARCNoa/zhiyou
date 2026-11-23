package com.AARCNoa.zhiyou.ui.style

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 模块设置界面的两套动效（只作用于模块自己的页面，不涉及任何微信 Hook）：
 *
 * - [fxReveal]：卡片随首次组合淡入 + 上移，[order] 决定错开序（每 80ms 一档）；
 * - [rememberGrowNumber]：数字从 0 滚到目标值（配合同屏进度条生长）。
 *
 * 动效恒开、不提供开关：早期版本有一个 `ui_anim_fx_enabled` 偏好（默认开），
 * 关掉后永久静默，反而成了「动效莫名不生效」的坑，已移除。
 */

/**
 * 滚动渐显：首次进入组合时淡入 + 上移归位，[order] 决定错开序（每 80ms 一档）。
 * 用在 LazyColumn 的 item 根修饰符上。
 */
@Composable
fun Modifier.fxReveal(order: Int = 0): Modifier {
    val alpha = remember { Animatable(0f) }
    val ty = remember { Animatable(26f) }
    LaunchedEffect(Unit) {
        delay((order % 8) * 80L)
        launch { alpha.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
        ty.animateTo(0f, tween(380, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        this.alpha = alpha.value
        this.translationY = ty.value
    }
}

/**
 * 数据生长：数字/比例从 0 滚到 [target]（0f..1f 或任意数值）。
 * 返回当前动画值，调用方自己格式化（如百分比、计数）。
 */
@Composable
fun rememberGrowNumber(target: Float, durationMs: Int = 700): Float {
    val value = remember { Animatable(0f) }
    LaunchedEffect(target) {
        value.snapTo(0f)
        value.animateTo(target, tween(durationMs, easing = FastOutSlowInEasing))
    }
    return value.value
}
