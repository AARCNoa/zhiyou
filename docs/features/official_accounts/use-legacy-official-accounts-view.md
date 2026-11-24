# 恢复旧版公众号列表

**公众号** · 可开关 · 仅旧版微信可用 — 把新版公众号入口重定向到旧版列表。

| 原页面 | 重定向到 |
| --- | --- |
| `BizFlutterTLFlutterViewActivity`（Flutter 版列表） | `NewBizConversationUI` |
| `BizTimeLineUI`（时间线） | 同上 |

## 说明

- 新版本微信（含 8.0.80）已在代码里移除旧 UI，本功能**实际不可用**，只作为旧版本兼容开关保留，自检页显示「不适用」。
- 重定向前会 `resolveActivity` 检查目标页是否存在，不存在就跳过，不会白屏或崩溃。
- 判断不到旧 UI 时只写一条 debug 日志，界面行为与未启用时一致。
