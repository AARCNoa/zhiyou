# 恢复旧版公众号列表

> !!! 仅适用于旧版本微信 !!!
> 新版本已在代码中移除旧 UI, 无法继续使用本功能

## 类别

公众号

## 类型

可开关

## 描述

拦截跳转公众号的 `Intent`，把新版入口重定向到旧版列表：

| 原页面 | 重定向到 |
|---|---|
| `BizFlutterTLFlutterViewActivity`（Flutter 版列表） | `NewBizConversationUI` |
| `BizTimeLineUI`（时间线） | 同上 |

## 使用方法

启用后从公众号入口进入生效。在已经删除旧 UI 的微信版本（含 8.0.79）上，本功能**实际不可用**，
只作为旧版本兼容开关保留。

## 说明

- 重定向前会 `resolveActivity` 检查 `NewBizConversationUI` 是否存在，不存在就跳过，
  不会出现白屏或崩溃；
- 判断不到旧 UI 时只写一条 debug 日志，界面行为与未启用时一致；
- 自检页里该功能会显示为「不适用」。
