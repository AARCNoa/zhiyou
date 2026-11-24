package com.AARCNoa.zhiyou.ui.panel

import androidx.compose.ui.graphics.vector.ImageVector
import com.AARCNoa.zhiyou.ui.icons.OutlinedIcons

internal fun <T> panelMultiSelectActions(
    items: List<T>,
    selectedKeys: Set<String>,
    key: (T) -> String,
    terminalIcon: ImageVector,
    terminalLabel: String,
    onClose: () -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onTerminalAction: (List<T>) -> Unit,
): List<PanelAction> = listOf(
    PanelAction(OutlinedIcons.Close, "关闭", showLabel = true, onClick = onClose),
    PanelAction(
        OutlinedIcons.Select_all,
        "全选",
        enabled = items.isNotEmpty(),
        showLabel = true,
    ) {
        onSelectionChange(items.mapTo(linkedSetOf(), key))
    },
    PanelAction(
        OutlinedIcons.Deselect,
        "反选",
        enabled = items.isNotEmpty(),
        showLabel = true,
    ) {
        onSelectionChange(invertPanelSelection(selectedKeys, items, key))
    },
    PanelAction(
        OutlinedIcons.Done_all,
        "连选",
        enabled = selectedKeys.size > 1,
        showLabel = true,
    ) {
        onSelectionChange(closePanelSelectionRange(selectedKeys, items, key))
    },
    PanelAction(
        terminalIcon,
        terminalLabel,
        enabled = selectedKeys.isNotEmpty(),
        showLabel = true,
    ) {
        onTerminalAction(items.filter { key(it) in selectedKeys })
    },
)

private fun <T> invertPanelSelection(
    current: Set<String>,
    items: List<T>,
    key: (T) -> String,
): Set<String> {
    val candidates = items.mapTo(linkedSetOf(), key)
    return candidates.filterNotTo(linkedSetOf()) { it in current }
}

private fun <T> closePanelSelectionRange(
    current: Set<String>,
    items: List<T>,
    key: (T) -> String,
): Set<String> {
    val selectedIndexes = items.indices.filter { key(items[it]) in current }
    if (selectedIndexes.size <= 1) return current
    return current + (selectedIndexes.first()..selectedIndexes.last()).map { key(items[it]) }
}
