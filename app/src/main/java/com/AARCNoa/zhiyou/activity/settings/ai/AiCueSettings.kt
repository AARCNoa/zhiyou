package com.AARCNoa.zhiyou.activity.settings.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.AARCNoa.zhiyou.features.ai.AiConfig
import com.AARCNoa.zhiyou.features.items.chat.AiCueSound
import com.AARCNoa.zhiyou.features.items.moments.AiMomentsComment
import com.AARCNoa.zhiyou.ui.style.SoftCard
import com.AARCNoa.zhiyou.ui.style.WxPalette

/**
 * AI 页的「设置」页：回复延迟 + 朋友圈帮我评论 + 双击提示音。
 *
 * AI 相关能力都收在这里，不散到各自的功能分类 —— 用户找「AI 还能做什么」时只看这一页。
 * 提示音放在这里而不是全局设置，是因为它只影响 AI 建议这一个手势。
 */
@Composable
internal fun AiCueSettings() {
    val styles = AiCueSound.Style.values()
    var enabled by remember { mutableStateOf(AiConfig.isCueSoundEnabled) }
    var selectedId by remember { mutableStateOf(AiConfig.cueSoundId) }
    var momentsComment by remember { mutableStateOf(AiConfig.isMomentsCommentEnabled) }
    var delaySec by remember { mutableStateOf(AiConfig.replyDelaySec) }
    val selectedIndex = AiCueSound.Style.indexOf(selectedId)

    // 回复延迟的原地编辑态：点秒数进入输入，回车 / 点「完成」/ 失焦都保存。
    var editingDelay by remember { mutableStateOf(false) }
    var delayInput by remember { mutableStateOf("") }
    var delayHadFocus by remember { mutableStateOf(false) }
    val delayFocus = remember { FocusRequester() }

    fun commitDelay() {
        if (!editingDelay) return
        // 夹取只在 AiConfig 里做一次；这里回读，界面显示的就是真正存下去的值。
        // 输入为空或非数字时回读的仍是旧值，等于「没改」。
        delayInput.toIntOrNull()?.let { AiConfig.setReplyDelaySec(it) }
        delaySec = AiConfig.replyDelaySec
        editingDelay = false
        delayHadFocus = false
    }

    LaunchedEffect(editingDelay) {
        if (editingDelay) delayFocus.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 回复延迟：自动回复的静默窗口。选一下即生效，下一次自动回复就用新值。
        SoftCard(contentPadding = 0) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "回复延迟",
                        fontSize = 14.sp,
                        color = WxPalette.textPrimary(),
                    )
                    Spacer(Modifier.weight(1f))
                    // 原地输入：点秒数变成输入框，回车或点「完成」即保存，不再铺一排 chip。
                    if (editingDelay) {
                        BasicTextField(
                            value = delayInput,
                            onValueChange = { text ->
                                delayInput = text.filter { it.isDigit() }.take(2)
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = WxPalette.Primary,
                                textAlign = TextAlign.End,
                            ),
                            // 光标跟着内容走：两位数时自动收窄，不会把第二个字推出框外。
                            cursorBrush = SolidColor(WxPalette.Primary),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { commitDelay() }),
                            modifier = Modifier
                                .width(44.dp)
                                .focusRequester(delayFocus)
                                // 首次出现时焦点回调会先来一次「未聚焦」：只有真正拿到过焦点，
                                // 之后的失焦才算用户离开，否则输入框刚展开就被收起来。
                                .onFocusChanged { state ->
                                    if (state.isFocused) {
                                        delayHadFocus = true
                                    } else if (delayHadFocus) {
                                        commitDelay()
                                    }
                                },
                        )
                        Text(
                            text = "秒",
                            fontSize = 13.sp,
                            color = WxPalette.textSecondary(),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "完成",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = WxPalette.Primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { commitDelay() }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    } else {
                        Text(
                            text = "$delaySec 秒",
                            fontSize = 13.sp,
                            color = WxPalette.Primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    delayInput = delaySec.toString()
                                    editingDelay = true
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = "点秒数可直接输入（${AiConfig.MIN_REPLY_DELAY_SEC}~${AiConfig.MAX_REPLY_DELAY_SEC} 秒）",
                    fontSize = 11.sp,
                    color = WxPalette.textSecondary(),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        SoftCard(contentPadding = 0) {
            AiSwitchRow(
                title = "朋友圈帮我评论",
                subtitle = "长按朋友圈文字，AI 写三条不同风格的评论",
                checked = momentsComment,
                onCheckedChange = {
                    momentsComment = it
                    AiConfig.setMomentsCommentEnabled(it)
                    // 立刻生效：只写配置的话，关掉后已注册的菜单项仍会出现在长按菜单里。
                    runCatching { AiMomentsComment.sync() }
                },
                showDivider = true,
            )

            AiSwitchRow(
                title = "双击提示音",
                subtitle = "聊天页双击触发 AI 建议时响一声",
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    AiConfig.setCueSoundEnabled(it)
                    // 打开开关时试听一次当前提示音。
                    if (it) AiCueSound.preview(selectedIndex)
                },
                // 收起时不要留一条孤零零的分隔线。
                showDivider = enabled,
            )

            // 音色跟着开关走：开着才展开，关掉整块收起。
            AnimatedVisibility(
                visible = enabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    styles.forEachIndexed { index, item ->
                        CueStyleRow(
                            label = item.label,
                            hint = item.hint,
                            selected = index == selectedIndex,
                            onClick = {
                                selectedId = item.id
                                AiConfig.setCueSoundId(item.id)
                                // 选中的同时试听：不用再单独点一次「播放」。
                                AiCueSound.preview(index)
                            },
                            showDivider = index != styles.lastIndex,
                        )
                    }
                }
            }
        }

        Text(
            text = "点一下试听并选中。关掉开关后双击不再发声，卡片照常弹出。",
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun CueStyleRow(
    label: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (selected) WxPalette.Primary else WxPalette.divider())
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    color = WxPalette.textPrimary(),
                )
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = WxPalette.textSecondary(),
                )
            }
            Text(
                text = "试听",
                fontSize = 12.sp,
                color = WxPalette.Primary,
            )
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(0.5.dp)
                    .background(WxPalette.divider())
            )
        }
    }
}
